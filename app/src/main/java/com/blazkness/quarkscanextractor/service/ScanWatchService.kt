package com.blazkness.quarkscanextractor.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blazkness.quarkscanextractor.MainActivity
import com.blazkness.quarkscanextractor.R
import com.blazkness.quarkscanextractor.engine.ExportEngine
import com.blazkness.quarkscanextractor.notify.ScanNotifier
import com.blazkness.quarkscanextractor.shizuku.ShizukuRuntime
import com.blazkness.quarkscanextractor.shell.RootShellRunner
import com.blazkness.quarkscanextractor.shell.ShizukuShellRunner
import com.blazkness.quarkscanextractor.shell.ShellRunner
import com.blazkness.quarkscanextractor.util.QseLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 后台监视夸克扫描的前台服务。
 *
 * 为什么要服务：小米（HyperOS）会冻结后台普通进程，协程在里面跑不动；前台服务不冻结。
 *
 * 提醒规则（用户规定）：
 *  1. 只看「夸克被移出最近任务」这一个时机 —— 实测上滑移除后它的进程数会变成 0，以此判定。
 *     不再看扫描件是否"写完"、也不看前台是谁。
 *  2. 这个时机到了就报一次「现在有多少个扫描件缓存」；计数用的是本程序能导出的那些文件（*detect* 目录）。
 *  3. 如果提醒正挂着、而缓存内容变了（文件本身或大小/时间有变化），立刻撤掉旧通知再发新的。
 *  4. 进程序看列表时由 MainActivity 通知本服务撤掉提醒（列表里已经能看到）。
 */
class ScanWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runner: ShellRunner? = null

    /** 当前这条常驻通知是否正贴着（用来避免重复 start/stop）。 */
    private var notificationShown = false

    /** 进程起来后的第一轮只记录状态，不提醒（避免一启动就弹）。 */
    private var seeded = false
    private var lastQuarkAlive = false

    /** 上一次提醒时缓存的样子（用来判断"缓存到底有没有变"），跨进程重启保留。 */
    private var lastNotifiedSignature = ""

    /** 上一轮探测到的缓存指纹（用来在夸克没跑时兜底发现缓存变化）。 */
    private var prevSignature = ""

    /** 夸克当前是否在跑（决定轮询快慢）。 */
    private var quarkAlive = false

    /** Root 探测失败后的冷却截止时间（避免 Magisk 反复弹授权界面）。 */
    private var rootProbeCooldownUntil = 0L

    /** 上一次主动清常驻通知的时间（tick 里兜底清理用，避免每轮都清）。 */
    private var lastHiddenAtMs = 0L

    /** 本次"通知被关"是否已经处理过（撤残留 + 重贴保住前台服务，只做一次）。 */
    private var disabledHandled = false

    /** 上一轮"系统里是否挂着常驻通知"（变化时记一行，用来抓"关了又出现"）。 */
    private var lastNotificationActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        ensureWatchChannel()
        log("监视：服务已启动")
        // 无论通知开关如何，都要建立前台身份：
        // 关掉通知只是让这条常驻通知不显示，服务的"前台服务"身份照旧（这是系统规则），
        // 而只要有前台身份，后台就不会被冻结、监视与提醒照常 —— 之前用普通后台服务当退路是错的。
        if (notificationDisabledNow()) {
            log("常驻通知：系统里已关闭本应用的通知，常驻通知不显示（服务仍以前台服务方式运行）")
        }
        try {
            startForeground(WATCH_NOTIFICATION_ID, buildWatchNotification())
            notificationShown = true
        } catch (t: Throwable) {
            log("常驻通知：贴出失败 $t")
        }
        // 订阅"应用是否在前台"：退到后台立刻贴通知、回到前台立刻撤掉（同进程，零延迟）
        scope.launch {
            appVisible.collect { visible ->
                if (visible) {
                    log("监视：程序回到前台")
                    hideNotification()
                } else {
                    log("监视：程序退到后台")
                    showNotification()
                }
            }
        }
        lastNotifiedSignature = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_NOTIFIED_SIG, "") ?: ""
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (t: Throwable) {
                    log("监视：异常 $t")
                }
                // 夸克在跑时快问（2 秒），没在跑时慢问（6 秒）：触发点只在"夸克从有到无"，闲时没必要 3 秒一跳
                delay(if (quarkAlive) FAST_INTERVAL_MS else SLOW_INTERVAL_MS)
            }
        }
    }

    /** 应用回到前台 / 退到后台由 MainActivity 直接改 appVisible（进程内状态），这里不再收动作。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次 startForegroundService() 都要求服务在 5 秒内调用 startForeground()，
        // 否则系统抛 ForegroundServiceDidNotStartInTimeException 直接杀进程；服务已在跑时
        // onCreate 不会再执行，所以这里无条件补一次（与"通知开没开、程序在不在前台"无关）。
        // 程序在前台时这里贴完会立刻被上面那个 appVisible 订阅撤掉，净效果就是 startForeground 一次。
        try {
            if (!notificationShown) {
                startForeground(WATCH_NOTIFICATION_ID, buildWatchNotification())
                notificationShown = true
            }
            handleDisabledNotificationState()
        } catch (t: Throwable) {
            log("常驻通知：贴出失败 $t")
        }
        return START_STICKY
    }

    private fun hideNotification() {
        // 不能因为 notificationShown=false 就直接 return：服务可能被系统重启过（内存标志是新实例的 false），
        // 而系统那边还挂着上一条通知 —— 那就成了空操作，表现为"我关了通道，返回程序它还在"。
        // 所以这里无条件清：先撤前台状态，再把这条通知 cancel 掉。
        try {
            if (notificationShown) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        } catch (t: Throwable) {
            log("常驻通知：撤下失败 $t")
        }
        val wasShown = notificationShown
        notificationShown = false
        if (wasShown) log("常驻通知：撤下")
        try {
            NotificationManagerCompat.from(this).cancel(WATCH_NOTIFICATION_ID)
        } catch (t: Throwable) {
            log("常驻通知：清理失败 $t")
        }
    }

    /**
     * 应用在前台时不该有任何常驻通知：主动清一次（连系统里的残留一起清）。
     * 每轮 tick 也会调一次，避免系统/小米把通知又贴回来。
     */
    private fun ensureNotificationHidden() {
        if (System.currentTimeMillis() - lastHiddenAtMs < 1000) return
        lastHiddenAtMs = System.currentTimeMillis()
        hideNotification()
    }

    private fun showNotification() {
        if (notificationShown) return
        try {
            // 即使应用级通知被关，也照样 startForeground：通知不会显示出来，
            // 但服务的"前台服务"身份必须建立，否则退到后台就会被系统冻结。
            startForeground(WATCH_NOTIFICATION_ID, buildWatchNotification())
            notificationShown = true
            log(
                if (notificationDisabledNow()) "常驻通知：已建立前台身份（应用级通知被关，通知不显示）"
                else "常驻通知：贴上"
            )
        } catch (t: Throwable) {
            log("常驻通知：贴出失败 $t")
        }
    }

    /**
     * 用户在系统里把「扫描监视」通知关掉后，**已经贴出去的那条不会自己消失**
     * （Android 不会因为通道被关就撤销已发出的通知，小米同理）。
     * 处理方式：撤掉再重贴一次 —— 旧那条被移除，新那条被系统按"已关闭"丢弃，
     * 前台服务照常活着。只在真的还挂着一条时才做，且有冷却，避免每轮都折腾。
     */
    private fun handleDisabledNotificationState() {
        // 用户把常驻通知关了（通道被关，或整个应用的通知被关）
        val disabled = notificationDisabledNow()
        if (!disabled) {
            disabledHandled = false
            return
        }
        if (disabledHandled) return          // 每次"关掉"只处理一次
        disabledHandled = true
        // 关掉之前已经贴出去的那条不会自己消失 → 撤掉它；**撤掉之后不再重贴**
        // （小米对"已关闭通道的新通知"照样会延迟显示出来，重贴 = 关了还会冒出来）
        if (notificationShown || notificationStillActive()) {
            hideNotification()
            log("常驻通知：系统里已关闭该通知 → 撤掉残留的那条，之后不再贴")
            // 撤掉的是"显示出来的那条"，不是"前台服务身份"：立刻补回来，否则退到后台会被冻结。
            try {
                startForeground(WATCH_NOTIFICATION_ID, buildWatchNotification())
                notificationShown = true
            } catch (t: Throwable) {
                log("常驻通知：贴出失败 $t")
            }
        }
    }

    /** 系统里此刻是否还挂着我们那条常驻通知（被通道关掉的不会算进来）。 */
    private fun notificationStillActive(): Boolean = try {
        getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.any { it.id == WATCH_NOTIFICATION_ID } == true
    } catch (t: Throwable) {
        false
    }

    /**
     * 应用级通知开关是否被关（系统「通知」总开关）。
     *
     * 注意：**这里刻意不看「扫描监视」通道的状态**。
     * 通道被关只意味着那条常驻通知不显示，服务的"前台服务"身份不受影响，
     * 后台监视与提醒照常 —— 实测（release 与关闭通道的 debug）都验证过这一点。
     * 曾经因为这里带上通道判断，导致通道一关就不再 startForeground、前台身份丢失、
     * 后台被系统冻结、提醒彻底不可用。
     */
    private fun notificationDisabledNow(): Boolean = !ScanNotifier.notificationsEnabled(this)

    /** 服务侧日志：跟界面共用一份（QseLog），导出的日志里能看到；logcat 用原来的 tag。 */
    private fun log(line: String) {
        QseLog.add(line, TAG)
    }

    override fun onDestroy() {
        running = false
        log("监视：服务已停止")
        scope.cancel()
        runner = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 监视

    private suspend fun tick() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_MONITOR, false)) {
            // 通知开关关着：什么都不做，直接退出（程序最小化后不留任何后台任务）
            stopSelf()
            return
        }
        val mode = prefs.getString(KEY_MODE, null) ?: "SHIZUKU"
        // 用户把常驻通知关掉后：撤掉残留那条，并只重贴一次保住前台服务（放在授权判断之前）
        handleDisabledNotificationState()
        // 应用在前台时不该有常驻通知；系统/小米有时会贴回来，这里兜底再清一次
        if (appVisible.value) ensureNotificationHidden()
        if (mode == "SHIZUKU" && (!ShizukuRuntime.binderAlive() || !ShizukuRuntime.isPermissionGranted())) return
        // Root 探测失败后的冷却：Magisk 的授权可以带有效期，过期后每次 su 都会重新拉起
        // 「超级用户请求」全屏界面 —— 后台每几秒弹一次会把人逼疯，所以失败就先歇 5 分钟。
        if (mode == "ROOT" && System.currentTimeMillis() < rootProbeCooldownUntil) return

        val r = runnerFor(mode)
        val out = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { r.run(ExportEngine.monitorProbeScript()) }
        }
        if (out == null) {
            log("监视：探测超时（提权执行未返回）")
            if (mode == "ROOT") rootProbeCooldownUntil = System.currentTimeMillis() + ROOT_PROBE_COOLDOWN_MS
            return
        }
        // 探测脚本最后一定会打印 QUARK 行。没有它 = 这次提权没跑成（su 被拒、Shizuku 掉线等），
        // 输出是空的 —— 若把这种空结果当成"缓存变空了"，等提权恢复后签名从空变有，
        // 就会被误判成"缓存有变化"而发一条假提醒。所以探测失败就整轮跳过，不动任何状态。
        if (out.lines.none { it.startsWith("QUARK\t") || it == "QUARK" }) {
            log("监视：探测未成功（提权被拒或未就绪）→ 本轮跳过，不改状态")
            if (mode == "ROOT") rootProbeCooldownUntil = System.currentTimeMillis() + ROOT_PROBE_COOLDOWN_MS
            return
        }

        val files = LinkedHashMap<String, String>()   // 路径 -> 大小
        var bytes = 0L
        out.lines.filter { it.startsWith("F\t") }.forEach { line ->
            val parts = line.split('\t')
            val path = parts.getOrNull(1) ?: return@forEach
            val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            files[path] = size.toString()
            bytes += size
        }
        val quarkIsAlive = (out.lines.firstOrNull { it.startsWith("QUARK\t") }
            ?.substringAfter('\t')?.trim()?.toIntOrNull() ?: 0) > 0
        quarkAlive = quarkIsAlive
        // 指纹只取「路径 + 大小」，不看修改时间：夸克有时只碰一下时间戳，那不算产生新扫描件（避免误报重复提醒）
        val signature = files.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value}" }
        val alerting = ScanNotifier.alertShowing(this)

        val changed = signature != lastNotifiedSignature
        // 只在"这一轮跟上一轮不一样"时记一行，别每 6 秒刷一次
        val notiActiveNow = notificationStillActive()
        // 缓存内容变了 → 版本号 +1，界面侧据此自动刷新列表
        if (signature != prevSignature && seeded) {
            cacheVersion.value = cacheVersion.value + 1
        }
        if (signature != prevSignature || quarkIsAlive != lastQuarkAlive || notiActiveNow != lastNotificationActive) {
            log(
                "监视：探测到 ${files.size} 个扫描件 / ${ExportEngine.formatSize(bytes)}，" +
                    "夸克${if (quarkIsAlive) "在运行" else "未运行"}，缓存${if (signature != prevSignature) "有变化" else "没变化"}" +
                    if (!notificationDisabledNow() && notiActiveNow != appVisible.value.not()) "（常驻通知状态异常）" else ""
            )
            lastNotificationActive = notiActiveNow
        }

        if (!seeded) {
            seeded = true
            lastQuarkAlive = quarkIsAlive
            prevSignature = signature
            return
        }

        // 触发一：夸克被移出最近任务（进程 有→无）
        val quarkRemoved = lastQuarkAlive && !quarkIsAlive
        // 触发二（兜底）：夸克本来就没在跑，但缓存内容变了 —— 防止"开了又很快关"被 6 秒间隔漏掉
        val quietChange = !quarkIsAlive && signature != prevSignature
        lastQuarkAlive = quarkIsAlive
        prevSignature = signature
        if (!quarkRemoved && !quietChange) return

        // 只有缓存确实变了的时候才提醒（对比的是"上次提醒时的内容"，且会跨进程重启保留）
        if (files.isEmpty()) {
            ScanNotifier.cancel(this)
            setNotifiedSignature("")
            return
        }
        // 触发原因要写准：一种是"夸克被移出最近任务"（主触发），另一种是兜底
        //（夸克本来就没在运行、只是缓存内容变了），两者的日志不能混为一谈。
        val why = if (quarkRemoved) "夸克被移出最近任务" else "夸克没在运行、但缓存有变化"
        if (signature == lastNotifiedSignature) {
            log("监视：$why，但扫描件没变化 → 不重复提醒")
            return
        }
        if (!ScanNotifier.notificationsEnabled(this)) {
            // 通知权限被关：提醒发不出去，也不补发（用户明确要求）
            log("监视：$why，但系统未允许本应用发通知 → 本次提醒丢弃（不补发）")
        } else {
            log("监视：$why，${files.size} 个扫描件 / ${ExportEngine.formatSize(bytes)} → 发提醒")
            ScanNotifier.refreshScanCache(this, files.size, ExportEngine.formatSize(bytes))
        }
        setNotifiedSignature(signature)
    }

    /** 记下"已提醒过的缓存内容"，并存盘（进程被杀重启后不会因为忘了而重复提醒同一批文件）。 */
    private fun setNotifiedSignature(signature: String) {
        lastNotifiedSignature = signature
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_NOTIFIED_SIG, signature)
            .apply()
    }

    private fun runnerFor(mode: String): ShellRunner {
        val existing = runner
        if (existing != null && ((mode == "ROOT" && existing is RootShellRunner) || (mode != "ROOT" && existing is ShizukuShellRunner))) {
            return existing
        }
        val created: ShellRunner = when (mode) {
            "ROOT" -> RootShellRunner(timeoutMs = 10_000L, packageName = packageName)
            else -> ShizukuShellRunner(packageName).also { it.ensureConnected(5) }
        }
        runner = created
        return created
    }

    // ------------------------------------------------------------ 常驻通知

    private fun ensureWatchChannel() {
        // 通知通道是 Android 8.0（API 26）才有的 API：低版本上 getNotificationChannel /
        // createNotificationChannel 会直接 NoSuchMethodError 崩掉，所以整段都要挡在版本判断后面。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // 老 ID 删掉（小米会把用户对某通道的"已屏蔽"状态记在它自己的库里，
        // 复用同一个 ID 会继承"被屏蔽"，前台服务就起不来了，所以换新 ID）
        LEGACY_WATCH_CHANNEL_IDS.forEach { legacy ->
            try {
                if (manager.getNotificationChannel(legacy) != null) manager.deleteNotificationChannel(legacy)
            } catch (t: Throwable) {
                Log.d(TAG, "delete legacy watch channel $legacy failed: $t")
            }
        }
        if (manager.getNotificationChannel(WATCH_CHANNEL_ID) == null) {
            // IMPORTANCE_MIN：最低调的一条（不出状态栏图标、静音）。不能用 IMPORTANCE_NONE ——
            // 实测那样前台服务会失效（最小化后 tick 全停）。想彻底不看见它，请走系统的「扫描监视」通道设置关掉。
            manager.createNotificationChannel(
                NotificationChannel(WATCH_CHANNEL_ID, "扫描监视", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "后台监视夸克扫描的前台服务通知；在系统通知设置里关掉它可隐藏，监视照常"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildWatchNotification() = NotificationCompat.Builder(this, WATCH_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_scan)
        .setContentTitle("正在监视夸克扫描")
        .setContentText("扫描完成后会提醒导出")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(false)
        .setAutoCancel(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setSilent(true)
        // Android 12+ 默认会把前台服务的通知"攒一下再显示"（最长约 10 秒），
        // 表现就是"我刚切出去/刚开开关，通知要等一会儿才冒出来"。
        // IMMEDIATE 要求系统立刻显示，不要延迟。
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    companion object {
        private const val TAG = "QSE-Monitor"
        const val WATCH_CHANNEL_ID = "scan_watch_v3"
        private val LEGACY_WATCH_CHANNEL_IDS = listOf("scan_watch", "scan_watch_v2")
        private const val WATCH_NOTIFICATION_ID = 4201
        private const val INTERVAL_MS = 3_000L
        private const val FAST_INTERVAL_MS = 3_000L   // 夸克在跑：快问
        private const val SLOW_INTERVAL_MS = 6_000L   // 夸克没在跑：慢问（省电）
        private const val PROBE_TIMEOUT_MS = 8_000L
        /** Root 探测失败后的冷却时长（Magisk 授权过期会反复弹授权界面）。 */
        private const val ROOT_PROBE_COOLDOWN_MS = 5 * 60_000L
        private const val PREFS_NAME = "quark_scan_extractor"
        private const val KEY_MODE = "privilege_mode"
        private const val KEY_MONITOR = "monitor_enabled"
        private const val KEY_NOTIFIED_SIG = "notified_signature"



        /**
         * 应用是否在前台。**用进程内共享状态，不再给服务发 Intent**：
         * Android 12+ 禁止应用在后台启动前台服务，MainActivity.onStop 里 startForegroundService
         * 会抛 ForegroundServiceStartNotAllowedException（被 catch 吞掉），
         * 表现就是「退到后台常驻通知不出现、过一会儿才冒出来」。
         * 服务本来就活着（同一个进程），它直接订阅这个状态：变 false 就贴通知、变 true 就撤掉。
         */
        val appVisible = MutableStateFlow(false)

        /**
         * 缓存的"版本号"：每轮探测发现签名变化就 +1。
         *
         * 界面侧订阅它（停在程序里时每 1.5 秒比一次），这样"监视服务发现了新扫描件"
         * 和"界面列表"就不会脱节 —— 以前从通知点进程序，列表还是旧的那份，
         * 必须手点一次「扫描」。这是同一进程内的共享状态，不需要任何广播或轮询文件。
         */
        val cacheVersion = MutableStateFlow(0L)

        /**
         * 服务是否已经在本进程里活着（进程内静态变量：进程被杀就自然归零）。
         *
         * 用来避免"服务还活着的时候又 startForegroundService 一次"：
         * 每次 startForegroundService() 都会给系统立一个「5 秒内必须调用 startForeground()」的期限，
         * 而服务活着时 onCreate 不会再执行，没人补这一刀 → 5 秒后系统直接抛
         * ForegroundServiceDidNotStartInTimeException 杀掉进程（实测：在主界面停留约 5 秒必崩）。
         */
        @Volatile
        private var running = false

        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, ScanWatchService::class.java)
            try {
                if (!enabled) {
                    running = false
                    context.stopService(intent)
                } else if (running) {
                    // 已经在跑：什么都不做。它自己订阅了应用可见性，会贴/撤通知。
                    // （保留 running 判断是为了修那个真崩溃：服务已活着时再调 startForegroundService，
                    //   会给系统立 5 秒死线，而 onCreate 不会再执行 → 5 秒后进程被杀。）
                    return
                } else {
                    // 一律走 startForegroundService：后台启动普通服务在 Android 8+ 是非法的
                    //（会抛异常、服务起不来），所以不能用它当"通知被关"时的退路。
                    // 通知关不关只影响"那一条常驻通知显不显示"，不影响服务是不是前台服务。
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (t: Throwable) {
                Log.d(TAG, "service toggle failed: $t")
            }
        }

        /** MainActivity.onStart：常驻通知立刻消失（服务没在跑就顺手拉起来，此时前台启动是合法的）。 */
        fun onAppVisible(context: Context) {
            appVisible.value = true
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MONITOR, false)) setEnabled(context, true)
        }

        /** MainActivity.onStop：常驻通知立刻出现。注意——这里不发 Intent（后台不允许启动前台服务）。 */
        fun onAppHidden(context: Context) {
            appVisible.value = false
        }
    }
}
