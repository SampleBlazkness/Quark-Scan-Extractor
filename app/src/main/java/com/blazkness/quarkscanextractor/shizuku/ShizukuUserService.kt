package com.blazkness.quarkscanextractor.shizuku

import com.blazkness.quarkscanextractor.IUserService

/**
 * Shizuku 用户服务的实现。
 *
 * 这个类并不是普通的 Android Service —— 它由 Shizuku 服务端用 app_process 直接实例化，
 * 进程身份是 root 或 shell，因此这里执行的任何代码都拥有提权身份。
 * 类必须有无参构造函数，且不能被混淆（见 keepRules/rules.keep）。
 */
class ShizukuUserService : IUserService.Stub() {

    override fun exec(script: String): String = runScript(script)

    override fun getUid(): Int = android.os.Process.myUid()

    override fun destroy() = exitNow()

    override fun exit() = exitNow()

    private fun exitNow() {
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    private fun runScript(script: String): String {
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            "$output\n__EXIT__=$code"
        } catch (t: Throwable) {
            "__ERROR__=$t\n__EXIT__=-1"
        }
    }
}
