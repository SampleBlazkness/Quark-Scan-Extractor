package com.blazkness.quarkscanextractor.engine

import com.blazkness.quarkscanextractor.shell.ShellRunner

/**
 * 扫描 / 导出 / 清理逻辑。全部通过提权 shell 完成，应用进程自身不直接读写 /sdcard，
 * 因此不需要任何存储权限。
 */
object ExportEngine {

    /** 夸克扫描件的私有临时目录（Android/data 下，普通应用无权访问）。 */
    const val QUARK_BASE = "/storage/emulated/0/Android/data/com.quark.browser/files/dir_pic_camera_temp"

    /** 导出目标目录。 */
    const val DEFAULT_DEST = "/storage/emulated/0/Pictures"

    /** 只导出目录名含 detect 的扫描目录（与原始脚本一致）。 */
    private const val DETECT_GLOB = "*detect*"

    data class TempFile(val path: String, val name: String, val size: Long, val type: FileType)

    data class DetectDir(val path: String, val files: List<TempFile>)

    data class ScanResult(val dirs: List<DetectDir>, val error: String? = null) {
        val fileCount: Int get() = dirs.sumOf { it.files.size }
        val totalSize: Long get() = dirs.sumOf { dir -> dir.files.sumOf { it.size } }
    }

    data class ExportItem(val source: TempFile, val targetName: String)

    data class ExportedFile(val path: String, val size: Long)

    data class ExportReport(
        val copied: List<ExportedFile>,
        val failures: List<String>,
        val cleaned: List<Pair<String, Long>>,
        val error: String? = null
    )

    // ---------------------------------------------------------------- 扫描

    fun scan(shell: ShellRunner, log: (String) -> Unit): ScanResult {
        val result = shell.run(scanScript())
        if (result.exitCode == 3) {
            return ScanResult(emptyList(), "夸克临时目录不存在：$QUARK_BASE")
        }
        val dirs = mutableListOf<DetectDir>()
        var currentPath: String? = null
        var currentFiles = mutableListOf<TempFile>()

        for (line in result.lines) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            when (parts[0]) {
                "D" -> {
                    currentPath?.let { dirs += DetectDir(it, currentFiles) }
                    currentPath = parts.getOrNull(1)
                    currentFiles = mutableListOf()
                }

                "F" -> {
                    val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    val type = FileType.fromHexHeader(parts.getOrNull(2).orEmpty())
                    val path = parts.getOrNull(3).orEmpty()
                    if (path.isNotEmpty()) {
                        currentFiles += TempFile(path, path.substringAfterLast('/'), size, type)
                    }
                }

                "ERR" -> return ScanResult(emptyList(), parts.getOrNull(1) ?: "扫描失败")
            }
        }
        currentPath?.let { dirs += DetectDir(it, currentFiles) }

        if (dirs.isEmpty() && !result.output.contains("END")) {
            return ScanResult(emptyList(), "扫描失败（exit=${result.exitCode}）：${result.output.trim().take(300)}")
        }
        log("扫描完成：${dirs.sumOf { it.files.size }} 个文件")
        return ScanResult(dirs)
    }

    private fun scanScript(): String = """
        BASE='$QUARK_BASE'
        if [ ! -d "${'$'}BASE" ]; then printf 'ERR\t夸克临时目录不存在\n'; exit 3; fi
        for d in "${'$'}BASE"/$DETECT_GLOB; do
          [ -d "${'$'}d" ] || continue
          printf 'D\t%s\n' "${'$'}d"
          for f in "${'$'}d"/*.temp; do
            [ -f "${'$'}f" ] || continue
            sz=${'$'}(stat -c %s "${'$'}f" 2>/dev/null || echo 0)
            hex=${'$'}(head -c 12 "${'$'}f" 2>/dev/null | od -An -tx1 | tr -d ' \n')
            printf 'F\t%s\t%s\t%s\n' "${'$'}sz" "${'$'}hex" "${'$'}f"
          done
        done
        echo END
    """.trimIndent()

    // ---------------------------------------------------------------- 导出

    fun export(
        shell: ShellRunner,
        result: ScanResult,
        dest: String,
        cleanTemp: Boolean,
        log: (String) -> Unit
    ): ExportReport {
        val items = buildItems(result)
        if (items.isEmpty()) {
            return ExportReport(emptyList(), emptyList(), emptyList(), "没有可导出的文件，请先扫描")
        }
        log("开始导出 ${items.size} 个文件 → $dest")

        val copied = mutableListOf<ExportedFile>()
        val failures = mutableListOf<String>()

        val run = shell.run(exportScript(items, dest))
        for (line in run.lines) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            when (parts[0]) {
                "OK" -> {
                    val path = parts.getOrNull(1).orEmpty()
                    val size = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    copied += ExportedFile(path, size)
                    log("已复制 ${path.substringAfterLast('/')}（${formatSize(size)}）")
                }

                "BAD" -> {
                    val path = parts.getOrNull(1).orEmpty()
                    failures += path
                    log("大小校验不一致：${path.substringAfterLast('/')}（目标 ${parts.getOrNull(2)} / 源 ${parts.getOrNull(3)}）")
                }

                "ERR" -> {
                    val detail = parts.getOrNull(2).orEmpty()
                    failures += detail
                    log("失败：${parts.getOrNull(1)} $detail")
                }
            }
        }

        if (copied.size < items.size && failures.isEmpty()) {
            failures += "有 ${items.size - copied.size} 个文件未被处理"
        }

        val cleaned = mutableListOf<Pair<String, Long>>()
        if (cleanTemp) {
            log("清理夸克临时目录…")
            val cleanRun = shell.run(cleanScript())
            for (line in cleanRun.lines) {
                val parts = line.split('\t')
                if (parts[0] == "CLEAN") {
                    val path = parts.getOrNull(1).orEmpty()
                    val kb = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                    cleaned += path to kb * 1024
                }
            }
            if (cleaned.isEmpty()) {
                log("临时目录里没有可清理的内容")
            } else {
                log("已清理临时目录（${cleaned.size} 个目录，${formatSize(cleaned.sumOf { it.second })}）")
            }
        }

        return ExportReport(copied, failures, cleaned)
    }

    private fun buildItems(result: ScanResult): List<ExportItem> {
        val items = mutableListOf<ExportItem>()
        for (dir in result.dirs) {
            for (file in dir.files) {
                items += ExportItem(file, targetNameOf(file))
            }
        }
        return items
    }

    private fun exportScript(items: List<ExportItem>, dest: String): String {
        val body = StringBuilder()
        for (item in items) {
            val target = "$dest/${item.targetName}"
            body.append(
                """
                |S=${quote(item.source.path)}
                |D=${quote(target)}
                |if cp -f "${'$'}S" "${'$'}D" 2>/dev/null; then
                |  chmod 644 "${'$'}D" 2>/dev/null
                |  sz=${'$'}(stat -c %s "${'$'}D" 2>/dev/null || echo 0)
                |  if [ "${'$'}sz" = "${item.source.size}" ]; then printf 'OK\t%s\t%s\n' "${'$'}D" "${'$'}sz"; else printf 'BAD\t%s\t%s\t${item.source.size}\n' "${'$'}D" "${'$'}sz"; fi
                |else
                |  printf 'ERR\tcp\t%s\n' "${'$'}S"
                |fi
                |
                """.trimMargin()
            )
        }
        return """
            |DEST=${quote(dest)}
            |mkdir -p "${'$'}DEST" || { printf 'ERR\tmkdir\t%s\n' "${'$'}DEST"; exit 4; }
            |$body
            |echo END
            """.trimMargin()
    }

    // ---------------------------------------------------------------- 清理

    private fun cleanScript(): String = """
        BASE='$QUARK_BASE'
        for e in "${'$'}BASE"/*; do
          [ -e "${'$'}e" ] || continue
          kb=${'$'}(du -sk "${'$'}e" 2>/dev/null | cut -f1)
          if rm -rf "${'$'}e" 2>/dev/null; then printf 'CLEAN\t%s\t%s\n' "${'$'}e" "${'$'}{kb:-0}"; fi
        done
        echo END
    """.trimIndent()

    // ---------------------------------------------------------------- 媒体库

    /** 让 MediaProvider 收录导出文件（应用侧也会调用 MediaScannerConnection 兜底）。 */
    fun mediaScanScript(paths: List<String>): String {
        val list = paths.joinToString(" ") { quote(it) }
        return """
            |for p in $list; do
            |  out=${'$'}(content call --uri content://media/ --method scan_file --arg "${'$'}p" 2>&1 | head -n 1)
            |  printf 'SCAN\t%s\t%s\n' "${'$'}p" "${'$'}out"
            |done
            |echo END
            """.trimMargin()
    }

    /** 导出目标文件名：原名去掉 .temp + 按真实类型定的扩展名。 */
    fun targetNameOf(file: TempFile): String = file.name.removeSuffix(".temp") + "." + file.type.ext

    /** 检查目标目录里是否已存在同名文件（判断「已导出」）。 */
    fun alreadyExportedScript(dest: String, names: List<String>): String {
        val sb = StringBuilder()
        for (name in names) {
            sb.append("if [ -e ").append(quote("$dest/$name"))
                .append(" ]; then printf 'EX\\t%s\\n' ").append(quote(name)).append("; fi\n")
        }
        sb.append("echo END\n")
        return sb.toString()
    }

    // ---------------------------------------------------------------- 监视

    /**
     * 一次探测：列出可导出的扫描件（路径 + 大小 + 修改时间）+ 夸克主进程数。
     * 夸克主进程数用来判断"是否被移出最近任务"——实测上滑移除后主进程会消失。
     *
     * 省电写法（输出与老写法逐字一致，已实测比对）：
     *  - 每个文件只起 1 个 stat 进程（老写法 2 个）
     *  - pidof 代替 ps|grep（2 个进程变 1 个）
     */
    fun monitorProbeScript(): String = """
        |BASE='$QUARK_BASE'
        |for d in "${'$'}BASE"/*detect*; do
        |  [ -d "${'$'}d" ] || continue
        |  for f in "${'$'}d"/*.temp; do
        |    [ -f "${'$'}f" ] || continue
        |    set -- ${'$'}(stat -c "%s %Y" "${'$'}f" 2>/dev/null)
        |    printf 'F\t%s\t%s\t%s\n' "${'$'}f" "${'$'}{1:-0}" "${'$'}{2:-0}"
        |  done
        |done
        |printf 'QUARK\t%s\n' "${'$'}(pidof com.quark.browser | wc -w)"
        |echo END
    """.trimMargin()

    // ---------------------------------------------------------------- 预览

    /** 把 .temp 复制到应用外部目录当预览临时文件（系统看图 App 读不到夸克私有目录）。 */
    fun previewCopyScript(source: String, target: String): String {
        val src = quote(source)
        val dst = quote(target)
        val parent = quote(target.substringBeforeLast('/'))
        return """
            |mkdir -p $parent 2>/dev/null
            |if cp -f $src $dst 2>/dev/null; then
            |  chmod 644 $dst 2>/dev/null
            |  printf 'OK\t%s\n' $dst
            |else
            |  printf 'ERR\tcp\t$src\n'
            |fi
            """.trimMargin()
    }

    /** 删掉整个预览临时目录。 */
    fun previewCleanScript(dir: String): String =
        "rm -rf ${quote(dir)} 2>/dev/null; echo END"

    // ---------------------------------------------------------------- 目录可写性

    /** 用提权身份试建目录 + 试写探针文件，确认这个导出目录真的能用。 */
    fun probeDirScript(path: String): String {
        val dir = quote(path)
        return """
            |d=$dir
            |mkdir -p "${'$'}d" 2>/dev/null || { printf 'PROBE\tfail\t无法创建目录\n'; exit 0; }
            |t="${'$'}d/.qse_write_probe"
            |if ( : > "${'$'}t" ) 2>/dev/null; then rm -f "${'$'}t"; printf 'PROBE\tok\t\n'; else printf 'PROBE\tfail\t目录不可写\n'; fi
            """.trimMargin()
    }

    // ---------------------------------------------------------------- 工具

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
