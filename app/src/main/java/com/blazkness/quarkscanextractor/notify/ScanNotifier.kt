package com.blazkness.quarkscanextractor.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blazkness.quarkscanextractor.MainActivity
import com.blazkness.quarkscanextractor.R

/** 扫描完成提醒：发现新的扫描件时发一条通知（要横幅、要响）。 */
object ScanNotifier {

    // v5：换新 ID 重建（老通道被系统/MIUI 记成了"静默"）。振动关掉，声音用系统默认。
    const val CHANNEL_ID = "scan_ready_v5"
    private val LEGACY_CHANNEL_IDS = listOf("scan_ready", "scan_ready_v2", "scan_ready_v3", "scan_ready_v4")
    private const val NOTIFICATION_ID = 4202

    fun ensureChannel(context: Context) {
        // 通知通道是 API 26+ 的 API，低版本调用会 NoSuchMethodError，先挡版本
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        LEGACY_CHANNEL_IDS.forEach { legacy ->
            try {
                if (manager.getNotificationChannel(legacy) != null) {
                    manager.deleteNotificationChannel(legacy)
                }
            } catch (t: Throwable) {
                android.util.Log.d("QSE-Monitor", "delete legacy channel $legacy failed: $t")
            }
        }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 只在系统允许发通知之后才建通道：通知还没被允许时就建，会被系统/MIUI 记成"静默通道"
        //（悬浮通知=关、声音=无），之后改不掉，横幅永远弹不出来。
        if (!notificationsEnabled(context)) return
        manager.createNotificationChannel(
            // HIGH：MIUI 才会弹横幅；声音用系统默认；振动不开（没必要）
            NotificationChannel(CHANNEL_ID, "扫描完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "夸克扫描结束后提醒去导出"
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    /** 系统是否允许本应用发通知（权限被拒/被系统关掉时返回 false）。 */
    fun notificationsEnabled(context: Context): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (t: Throwable) {
        false
    }

    /** 用户已经进程序看列表了，把那条"有缓存"的提醒撤掉，别一直挂在通知栏。 */
    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            // 没有通知权限等情况：忽略
        }
    }

    /** 这条提醒当前是否正挂着（用来判断"已经在提示中，缓存变了就撤旧发新"）。 */
    fun alertShowing(context: Context): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.activeNotifications?.any { it.id == NOTIFICATION_ID } ?: false
    } catch (t: Throwable) {
        false
    }

    /** 撤掉旧通知再发新的（Quark/缓存变化时要"立刻移除旧的"）。 */
    fun refreshScanCache(context: Context, count: Int, sizeText: String) {
        cancel(context)
        post(context, count, sizeText)
    }

    fun post(context: Context, count: Int, sizeText: String) {
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("现在有 $count 个扫描件缓存")
            .setContentText("共 $sizeText，点击进入程序导出")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // 没有通知权限等情况：忽略，但要留在共用日志里，否则"发出去了吗"无从判断
            com.blazkness.quarkscanextractor.util.QseLog.add("提醒：发送失败 ${t.message}", "QSE-Monitor")
        }
    }
}
