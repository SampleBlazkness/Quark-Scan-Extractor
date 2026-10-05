package com.blazkness.quarkscanextractor.engine

/** 按真实文件头（magic bytes）判断类型 —— .temp 是夸克的临时后缀，内容并不一定是 png。 */
enum class FileType(val ext: String, val label: String, val mime: String) {
    JPEG("jpg", "JPEG", "image/jpeg"),
    PNG("png", "PNG", "image/png"),
    WEBP("webp", "WebP", "image/webp"),
    GIF("gif", "GIF", "image/gif"),
    BMP("bmp", "BMP", "image/bmp"),
    HEIF("heic", "HEIF", "image/heic"),
    PDF("pdf", "PDF", "application/pdf"),
    UNKNOWN("bin", "未知", "application/octet-stream");

    companion object {
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")

        /** hex 是文件前 12 字节的十六进制字符串（小写、无分隔）。 */
        fun fromHexHeader(hex: String): FileType {
            val h = hex.lowercase()
            fun at(from: Int, to: Int): String =
                if (h.length >= to) h.substring(from, to) else ""

            return when {
                h.startsWith("ffd8ff") -> JPEG
                h.startsWith("89504e47") -> PNG
                h.startsWith("47494638") -> GIF
                h.startsWith("424d") -> BMP
                h.startsWith("25504446") -> PDF
                h.startsWith("52494646") && at(16, 24) == "57454250" -> WEBP
                at(8, 16) == "66747970" && hexToAscii(at(16, 24)) in HEIF_BRANDS -> HEIF
                else -> UNKNOWN
            }
        }

        private fun hexToAscii(hex: String): String {
            if (hex.isEmpty() || hex.length % 2 != 0) return ""
            val sb = StringBuilder()
            var i = 0
            while (i < hex.length) {
                sb.append(hex.substring(i, i + 2).toIntOrNull(16)?.toChar() ?: '?')
                i += 2
            }
            return sb.toString()
        }
    }
}
