package com.blazkness.quarkscanextractor.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 读取"上次进程为什么结束"。
 *
 * `getHistoricalProcessExitReasons` 和 `ApplicationExitInfo` 都是 **API 30（Android 11）** 才有的，
 * 所以整块单独放在这里、并只从调用方做过版本判断的分支进入 —— 低版本上连这个类都不会被加载，
 * 不会出现 NoClassDefFoundError / NoSuchMethodError。
 */
object ExitReason {

    data class Info(val reason: Int, val timestamp: Long, val description: String?)

    /** 返回本应用最近一次进程退出的原因；低版本或读不到时返回 null。 */
    fun last(context: Context): Info? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            read(context)
        } catch (t: Throwable) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun read(context: Context): Info? {
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return Info(info.reason, info.timestamp, info.description)
    }

    // 这些数值直接写字面量，**不引用 ApplicationExitInfo 的常量**：
    // 常量会在类初始化时求值，低版本上就会变成 NoClassDefFoundError，正好毁掉这个隔离。
    // 数值取自 AOSP ApplicationExitInfo，并已在本机 dump 里核对过
    //（reason=10 USER REQUESTED、reason=16 PACKAGE UPDATED 与实测一致）。
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_USER_REQUESTED = 10
}
