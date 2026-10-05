package com.blazkness.quarkscanextractor.engine

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * SAF（存储访问框架）的 tree Uri ←→ 真实文件系统路径的互转。
 *
 * 复制动作是在提权身份下用 shell `cp` 完成的，需要真实路径，
 * 而系统目录选择器只给 Uri，所以必须做这一步转换。
 */
object StoragePaths {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** tree Uri → 真实路径；不是本机存储（第三方网盘等 provider）时返回 null。 */
    fun physicalPath(treeUri: Uri): String? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (t: Throwable) {
            null
        } ?: return null

        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val relative = parts.getOrNull(1).orEmpty()
        val root = when {
            volume.isEmpty() -> return null
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** 真实路径 → document Uri（用于下次打开选择器时定位到当前目录）；失败返回 null。 */
    fun documentUriFor(path: String): Uri? {
        val emulated = Environment.getExternalStorageDirectory().absolutePath
        val docId = when {
            path == emulated -> "primary:"
            path.startsWith("$emulated/") -> "primary:" + path.removePrefix("$emulated/")
            path.startsWith("/storage/") -> {
                val rest = path.removePrefix("/storage/")
                val index = rest.indexOf('/')
                if (index <= 0) null else rest.substring(0, index) + ":" + rest.substring(index + 1)
            }
            else -> null
        } ?: return null
        return try {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
        } catch (t: Throwable) {
            null
        }
    }
}
