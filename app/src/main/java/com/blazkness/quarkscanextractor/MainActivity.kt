package com.blazkness.quarkscanextractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.blazkness.quarkscanextractor.service.ScanWatchService
import com.blazkness.quarkscanextractor.ui.AppViewModel
import com.blazkness.quarkscanextractor.ui.MainScreen
import com.blazkness.quarkscanextractor.ui.theme.QuarkScanTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuarkScanTheme {
                MainScreen(viewModel)
            }
        }
    }

    /**
     * 进程序：常驻通知立刻消失。
     * 顺便把监视服务提前拉起来（不等授权检测完成），这样冷启动后马上最小化也能立刻看到通知。
     */
    override fun onStart() {
        super.onStart()
        ScanWatchService.onAppVisible(this)
    }

    /** 退到后台（最小化）：常驻通知立刻出现。 */
    override fun onStop() {
        ScanWatchService.onAppHidden(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onForeground()
    }
}
