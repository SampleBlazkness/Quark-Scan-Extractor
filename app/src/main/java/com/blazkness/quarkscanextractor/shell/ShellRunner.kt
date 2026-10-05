package com.blazkness.quarkscanextractor.shell

/** 一次提权命令执行的结果。exitCode < 0 表示执行框架本身失败。 */
data class ShellResult(val exitCode: Int, val output: String) {
    val lines: List<String> get() = output.lines()
}

class ShellException(message: String) : Exception(message)

/** 提权执行后端：Root(su) 或 Shizuku 用户服务，两者都对外提供同一套脚本执行能力。 */
interface ShellRunner {
    val name: String

    /** 在提权身份下执行一段 sh 脚本，返回合并后的 stdout/stderr 与退出码。 */
    fun run(script: String): ShellResult

    /** 释放资源（Shizuku 用户服务会解绑）。 */
    fun close() {}
}
