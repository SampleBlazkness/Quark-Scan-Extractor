package com.blazkness.quarkscanextractor.shell

/**
 * 通过 `su -c <script>` 提权执行。
 *
 * KernelSU / Magisk 的 su 由内核或挂载机制拦截，首次调用会在 root 管理器里弹出授权；
 * 未授权的应用 execve("su") 会直接返回 EACCES(error=13)，且不同 ROM 上 su 的绝对路径不一致，
 * 因此这里按候选列表逐个尝试，只有「进程真的起来了」才算命中。
 */
class RootShellRunner(
    private val timeoutMs: Long = 60_000L,
    private val packageName: String = "",
    /** 已知可用的 su 路径：设置后跳过候选探测，直接用它（用于「授权是否还有效」的复核）。 */
    private val forcedSu: String? = null
) : ShellRunner {

    override val name = "Root"

    @Volatile
    private var suCommand: String? = null

    /** 已解析到的 su 可执行文件路径（null 表示尚未成功过）。 */
    val resolvedSu: String? get() = suCommand

    override fun run(script: String): ShellResult {
        val pinned = forcedSu ?: suCommand
        pinned?.let { return exec(it, script) }

        val attempted = mutableListOf<String>()
        for (candidate in CANDIDATES) {
            val result = exec(candidate, script)
            if (result.exitCode != START_FAILED) {
                suCommand = candidate
                return result
            }
            attempted += "$candidate → ${result.output.removePrefix("无法启动 $candidate：").trim().take(120)}"
        }

        val denied = attempted.any {
            it.contains("Permission denied") || it.contains("error=13") || it.contains("EACCES")
        }
        val hint = if (denied) {
            "root 管理器（KernelSU / Magisk）拒绝了本次调用：需要先在管理器里给本应用授予 root 权限"
        } else {
            "没有找到可用的 su"
        }
        return ShellResult(START_FAILED, "$hint\n尝试过的路径：\n" + attempted.joinToString("\n"))
    }

    private fun exec(command: String, script: String): ShellResult {
        val process = try {
            ProcessBuilder(command, "-c", script).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            return ShellResult(START_FAILED, "无法启动 $command：${t.message}")
        }
        process.outputStream.close()

        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                if (process.isAlive) {
                    // 到点还没回来：多半是 root 管理器弹了授权界面在等人点
                    timedOut.set(true)
                    process.destroy()
                }
            } catch (ignored: InterruptedException) {
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            ""
        }
        val code = try {
            process.waitFor()
        } catch (t: Throwable) {
            -1
        }
        if (timedOut.get()) {
            return ShellResult(
                START_FAILED,
                "等待 $command 响应超时（${timeoutMs / 1000} 秒）：root 管理器可能弹出了授权界面还在等确认"
            )
        }
        return ShellResult(code, output)
    }

    companion object {
        /** exec 根本没起来（区别于「起来了但退出码非 0」）。 */
        const val START_FAILED = -999

        private val CANDIDATES = listOf(
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su"
        )
    }
}
