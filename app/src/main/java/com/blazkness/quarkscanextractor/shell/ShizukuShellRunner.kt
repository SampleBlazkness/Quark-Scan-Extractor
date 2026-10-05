package com.blazkness.quarkscanextractor.shell

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.blazkness.quarkscanextractor.IUserService
import com.blazkness.quarkscanextractor.shizuku.ShizukuRuntime
import com.blazkness.quarkscanextractor.shizuku.ShizukuUserService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 通过 Shizuku 用户服务提权执行。
 *
 * 应用进程 → Shizuku Binder → 用户服务进程（root 或 shell 身份）→ 执行脚本。
 * 注意：Shizuku v13 已移除 newProcess，用户服务是官方唯一推荐方式。
 */
class ShizukuShellRunner(
    private val packageName: String
) : ShellRunner {

    override val name = "Shizuku"

    private val args = rikka.shizuku.Shizuku.UserServiceArgs(
        ComponentName(packageName, ShizukuUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .tag("quark-scan-extractor")
        .version(SERVICE_VERSION)
        .debuggable(false)

    @Volatile
    private var service: IUserService? = null

    @Volatile
    private var connecting = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                IUserService.Stub.asInterface(binder)
            } else {
                null
            }
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    @Volatile
    private var latch = CountDownLatch(1)

    /**
     * 确保用户服务已连接；未授权或超时会抛 ShellException。
     *
     * 默认值按"用户主动点按钮"这一档给（和 Root 的 120 秒一致）；后台路径
     * （监视服务、静默扫描）请显式传小值，别在后台干等两分钟。
     */
    @Synchronized
    fun ensureConnected(timeoutSeconds: Long = 120) {
        // 缓存的 Binder 可能已经死了（Shizuku 重启 / 用户取消授权），先探活
        val cached = service
        if (cached != null) {
            val alive = try {
                cached.asBinder().pingBinder()
            } catch (t: Throwable) {
                false
            }
            if (alive) return
            service = null
        }
        if (!ShizukuRuntime.binderAlive()) throw ShellException("Shizuku 服务未运行")
        if (!ShizukuRuntime.isPermissionGranted()) throw ShellException("未获得 Shizuku 授权")
        if (connecting) {
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
            if (service == null) throw ShellException("等待 Shizuku 用户服务连接超时")
            return
        }
        connecting = true
        latch = CountDownLatch(1)
        try {
            // 服务已在运行就复用，避免重复启动
            val running = try {
                rikka.shizuku.Shizuku.peekUserService(args, connection)
            } catch (t: Throwable) {
                -1
            }
            if (running == -1) {
                rikka.shizuku.Shizuku.bindUserService(args, connection)
            }
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS) || service == null) {
                throw ShellException("Shizuku 用户服务启动超时（Shizuku 可能已停止运行）")
            }
        } finally {
            connecting = false
        }
    }

    override fun run(script: String): ShellResult {
        ensureConnected()
        val stub = service ?: throw ShellException("Shizuku 用户服务未连接")
        val raw = try {
            stub.exec(script)
        } catch (t: Throwable) {
            throw ShellException("调用 Shizuku 用户服务失败：${t.message}")
        }
        val marker = raw.lastIndexOf(EXIT_MARKER)
        if (marker < 0) return ShellResult(-1, raw)
        val code = raw.substring(marker + EXIT_MARKER.length).trim().toIntOrNull() ?: -1
        val body = raw.substring(0, marker).trimEnd('\n')
        return ShellResult(code, body)
    }

    /**
     * 连接/调用卡住时的急救：**销毁我们自己的用户服务进程**（AIDL destroy → Shizuku 让它退出），
     * 再解绑并清掉缓存 Binder。这样下一次 ensureConnected 会重新拉起一个干净的用户服务，
     * 卡在里面的调用随之结束。
     *
     * 注意：**绝不会去动 Shizuku 的 server 进程** —— 那个是全体应用共用的，杀掉会让 Shizuku
     * 对所有应用失效，用户得手动重启它。这里只处理我们自己这一侧。
     */
    fun destroyAndReset() {
        close()
    }

    override fun close() {
        val bound = service ?: return
        try {
            bound.destroy()
        } catch (ignored: Throwable) {
        }
        service = null
        try {
            rikka.shizuku.Shizuku.unbindUserService(args, connection, true)
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val EXIT_MARKER = "__EXIT__="

        /** 用户服务的版本号：改了用户服务的代码就把它 +1，Shizuku 会销毁旧进程重开一个。 */
        const val SERVICE_VERSION = 1
    }
}
