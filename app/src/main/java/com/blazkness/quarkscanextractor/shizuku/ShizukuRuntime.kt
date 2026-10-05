package com.blazkness.quarkscanextractor.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku 的存活 / 权限状态查询，以及授权结果回调。 */
object ShizukuRuntime {

    const val REQUEST_CODE = 4201

    /** 授权结果回调：(是否已授权) */
    var onPermissionResult: ((Boolean) -> Unit)? = null

    /** Shizuku 服务退出的回调。 */
    var onBinderDead: (() -> Unit)? = null

    private var listenersAdded = false

    /** Shizuku 服务是否在运行（Binder 是否可用）。 */
    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    /** Shizuku 版本是否满足本应用要求（需要 v12 及以上，用户服务才稳定）。 */
    fun version(): Int = try {
        Shizuku.getVersion()
    } catch (t: Throwable) {
        -1
    }

    fun isSupported(): Boolean = try {
        binderAlive() && !Shizuku.isPreV11() && version() >= 12
    } catch (t: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    // 注意：实测 Shizuku 13.5 上 shouldShowRequestPermissionRationale() 恒为 false
    // （拒绝授权后也不会变成 true），所以它不能用来判断"是否被拒绝过"。
    // 需要这个语义就在应用侧自己记录（见 AppViewModel.shizukuDenied）。

    /** 当前 Shizuku 后端身份 uid：0 = root，2000 = adb/shell。 */
    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (t: Throwable) {
        -1
    }

    /**
     * 发起授权申请。Shizuku 在"Binder 已死 / 未初始化"时会直接抛异常
     * （IllegalStateException: Binder hasn't been sent），所以这里必须自己兜住：
     * 用户拔权限、Shizuku 被杀的那一刻点按钮，不能把异常抛到界面线程上。
     */
    fun requestPermission(): Boolean = try {
        ensureListeners()
        if (!binderAlive()) {
            false
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }
    } catch (t: Throwable) {
        false
    }

    private fun ensureListeners() {
        if (listenersAdded) return
        listenersAdded = true
        try {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE) {
                    // 回调在 Shizuku 的 Binder 线程上：切回主线程再改界面状态
                    onMain { onPermissionResult?.invoke(grantResult == PackageManager.PERMISSION_GRANTED) }
                }
            }
            Shizuku.addBinderDeadListener {
                onMain { onBinderDead?.invoke() }
            }
        } catch (t: Throwable) {
            listenersAdded = false
        }
    }

    /** 把回调切到主线程执行（界面状态只能在主线程改，Binder 线程改会崩）。 */
    private fun onMain(block: () -> Unit) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post(block)
        } catch (t: Throwable) {
            // 主线程已不存在（进程要退出）：忽略
        }
    }
}
