package com.blazkness.quarkscanextractor.util

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 界面侧和服务侧共用的一份日志。
 *
 * 之前服务的记录（常驻通知贴/撤、探测、通道被关…）只写进 logcat，
 * 用户从程序里导出的日志里根本看不到 —— 排查问题等于缺了关键的那半。
 * 现在两边都写这里，导出的日志就是完整的。
 *
 * 每行带时间戳，方便和系统行为（通知出现/消失的时刻）对齐。
 */
object QseLog {

    private const val MAX_LINES = 400

    // SimpleDateFormat 不是线程安全的：界面侧（主线程）和监视服务（自己的协程）
    // 会同时写日志，共用同一个实例会出现时间戳错乱甚至抛异常，所以整段加锁。
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val lines = mutableStateListOf<String>()

    /**
     * 记一行日志。
     * @param tag logcat 的 tag（界面侧 QSE-App / 服务侧 QSE-Monitor），方便从 adb 过滤。
     */
    fun add(line: String, tag: String = "QSE-App") {
        Log.d(tag, line)
        synchronized(lock) {
            val stamped = "${timeFormat.format(Date())}  $line"
            lines.add(stamped)
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }
}
