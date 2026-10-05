package com.blazkness.quarkscanextractor.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.blazkness.quarkscanextractor.util.ExitReason
import com.blazkness.quarkscanextractor.util.QseLog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blazkness.quarkscanextractor.engine.ExportEngine
import com.blazkness.quarkscanextractor.engine.StoragePaths
import com.blazkness.quarkscanextractor.shizuku.ShizukuRuntime
import com.blazkness.quarkscanextractor.shell.RootShellRunner
import com.blazkness.quarkscanextractor.shell.ShizukuShellRunner
import com.blazkness.quarkscanextractor.shell.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.blazkness.quarkscanextractor.notify.ScanNotifier
import com.blazkness.quarkscanextractor.service.ScanWatchService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PrivilegeMode(val title: String) {
    /** Shizuku 放第一位并作为默认：不挑设备，Root 需要额外手法。 */
    SHIZUKU("Shizuku"),
    ROOT("Root")
}

enum class StatusKind { IDLE, OK, WARN, ERROR }

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 上次使用的授权方式（持久化，重启后自动选中）。 */
    var mode by mutableStateOf(loadMode())
        private set

    /** 初始状态提示：Root 模式下进程序不会自动跑 su（Magisk 会因此弹授权界面），所以不能说"正在检测"。 */
    var status by mutableStateOf(
        if (mode == PrivilegeMode.SHIZUKU) "正在自动检测授权…" else "尚未检测，点「授权 / 检测」检查 Root 权限"
    )
        private set

    var statusKind by mutableStateOf(StatusKind.IDLE)
        private set

    var authorized by mutableStateOf(false)
        private set

    var busy by mutableStateOf(false)
        private set

    var cleanTemp by mutableStateOf(prefs.getBoolean(KEY_CLEAN, true))
        private set

    /** 扫描完成后发通知提醒（持久化，默认关：关闭时程序最小化后不跑任何后台任务）。 */
    var monitorEnabled by mutableStateOf(prefs.getBoolean(KEY_MONITOR, false))
        private set

    /** 系统是否允许本应用发通知（被拒/被系统关掉时返回 false）。 */
    var notificationsAllowed by mutableStateOf(true)
        private set

    /** 「横幅与声音」这一步用户点过没（系统不让 App 读通道的悬浮通知状态，只能记"点过了"）。 */
    var bannerStepDone by mutableStateOf(prefs.getBoolean(KEY_BANNER_STEP, false))

    /**
     * 我们已经把 Shizuku 授权弹窗发出去、正在等用户点。
     * 弹窗本身**没有超时**（实测等 30 秒仍在），所以等待期间状态行不能被 1.5 秒轮询改回
     * "尚未授权，点「授权 / 检测」申请" —— 那会让人以为申请丢了。
     */
    private var awaitingShizukuDialog = false

    /** 已经处理过的「监视服务缓存版本号」，用来判断是否需要自动刷新列表。 */
    private var seenCacheVersion = 0L

    /** 「上次进程退出原因」只在本次进程的第一次前台记录一次。 */
    private var exitReasonChecked = true

    /** 上一次授权检测的结论（用来只在结论变化时记日志，避免每次回前台都刷一行）。 */
    private var lastAuthVerdict: String = ""

    /** 本应用自己记的"Shizuku 申请被拒过一次"（Shizuku 自身不记拒绝，实测 13.5） */
    var shizukuDenied by mutableStateOf(prefs.getBoolean(KEY_SHIZUKU_DENIED, false))
        private set

    fun markBannerStepDone() {
        bannerStepDone = true
        prefs.edit().putBoolean(KEY_BANNER_STEP, true).apply()
    }

    fun refreshNotificationState() {
        val allowed = try {
            NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
        } catch (t: Throwable) {
            true
        }
        if (allowed != notificationsAllowed) {
            logLine(if (allowed) "通知权限：可用" else "通知权限：被关闭（通知不会显示，提醒也发不出去）")
        }
        notificationsAllowed = allowed
    }

    /** 是否不受系统省电限制（小米会冻结受限的后台应用，监视会中断）。 */
    var batteryUnrestricted by mutableStateOf(readBatteryUnrestricted())
        private set

    private fun readBatteryUnrestricted(): Boolean = try {
        val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) ?: true
    } catch (t: Throwable) {
        true
    }

    fun refreshPowerState() {
        val now = readBatteryUnrestricted()
        if (now != batteryUnrestricted) {
            logLine(if (now) "省电策略：不受限制" else "省电策略：受限制（后台可能被系统冻结，监视会中断）")
        }
        batteryUnrestricted = now
    }

    /** 停在程序里时的静默复核：还没授权就隔几秒重试一次（用户在 Shizuku 管理器里点了允许，这边自己就跳成已完成）。
     *  走独立的 in-flight 标志，不动 busy —— 否则扫描/导出按钮会跟着闪。 */
    fun refreshAuthorizationQuietly() {
        // Root 模式不自动重试：每次都要起一个 su 进程，KernelSU 会刷一堆授权记录
        if (mode != PrivilegeMode.SHIZUKU) return
        if (authorized || busy || checkInFlight || authRecheckInFlight) return
        val now = System.currentTimeMillis()
        if (now - lastAuthRetryMs < 3000) return
        lastAuthRetryMs = now
        authRecheckInFlight = true
        viewModelScope.launch {
            try {
                checkShizuku(requestPermission = false, label = "自动", quiet = true)
            } catch (t: Throwable) {
                // 静默：不打断用户
            } finally {
                authRecheckInFlight = false
            }
            if (authorized) {
                cleanupPreview(force = true)
                syncMonitorService()
                if (scanResult == null) rescanQuietly(force = true)
            }
        }
    }

    fun refreshSystemStates() {
        refreshNotificationState()
        refreshPowerState()
        ScanNotifier.ensureChannel(getApplication())
        refreshAfterWatcherIfNeeded()
    }

    /**
     * 监视服务发现缓存变了（[ScanWatchService.cacheVersion] 变了）→ 自动把列表刷新一遍。
     *
     * 这一条修的是：夸克里扫了新文件、退出并杀后台，通知来了，但点进程序看到的还是旧列表
     * （以前只有"回到前台时复核授权"顺带扫一次，那条路径在某些进入方式下不生效）。
     * 现在改成由服务主动报"有新内容"，界面每 1.5 秒比一次版本号，比到不同就重扫。
     */
    private fun refreshAfterWatcherIfNeeded() {
        val version = ScanWatchService.cacheVersion.value
        if (version == seenCacheVersion) return
        seenCacheVersion = version
        if (!authorized || busy || checkInFlight) return
        logLine("扫描：监视服务报有新缓存，自动刷新列表")
        viewModelScope.launch {
            try {
                rescanQuietly(force = true)
            } catch (t: Throwable) {
                logLine("扫描：自动刷新失败 ${t.message}")
            }
        }
    }

    var dest by mutableStateOf(loadDir())

    var scanResult by mutableStateOf<ExportEngine.ScanResult?>(null)
        private set

    /** 已勾选的文件路径（导出只导这些）。 */
    val selected = mutableStateListOf<String>()

    /** 目标目录里已经存在（即已导出过）的文件名。 */
    val exportedNames = mutableStateListOf<String>()

    /** 导出前发现已导出文件时的确认弹窗数据。 */
    var duplicatePrompt by mutableStateOf<DuplicatePrompt?>(null)
        private set

    data class DuplicatePrompt(val names: List<String>)

    /** 日志：界面侧和服务侧共用一份（见 QseLog），导出的日志才是完整的。 */
    val log: SnapshotStateList<String> get() = QseLog.lines

    private var rootRunner: RootShellRunner? = null
    private var shizukuRunner: ShizukuShellRunner? = null

    /** 授权检测的互斥标记（不驱动进度条，避免每次切回前台都闪一下）。 */
    private var checkInFlight = false

    /** 授权静默重试专用（不占用 checkInFlight / busy，避免扫描导出按钮跟着闪）。 */
    private var authRecheckInFlight = false
    private var lastAuthRetryMs = 0L

    /** 是否还没做过「首次检测」（首次检测要等窗口可见，才能在 Shizuku 上弹授权框）。 */
    private var firstForeground = true

    /** 有预览临时文件待清理。 */
    private var previewPending = false

    init {
        ShizukuRuntime.onPermissionResult = { granted ->
            awaitingShizukuDialog = false
            if (granted) {
                prefs.edit().putBoolean(KEY_SHIZUKU_DENIED, false).apply()
                shizukuDenied = false
                lastAuthVerdict = ""
                logLine("授权：Shizuku 授权弹窗结果 = 允许，正在复核")
                launchCheck(auto = true, requestPermission = false)
            } else {
                authorized = false
                // Shizuku 只记"允许"、不记"拒绝"（实测 13.5）：拒绝了再申请还会再弹，
                // 所以由本应用自己记住"拒过一次"，只用于提示，不影响用户再点一次申请。
                prefs.edit().putBoolean(KEY_SHIZUKU_DENIED, true).apply()
                shizukuDenied = true
                lastAuthVerdict = "Shizuku:no-grant-denied"
                setStatus(StatusKind.ERROR, "Shizuku 授权被拒绝。可再点「授权 / 检测」申请一次。")
                logLine("授权：Shizuku 授权弹窗结果 = 拒绝（Shizuku 不记拒绝，下次点按钮仍会弹窗）")
            }
        }
        ShizukuRuntime.onBinderDead = {
            awaitingShizukuDialog = false
            authorized = false
            setStatus(StatusKind.ERROR, "Shizuku 已停止运行，请重新启动 Shizuku。")
            logLine("授权：Shizuku 服务已断开")
        }
        // 首次检测放在 onForeground() 里做：那时窗口才真正可见，Shizuku 的授权弹窗才能稳定弹出
    }

    /**
     * 从后台回到前台（例如刚去 KernelSU / Shizuku 里授权或取消授权）：
     * 已就绪就复核授权是否还有效，未就绪就重新检测一次。
     */
    fun onForeground() {
        logLastExitReasonIfNeeded(getApplication())
        refreshSystemStates()
        ScanNotifier.cancel(getApplication())   // 人进来看列表了，提醒通知就不该再挂着
        if (busy || checkInFlight) return
        if (firstForeground) {
            firstForeground = false
            // 窗口已可见：只做静默检测。
            // - Shizuku：查询授权状态是纯 API 调用，没有副作用。
            // - Root：**不自动检测**。Magisk 下执行 su 会拉起"超级用户请求"全屏界面
            //   （用户没点「去授权」就弹框，卸载重装后表现为"一闪而过的授权窗"）。
            if (mode == PrivilegeMode.SHIZUKU) launchCheck(auto = true, requestPermission = false)
            return
        }
        if (previewPending) cleanupPreview()
        if (!authorized) {
            // 静默重检：不刷日志头，只更新状态。Root 模式同样跳过（见上：su 会触发 Magisk 授权界面）
            if (mode == PrivilegeMode.SHIZUKU) {
                launchCheck(auto = true, requestPermission = false, quiet = true)
            }
            return
        }
        checkInFlight = true
        viewModelScope.launch {
            try {
                if (!stillAuthorized()) {
                    authorized = false
                    scanResult = null
                    logLine("授权：已失效（被取消，或 Shizuku 服务已停止）")
                    setStatus(StatusKind.WARN, "授权已被取消：点「授权 / 检测」重新申请。")
                } else {
                    // 授权没问题就顺带把扫描结果刷新一下
                    rescanQuietly()
                }
            } catch (t: Throwable) {
                logLine("授权：复核失败 ${t.message}")
            } finally {
                checkInFlight = false
            }
        }
    }

    /**
     * 自动刷新扫描结果（回到前台时执行，和授权检测同一时机）。
     * 保留原有勾选 —— 原本没勾的仍然不勾，新出现的默认勾上；只有新旧列表有差异才写日志。
     */
    private suspend fun rescanQuietly(force: Boolean = false) {
        val previous = scanResult
        if (previous == null && !force) return   // 之前没扫描过就不自动扫，保持界面干净
        val previousPaths = previous?.dirs?.flatMap { dir -> dir.files }?.map { it.path } ?: emptyList()
        val unchecked = previousPaths.filter { it !in selected }
        val runner = runnerFor(getApplication())
        val result = withContext(Dispatchers.IO) { ExportEngine.scan(runner, ::logLine) }
        if (result.error != null) return
        val newPaths = result.dirs.flatMap { dir -> dir.files }.map { it.path }
        selected.clear()
        newPaths.forEach { path -> if (path !in unchecked) selected.add(path) }
        scanResult = result
        if (previous != null) {
            val added = newPaths.count { it !in previousPaths }
            val removed = previousPaths.count { it !in newPaths }
            if (added > 0 || removed > 0) {
                logLine("扫描：刷新列表 新增 $added 个 / 消失 $removed 个（当前共 ${newPaths.size} 个）")
            }
        }
        refreshExported(getApplication())
    }

    // ------------------------------------------------------------ 扫描完成提醒

    fun setMonitorEnabled(context: Context, value: Boolean) {
        monitorEnabled = value
        prefs.edit().putBoolean(KEY_MONITOR, value).apply()
        ScanWatchService.setEnabled(context, value)
        if (!value) {
            // 关掉就把提醒撤掉；常驻通知会随服务一起停掉
            ScanNotifier.cancel(context)
        }
        refreshSystemStates()
        logLine(if (value) "已开启「扫描完成后通知我」" else "已关闭「扫描完成后通知我」")
    }

    /** 授权就绪后确保后台监视服务在跑（服务自己读偏好里的授权方式）。 */
    private fun syncMonitorService() {
        if (monitorEnabled) ScanWatchService.setEnabled(getApplication(), true)
    }

    /** 复核当前授权是否仍然有效（用于从后台回来时）。 */
    private suspend fun stillAuthorized(): Boolean = withContext(Dispatchers.IO) {
        when (mode) {
            PrivilegeMode.ROOT -> {
                val pinned = rootRunner?.resolvedSu
                if (pinned == null) {
                    false
                } else {
                    val result = RootShellRunner(
                        timeoutMs = VERIFY_TIMEOUT_MS,
                        packageName = getApplication<Application>().packageName,
                        forcedSu = pinned
                    ).run("id")
                    if (result.output.contains("uid=0")) {
                        true
                    } else {
                        logLine("授权：Root 复核失败 ${result.output.trim().take(160)}")
                        rootRunner = null
                        false
                    }
                }
            }

            PrivilegeMode.SHIZUKU -> {
                val runner = shizukuRunner
                if (runner == null) {
                    false
                } else if (!ShizukuRuntime.binderAlive() || !ShizukuRuntime.isPermissionGranted()) {
                    runner.close()
                    shizukuRunner = null
                    false
                } else {
                    val ok = try {
                        runner.ensureConnected(timeoutSeconds = 5)
                        runner.run("id").output.contains("uid=")
                    } catch (t: Throwable) {
                        false
                    }
                    if (!ok) {
                        runner.close()
                        shizukuRunner = null
                    }
                    ok
                }
            }
        }
    }

    fun switchMode(newMode: PrivilegeMode) {
        if (newMode == mode) return
        mode = newMode
        prefs.edit().putString(KEY_MODE, newMode.name).apply()
        logLine("设置：授权方式切换为 ${newMode.title}")
        // 立刻给个状态，避免切换到未授权的方式时界面"卡着不动"（Root 检测最长等 15 秒）
        setStatus(StatusKind.IDLE, "正在检测 ${newMode.title} 授权…")
        // 注意：这里**不再**清空 authorized 和 scanResult。
        // 清了之后：授权状态翻假 → 清单卡片整块冒出来、扫描结果整块消失，屏幕就是一闪；
        // 而且闪烁期间用户还能点到别的开关。文件列表本来就跟授权方式无关，留着即可，
        // 真正的授权状态由下面这次检测的结果决定。
        launchCheck(auto = true, requestPermission = true, labelOverride = "手动（切换授权方式）")
    }

    /** 「导出后清空临时文件」开关（持久化）。 */
    fun setCleanTempEnabled(value: Boolean) {
        cleanTemp = value
        prefs.edit().putBoolean(KEY_CLEAN, value).apply()
    }

    /** 重置所有设置：授权方式、输出目录、清空开关都回到默认值。 */
    fun resetSettings(context: Context) {
        prefs.edit().clear().apply()
        mode = PrivilegeMode.SHIZUKU
        dest = ExportEngine.DEFAULT_DEST
        cleanTemp = true
        // 监视开关也是设置的一部分：内存里的开关、实际的后台服务都要一起回到"关"
        monitorEnabled = false
        bannerStepDone = false
        shizukuDenied = false
        lastAuthVerdict = ""
        authorized = false
        scanResult = null
        selected.clear()
        exportedNames.clear()
        duplicatePrompt = null
        shizukuRunner?.close()
        shizukuRunner = null
        rootRunner = null
        logLine("设置：已重置（授权方式 Shizuku、输出目录 ${ExportEngine.DEFAULT_DEST}、导出后清空临时文件 开、扫描完成后通知我 关）")
        ScanWatchService.setEnabled(context, false)
        setStatus(StatusKind.IDLE, "设置已重置，请点「授权 / 检测」。")
    }

    /** 手动按钮：完整超时、必要时发起授权申请。 */
    fun check(context: Context) {
        // 手动检测前把系统状态记一笔，出问题时看日志就知道当时是什么状态
        logLine(
            "开始检测授权（${mode.title}）：通知=${if (notificationsAllowed) "可用" else "被关闭"}、" +
                "省电=${if (batteryUnrestricted) "不受限制" else "受限制"}"
        )
        launchCheck(auto = false, requestPermission = true)
    }

    /** 系统目录选择器的返回值：Uri → 真实路径 → 存盘，并用提权身份验一次可写性。 */
    fun setExportDir(context: Context, uri: Uri) {
        val path = StoragePaths.physicalPath(uri)
        if (path == null) {
            setStatus(StatusKind.ERROR, "只能选择本机存储（内部存储或 SD 卡）里的文件夹。")
            logLine("设置：目录被拒绝，不支持的位置（${uri.authority}）")
            return
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) {
            logLine("设置：留存目录权限失败（不影响导出）${t.message}")
        }
        dest = path
        prefs.edit().putString(KEY_DIR, path).apply()
        logLine("设置：输出目录改为 $path")
        verifyExportDir(context, path)
        if (scanResult != null) refreshExported(context)
    }

    /** 用提权身份试建目录 + 写探针文件，立刻告诉用户这个目录到底能不能用。 */
    private fun verifyExportDir(context: Context, path: String) {
        if (!authorized || busy) return
        viewModelScope.launch {
            try {
                val runner = runnerFor(context)
                val result = withContext(Dispatchers.IO) { runner.run(ExportEngine.probeDirScript(path)) }
                val parts = result.lines.firstOrNull { it.startsWith("PROBE\t") }?.split('\t')
                if (parts?.getOrNull(1) == "ok") {
                    logLine("设置：目录可写 $path")
                } else {
                    val reason = parts?.getOrNull(2).orEmpty().ifBlank { "未知原因" }
                    setStatus(StatusKind.WARN, "导出目录不可用（$reason），换一个文件夹试试。")
                    logLine("设置：目录不可写 $path（$reason）")
                }
            } catch (t: Throwable) {
                logLine("设置：目录验证失败 ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------ 授权 / 检测

    private fun launchCheck(
        auto: Boolean,
        requestPermission: Boolean,
        quiet: Boolean = false,
        labelOverride: String? = null
    ) {
        if (busy || checkInFlight) return
        checkInFlight = true
        busy = true
        val label = labelOverride ?: if (auto) "自动" else "手动"
        // 记下这次检测针对哪种授权方式：检测期间用户又切了方式的话，这次结果不能算数
        val startedMode = mode
        viewModelScope.launch {
            try {
                when (startedMode) {
                    PrivilegeMode.ROOT -> checkRoot(auto, label, quiet)
                    PrivilegeMode.SHIZUKU -> checkShizuku(requestPermission, label, quiet)
                }
            } catch (t: Throwable) {
                if (mode == startedMode) {
                    authorized = false
                    setStatus(StatusKind.ERROR, "检测失败：${t.message}")
                }
                logLine("授权：检测异常 $t")
            } finally {
                busy = false
                checkInFlight = false
            }
            // 检测途中用户改了授权方式：这次的结果已经过时，重新跑一轮（别在这里改状态）
            if (mode != startedMode) {
                logLine("授权：检测期间授权方式已切换，本次结果作废，重新检测")
                launchCheck(auto = true, requestPermission = true, labelOverride = "手动（切换授权方式）")
                return@launch
            }
            // 授权就绪后顺手清掉上次可能残留的预览临时文件
            if (authorized) {
                cleanupPreview(force = true)
                syncMonitorService()
                if (scanResult == null) rescanQuietly(force = true)
            }
        }
    }

    private suspend fun checkRoot(auto: Boolean, label: String, quiet: Boolean) = withContext(Dispatchers.IO) {
        // 用户主动触发的检测（点按钮、切换授权方式）一律用长超时：
        // Magisk 的请求超时可配到 60 秒，短超时会在用户还没点完时先放弃。
        // 只有后台自动路径（回前台复核、监视探测）才用短超时。
        val timeout = MANUAL_TIMEOUT_MS
        val runner = RootShellRunner(timeoutMs = timeout, packageName = getApplication<Application>().packageName)
        val result = runner.run("id")
        if (result.output.contains("uid=0")) {
            rootRunner?.close()
            rootRunner = runner
            authorized = true
            setStatus(StatusKind.OK, "Root 可用（su=${runner.resolvedSu}，uid=0）")
            authLog(label, "Root:ok", "授权：${label}检测（Root）→ 成功（su=${runner.resolvedSu}，${Regex("uid=\\S+").find(result.output)?.value ?: "uid=0"}）", quiet)
        } else {
            authorized = false
            val detail = result.output.trim().ifEmpty { "无输出，exit=${result.exitCode}" }
            val timedOut = detail.contains("响应超时")
            val denied = !timedOut && (detail.contains("Permission denied") ||
                detail.contains("error=13") ||
                result.exitCode == RootShellRunner.START_FAILED)
            if (timedOut) {
                setStatus(
                    StatusKind.WARN,
                    "等待 Root 管理器响应超时（${MANUAL_TIMEOUT_MS / 1000} 秒）。" +
                        "如果管理器弹出了授权界面，请先在那里点允许，再点「重新检测」。"
                )
            } else if (denied) {
                setStatus(
                    StatusKind.WARN,
                    "尚未获得 Root 授权：请到 Root 管理器（KernelSU / Magisk）里允许本应用，" +
                        "再切回本应用点「重新检测」。（若是弹窗超时后自动拒绝，重新点一次即可）"
                )
            } else if (auto) {
                setStatus(StatusKind.WARN, "尚未获得 Root：${detail.lineSequence().first().take(120)}，点「授权 / 检测」再试。")
            } else {
                setStatus(StatusKind.ERROR, "Root 不可用：${detail.lineSequence().first().take(140)}")
            }
            authLog(
                label,
                if (denied) "Root:denied" else "Root:fail",
                "授权：${label}检测（Root）→ ${if (denied) "失败：未获得授权（Root 管理器没有放行本次调用：被拒绝，或弹窗超时后自动拒绝；需在 KernelSU / Magisk 里允许本应用）" else "失败：" + detail.lineSequence().first().take(140)}",
                quiet
            )
        }
    }

    private suspend fun checkShizuku(requestPermission: Boolean, label: String, quiet: Boolean) = withContext(Dispatchers.IO) {
        if (!ShizukuRuntime.binderAlive()) {
            authorized = false
            // 区分"没装"和"装了没启动"：以前一律说"请先启动 Shizuku"，
            // 没装 Shizuku 的手机上这句话等于让用户去启动一个不存在的东西。
            if (shizukuInstalled()) {
                setStatus(StatusKind.ERROR, "Shizuku 未运行，请先启动 Shizuku。")
                authLog(label, "Shizuku:no-binder", "授权：${label}检测（Shizuku）→ 失败：Shizuku 未运行", quiet)
            } else {
                setStatus(
                    StatusKind.ERROR,
                    "本机未安装 Shizuku。用 Shizuku 请先安装并启动它；也可以在上面切换到「Root」。"
                )
                authLog(label, "Shizuku:not-installed", "授权：${label}检测（Shizuku）→ 失败：未安装 Shizuku（可切换到 Root）", quiet)
            }
            return@withContext
        }
        if (!ShizukuRuntime.isSupported()) {
            authorized = false
            setStatus(StatusKind.ERROR, "Shizuku 版本过低（v${ShizukuRuntime.version()}），需要 v12 及以上。")
            authLog(label, "Shizuku:old", "授权：${label}检测（Shizuku）→ 失败：Shizuku 版本过低（v${ShizukuRuntime.version()}，需要 v12+）", quiet)
            return@withContext
        }
        if (!ShizukuRuntime.isPermissionGranted()) {
            authorized = false
            if (awaitingShizukuDialog) {
                // 弹窗还开着：保持"等待结果"，别被轮询改写成"尚未授权"
                setStatus(StatusKind.WARN, "正在等待 Shizuku 授权弹窗的结果（弹窗不会超时，点允许或拒绝即可）。")
                authLog(label, "Shizuku:awaiting-dialog", "授权：${label}检测（Shizuku）→ 仍未授权，授权弹窗等待答复中", quiet)
                return@withContext
            }
            if (requestPermission) {
                // 只有用户点按钮才会走到这里。Shizuku 不会记住"拒绝"，所以拒绝后再点＝再弹一次，
                // 这是用户主动动作，可以接受；绝不能自动重试，否则会反复弹窗。
                setStatus(StatusKind.WARN, "正在等待 Shizuku 授权弹窗的结果（弹窗不会超时，点允许或拒绝即可）。")
                val asked = ShizukuRuntime.requestPermission()
                awaitingShizukuDialog = asked
                authLog(
                    label,
                    if (asked) "Shizuku:no-grant-requested" else "Shizuku:request-failed",
                    if (asked) "授权：${label}检测（Shizuku）→ 未授权，已发起授权申请（等待弹窗结果）"
                    else "授权：${label}检测（Shizuku）→ 未授权，且授权申请没能发出（Shizuku 刚好不可用）",
                    quiet
                )
            } else if (shizukuDenied) {
                setStatus(StatusKind.WARN, "上次申请被拒绝。点「授权 / 检测」可再申请一次，或到 Shizuku 应用里允许本应用。")
                authLog(label, "Shizuku:no-grant-denied", "授权：${label}检测（Shizuku）→ 未授权（上次申请被拒绝）", quiet)
            } else {
                setStatus(StatusKind.WARN, "Shizuku 在运行，但本应用尚未授权。点「授权 / 检测」申请。")
                authLog(label, "Shizuku:no-grant", "授权：${label}检测（Shizuku）→ 未授权（尚未申请）", quiet)
            }
            return@withContext
        }
        val runner = shizukuRunner
            ?: ShizukuShellRunner(getApplication<Application>().packageName).also { shizukuRunner = it }
        try {
            runner.ensureConnected(MANUAL_TIMEOUT_MS / 1000)
        } catch (t: Throwable) {
            authorized = false
            setStatus(StatusKind.ERROR, "无法连接 Shizuku 用户服务：${t.message}")
            authLog(label, "Shizuku:conn-fail", "授权：${label}检测（Shizuku）→ 失败：用户服务连接失败（${t.message}）", quiet)
            return@withContext
        }
        val result = try {
            withTimeoutOrNull(MANUAL_TIMEOUT_MS) { runner.run("id") }
        } catch (t: Throwable) {
            authorized = false
            setStatus(StatusKind.ERROR, "Shizuku 执行失败：${t.message}")
            authLog(label, "Shizuku:run-fail", "授权：${label}检测（Shizuku）→ 失败：执行 id 失败（${t.message}）", quiet)
            return@withContext
        }
        if (result == null) {
            // 和 Root 侧一样的处理：到点就掐掉这次调用并复原后端。
            // Root 掐的是 su 子进程；Shizuku 掐的是我们自己的用户服务进程（销毁后自动重建）。
            authorized = false
            val secs = MANUAL_TIMEOUT_MS / 1000
            setStatus(
                StatusKind.ERROR,
                "等待 Shizuku 用户服务响应超时（$secs 秒）。已销毁并准备重建该服务，请点「授权 / 检测」重试。"
            )
            authLog(
                label, "Shizuku:timeout",
                "授权：${label}检测（Shizuku）→ 失败：等待 Shizuku 用户服务响应超时（$secs 秒），已销毁该服务（下次检测自动重建）",
                quiet
            )
            shizukuRunner?.destroyAndReset()
            shizukuRunner = null
            return@withContext
        }
        val probe = result            // 上面已排除 null，这里显式取非空值
        authorized = true
        awaitingShizukuDialog = false
        val backend = if (ShizukuRuntime.uid() == 0) "root" else "shell(adb)"
        setStatus(StatusKind.OK, "Shizuku 可用（后端身份：$backend）")
        authLog(
            label, "Shizuku:ok:$backend",
            "授权：${label}检测（Shizuku）→ 成功（${Regex("uid=\\S+").find(probe.output)?.value ?: "已连接"}，后端=$backend）",
            quiet
        )
    }

    /** 本机是否装了 Shizuku 应用（moe.shizuku.privileged.api）。 */
    private fun shizukuInstalled(): Boolean = try {
        getApplication<Application>().packageManager
            .getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * 启动时读一次系统的「上次进程退出原因」（Android 11+ 的 ApplicationExitInfo）。
     *
     * 为什么要这个：撤销 Shizuku 授权时，**Shizuku 会主动把本应用强制停止**
     * （实测日志：`Force stopping … from process:<shizuku_server 的 pid>`，退出原因
     * `USER REQUESTED / FORCE STOP`，没有崩溃栈、没有 am_crash 记录）。
     * 用户看到的是"程序崩溃了"，而进程已经死了、当时什么都写不下来。
     * 所以改成下次进程序时回头把这件事写进日志 —— 让"被停止"和"真崩溃"能一眼分开。
     */
    private fun logLastExitReasonIfNeeded(context: Context) {
        if (!exitReasonChecked) return
        exitReasonChecked = false
        // API 30+ 的 API 封装在 util/ExitReason 里，低版本那次调用直接返回 null
        val info = ExitReason.last(context) ?: return
        // 太久远的（上次运行是几小时前）跟这次进程序无关，不提
        if (System.currentTimeMillis() - info.timestamp > 6 * 60 * 60 * 1000L) return
        val at = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(info.timestamp))
        when (info.reason) {
            ExitReason.REASON_USER_REQUESTED ->
                logLine("启动：上次进程于 $at 被强制停止（不是崩溃；常见原因：撤销 Shizuku 授权后由 Shizuku 停止本应用）")
            ExitReason.REASON_CRASH, ExitReason.REASON_CRASH_NATIVE ->
                logLine("启动：上次进程于 $at 崩溃（原因：${info.description ?: "见 logcat"}）")
            ExitReason.REASON_ANR ->
                logLine("启动：上次进程于 $at 无响应被系统结束")
            ExitReason.REASON_LOW_MEMORY ->
                logLine("启动：上次进程于 $at 因内存不足被系统结束")
            else -> Unit
        }
    }

    /**
     * 授权日志：一次检测 = 一行，写明「谁触发的（自动 / 手动）→ 结果」。
     * 自动复核每次回到前台都会跑，所以只有结果发生变化（或用户手动点）时才记，
     * 免得日志被同一行刷满；状态一变就一定会有一条。
     */
    private fun authLog(label: String, verdict: String, line: String, quiet: Boolean) {
        val changed = verdict != lastAuthVerdict
        lastAuthVerdict = verdict
        if (!quiet || changed) logLine(line)
    }

    // ------------------------------------------------------------ 扫描 / 导出

    /** 勾选 / 取消勾选一个文件。 */
    fun toggleSelected(path: String) {
        if (!selected.remove(path)) selected.add(path)
    }

    fun selectAll() {
        selected.clear()
        scanResult?.dirs?.forEach { dir -> dir.files.forEach { selected.add(it.path) } }
    }

    fun clearSelection() = selected.clear()

    fun scan(context: Context) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            logLine("扫描：开始读取夸克临时目录")
            try {
                val runner = runnerFor(context)
                val result = withContext(Dispatchers.IO) { ExportEngine.scan(runner, ::logLine) }
                scanResult = result
                // 新一批扫描默认全选
                selected.clear()
                result.dirs.forEach { dir -> dir.files.forEach { selected.add(it.path) } }
                result.error?.let {
                    setStatus(StatusKind.ERROR, it)
                    logLine("扫描：失败 $it")
                }
                if (result.error == null) refreshExported(context)
            } catch (t: Throwable) {
                setStatus(StatusKind.ERROR, "扫描失败：${t.message}")
                logLine("扫描：异常 $t")
            } finally {
                busy = false
            }
        }
    }

    fun export(context: Context) {
        if (busy) return
        val scanned = scanResult
        if (scanned == null || scanned.fileCount == 0) {
            setStatus(StatusKind.ERROR, "没有可导出的文件，请先扫描。")
            return
        }
        val chosen = onlySelected(scanned)
        if (chosen.fileCount == 0) {
            setStatus(StatusKind.WARN, "一个文件都没勾选，先勾选要导出的文件。")
            return
        }
        // 勾选的文件里有已经导出过的 → 先弹窗让用户决定
        val duplicates = chosen.dirs.flatMap { dir -> dir.files }
            .map { ExportEngine.targetNameOf(it) }
            .filter { exportedNames.contains(it) }
        if (duplicates.isNotEmpty()) {
            duplicatePrompt = DuplicatePrompt(duplicates)
            return
        }
        startExport(context, chosen, scanned)
    }

    /** 弹窗选择：忽略照常导出，或取消勾选已导出的再导出。 */
    fun resolveDuplicatePrompt(context: Context, deselectDuplicates: Boolean) {
        val prompt = duplicatePrompt ?: return
        duplicatePrompt = null
        val scanned = scanResult ?: return
        if (deselectDuplicates) {
            val dupPaths = scanned.dirs.flatMap { dir -> dir.files }
                .filter { ExportEngine.targetNameOf(it) in prompt.names }
                .map { it.path }
            dupPaths.forEach { selected.remove(it) }
            logLine("导出：已取消勾选 ${dupPaths.size} 个已导出过的文件")
        } else {
            logLine("导出：无视提示照常导出（其中 ${prompt.names.size} 个已存在）")
        }
        val chosen = onlySelected(scanned)
        if (chosen.fileCount == 0) {
            setStatus(StatusKind.WARN, "取消勾选后没有待导出的文件了。")
            return
        }
        startExport(context, chosen, scanned)
    }

    fun dismissDuplicatePrompt() {
        duplicatePrompt = null
    }

    private fun startExport(
        context: Context,
        chosen: ExportEngine.ScanResult,
        scanned: ExportEngine.ScanResult
    ) {
        viewModelScope.launch {
            busy = true
            logLine("导出：开始（${chosen.fileCount}/${scanned.fileCount} 个文件，导出后清空临时文件：${if (cleanTemp) "开" else "关"}）")
            try {
                val runner = runnerFor(context)
                val report = withContext(Dispatchers.IO) {
                    ExportEngine.export(runner, chosen, dest, cleanTemp, ::logLine)
                }
                if (report.copied.isNotEmpty()) {
                    refreshMediaStore(context, runner, report.copied.map { it.path })
                }
                val failCount = report.failures.size
                setStatus(
                    if (failCount == 0) StatusKind.OK else StatusKind.WARN,
                    "导出完成：成功 ${report.copied.size} 个" + if (failCount == 0) "" else "，失败 $failCount 个"
                )
                logLine("导出：完成，产物在 $dest")
                if (cleanTemp && chosen.fileCount < scanned.fileCount) {
                    logLine("导出：只导出了 ${chosen.fileCount}/${scanned.fileCount} 个；未勾选的文件随临时目录一起清除")
                }
                scanResult = null
                selected.clear()
                exportedNames.clear()
            } catch (t: Throwable) {
                setStatus(StatusKind.ERROR, "导出失败：${t.message}")
                logLine("导出：异常 $t")
            } finally {
                busy = false
            }
        }
    }

    /** 只保留已勾选的文件。 */
    private fun onlySelected(result: ExportEngine.ScanResult): ExportEngine.ScanResult {
        val dirs = result.dirs
            .map { dir -> dir.copy(files = dir.files.filter { selected.contains(it.path) }) }
            .filter { it.files.isNotEmpty() }
        return result.copy(dirs = dirs)
    }

    // ------------------------------------------------------------ 已导出标记

    /** 检查扫描结果里哪些文件在当前导出目录里已经存在（标记「已导出」）。 */
    private fun refreshExported(context: Context) {
        val result = scanResult ?: return
        val names = result.dirs.flatMap { dir -> dir.files }.map { ExportEngine.targetNameOf(it) }
        exportedNames.clear()
        if (names.isEmpty()) return
        viewModelScope.launch {
            try {
                val runner = runnerFor(context)
                val out = withContext(Dispatchers.IO) {
                    runner.run(ExportEngine.alreadyExportedScript(dest, names))
                }
                out.lines.filter { it.startsWith("EX\t") }.forEach { line ->
                    exportedNames.add(line.substringAfter('\t'))
                }
                if (exportedNames.isNotEmpty()) {
                    logLine("导出：其中 ${exportedNames.size} 个文件已存在于 $dest（标记为已导出）")
                }
            } catch (t: Throwable) {
                logLine("导出：检查已存在文件失败 ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------ 日志导出

    /**
     * 把当前日志写成文件放到临时共享目录，再交给其他应用（分享/打开）。
     * 和预览同理：别的应用没有 root/Shizuku，读不到应用私有目录，必须走临时共享目录 + content://。
     */
    fun shareLog(context: Context) {
        if (busy) return
        if (log.isEmpty()) {
            setStatus(StatusKind.WARN, "还没有日志可导出。")
            return
        }
        val dir = previewDir()
        if (dir == null) {
            setStatus(StatusKind.ERROR, "无法创建临时目录。")
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val appVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (t: Throwable) {
                    "?"
                }
                // 文件名用工具的 ASCII 标识（与包名同源），避免被误认成夸克自己导出的文件
                val file = File(dir, "QuarkScanExtractor-log-$stamp.txt")
                val header = buildString {
                    append("夸克扫描导出工具 日志\n")
                    append("作者：Blazkness · https://github.com/SampleBlazkness\n")
                    append("时间：$stamp\n")
                    append("应用版本：${appVersion}\n")
                    append("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）· ${Build.MODEL}\n")
                    append("授权方式：${mode.title}\n")
                    append("输出目录：$dest\n")
                    append("导出后清空临时文件：${if (cleanTemp) "开" else "关"}\n")
                    append("扫描完成提醒：${if (monitorEnabled) "开" else "关"}\n")
                    append("系统通知权限：${if (notificationsAllowed) "允许" else "被关闭"}\n")
                    append("省电策略：${if (batteryUnrestricted) "无限制" else "受限制"}\n")
                    append("当前扫描：${scanResult?.fileCount ?: 0} 个文件\n")
                    append("----------------\n")
                }
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    file.writeText(header + log.joinToString("\n") + "\n")
                }
                previewPending = true
                logLine("日志：已生成文件 ${file.name}")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(Intent.createChooser(intent, "导出日志"))
                    logLine("日志：已交给所选应用（${file.length()} 字节）")
                } catch (t: Throwable) {
                    setStatus(StatusKind.WARN, "没有找到能接收日志的应用。")
                    logLine("日志：调用分享失败 ${t.message}")
                }
            } catch (t: Throwable) {
                setStatus(StatusKind.ERROR, "导出日志失败：${t.message}")
                logLine("日志：异常 $t")
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------ 预览

    private fun previewDir(): File? = getApplication<Application>().getExternalFilesDir("preview")
    /**
     * 预览：先把 .temp 复制到应用自己的外部目录，再以 content:// 交给系统图片查看器（图片类是图片查看器）。
     * 系统看图 App 读不到夸克私有目录，所以必须先复制；回到本应用后清理临时文件。
     */
    fun preview(context: Context, file: ExportEngine.TempFile) {
        if (busy) return
        if (!file.type.mime.startsWith("image/")) {
            setStatus(StatusKind.WARN, "这是 ${file.type.label} 文件，不能按图片预览。")
            logLine("预览：跳过 ${file.name}（${file.type.label} 不能按图片预览）")
            return
        }
        val dir = previewDir()
        if (dir == null) {
            setStatus(StatusKind.ERROR, "无法创建临时目录。")
            return
        }
        val target = File(dir, file.name.removeSuffix(".temp") + "." + file.type.ext)
        viewModelScope.launch {
            busy = true
            try {
                val runner = runnerFor(context)
                val result = withContext(Dispatchers.IO) {
                    runner.run(ExportEngine.previewCopyScript(file.path, target.absolutePath))
                }
                if (!result.output.contains("OK\t")) {
                    val detail = result.output.trim().take(160)
                    setStatus(StatusKind.ERROR, "准备预览失败：$detail")
                    logLine("预览：失败 $detail")
                    return@launch
                }
                previewPending = true
                logLine("预览：已复制到临时目录 ${target.name}")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.type.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    logLine("预览：已交给所选应用打开（${file.type.label}）")
                } catch (t: Throwable) {
                    setStatus(StatusKind.WARN, "没有找到能打开 ${file.type.label} 的应用。")
                    logLine("预览：启动查看器失败 ${t.message}")
                }
            } catch (t: Throwable) {
                setStatus(StatusKind.ERROR, "预览失败：${t.message}")
                logLine("预览：异常 $t")
            } finally {
                busy = false
            }
        }
    }

    /** 删掉预览临时目录（从看图 App 回到本应用时调用）。 */
    private fun cleanupPreview(force: Boolean = false) {
        val dir = previewDir() ?: return
        if (!force && !previewPending) return
        previewPending = false
        // getExternalFilesDir() 会顺手创建目录，所以要用「目录里有没有东西」判断
        if (dir.listFiles()?.isNotEmpty() != true) return
        viewModelScope.launch {
            try {
                val runner = runnerFor(getApplication())
                withContext(Dispatchers.IO) { runner.run(ExportEngine.previewCleanScript(dir.absolutePath)) }
                logLine("预览：已清理临时文件")
            } catch (t: Throwable) {
                logLine("预览：清理临时文件失败 ${t.message}")
            }
        }
    }

    /**
     * 让系统媒体库收录导出结果。
     *
     * 以前是「每个文件起一次 `content call`」：实测单次 1.62 s（每次都现起一个 ART 虚拟机），
     * 8 个文件就是 8.83 s —— 导出的大头全在这里，而文件复制本身只要 0.62 s。
     * 现在改成：应用内直连（`MediaScannerConnection` 一次调用、进程内完成，不额外起进程），
     * 并且放到后台等回调，不拖慢导出；只有系统确实没收录的那几个才用提权扫描兜底。
     */
    private fun refreshMediaStore(context: Context, runner: ShellRunner, paths: List<String>) {
        if (paths.isEmpty()) return
        val missed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = java.util.concurrent.CountDownLatch(paths.size)
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { path, uri ->
            if (uri == null && path != null) missed += path
            latch.countDown()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val finished = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            if (missed.isNotEmpty()) {
                logLine("导出：媒体库直连未收录 ${missed.size} 个，改用提权扫描兜底")
                try {
                    val result = runner.run(ExportEngine.mediaScanScript(missed.toList()))
                    result.lines.filter { it.startsWith("SCAN\t") }.forEach { line ->
                        val parts = line.split('\t')
                        val name = parts.getOrNull(1)?.substringAfterLast('/').orEmpty()
                        logLine("导出：媒体库收录（兜底）$name → ${parts.getOrNull(2).orEmpty().take(120)}")
                    }
                } catch (t: Throwable) {
                    logLine("导出：媒体库兜底扫描失败 ${t.message}")
                }
            } else if (finished) {
                logLine("导出：媒体库已收录 ${paths.size} 个文件")
            } else {
                logLine("导出：媒体库收录仍在进行（${paths.size} 个文件）")
            }
        }
    }

    private suspend fun runnerFor(context: Context): ShellRunner = withContext(Dispatchers.IO) {
        when (mode) {
            PrivilegeMode.ROOT -> rootRunner
                ?: RootShellRunner(timeoutMs = MANUAL_TIMEOUT_MS, packageName = context.packageName)
                    .also { rootRunner = it }

            PrivilegeMode.SHIZUKU -> {
                val runner = shizukuRunner
                    ?: ShizukuShellRunner(context.packageName).also { shizukuRunner = it }
                runner.ensureConnected()
                runner
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        shizukuRunner?.close()
        ShizukuRuntime.onPermissionResult = null
    }

    // ------------------------------------------------------------ 内部

    private fun loadMode(): PrivilegeMode {
        val saved = prefs.getString(KEY_MODE, null)
        return PrivilegeMode.entries.firstOrNull { it.name == saved } ?: PrivilegeMode.SHIZUKU
    }

    private fun loadDir(): String =
        prefs.getString(KEY_DIR, null)?.takeIf { it.isNotBlank() } ?: ExportEngine.DEFAULT_DEST

    private fun setStatus(kind: StatusKind, text: String) {
        statusKind = kind
        status = text
    }

    private fun logLine(line: String) {
        QseLog.add(line)
    }

    /** 系统权限弹窗的结果（成功/被拒/压根没弹）。 */
    fun logNotificationPermissionResult(granted: Boolean) {
        logLine(
            if (granted) "系统权限弹窗：用户允许了通知"
            else "系统权限弹窗：没拿到通知权限（被拒或系统未弹框）→ 已跳系统设置页"
        )
    }

    companion object {
        private const val PREFS_NAME = "quark_scan_extractor"
        private const val KEY_MODE = "privilege_mode"
        private const val KEY_DIR = "export_dir"
        private const val KEY_CLEAN = "clean_temp"
        private const val KEY_MONITOR = "monitor_enabled"
        private const val KEY_BASELINE_READY = "monitor_baseline_ready"
        private const val KEY_BANNER_STEP = "banner_step_done"
        private const val KEY_SHIZUKU_DENIED = "shizuku_denied"

        /** 监视轮询间隔。 */
        private const val MONITOR_INTERVAL_MS = 3_000L

        /** 启动自动检测：短超时，避免 KernelSU 弹窗没人点就卡住界面。 */
        // 这是**我们**等 su 回来多久，跟 Magisk 自己的「超级用户请求超时」无关
        // （那是它的设置：默认 10 秒，可选 0/-1/10/20/30/60）。
        // 用户主动触发的检测（点按钮 / 切换授权方式）用这个长值，是为了盖过
        // Magisk 的最长配置（60 秒）以及"设成不超时"的情况；KernelSU 不弹窗，
        // 会立刻 error=13 回来，等不到超时。
        private const val MANUAL_TIMEOUT_MS = 120_000L

        /** 复核授权是否仍有效时的超时（只需一次 exec，不必等久）。 */
        private const val VERIFY_TIMEOUT_MS = 10_000L
    }
}
