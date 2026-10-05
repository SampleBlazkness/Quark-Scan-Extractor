package com.blazkness.quarkscanextractor.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blazkness.quarkscanextractor.engine.ExportEngine
import com.blazkness.quarkscanextractor.engine.StoragePaths
import com.blazkness.quarkscanextractor.notify.ScanNotifier
import com.blazkness.quarkscanextractor.service.ScanWatchService
import kotlinx.coroutines.delay

/** 醒目的红色（Material 的 colorScheme.error 在深色主题下偏粉，看不清）。 */
private val WarnRed = Color(0xFFFF5252)

private const val AUTHOR_LINE = "作者 Blazkness"
private const val AUTHOR_URL = "github.com/SampleBlazkness"

@Composable
fun MainScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    // 系统目录选择器（SAF）：选中后把 tree Uri 交给 ViewModel 转成真实路径
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setExportDir(context, uri)
    }

    var showResetDialog by remember { mutableStateOf(false) }

    // 跳系统设置页（被拒过之后运行时弹窗不会再出现，只能引导去设置里开）
    val openNotificationSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t2: Throwable) {
                // 打不开就算了
            }
        }
    }

    // 通知权限（Android 13+ 才有运行时权限）
    // 注意：用户要是自己把权限撤了（或被系统记成"不再询问"），launch() 会立刻返回 false 而
    // 什么都不显示 —— 这时必须直接送设置页，否则点「去允许」看着像没反应。
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.logNotificationPermissionResult(granted)
        vm.refreshSystemStates()
        if (!granted) openNotificationSettings()
    }

    val openBatterySettings: () -> Unit = {
        // 系统那套「不受省电限制」的入口：小米上就是「省电策略 → 无限制」
        try {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        } catch (t: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                // 打不开就算了
            }
        }
    }
    // 跳到「扫描完成提醒」通道页：横幅(悬浮通知)和声音只有用户能在系统里开
    val openAlertChannelSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ScanNotifier.CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            openNotificationSettings()
        }
    }
    // 要通知权限：能弹就弹，弹不出来（被拒过）就直接送设置页
    val askNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }
    val notificationGranted = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // 停在程序里时定期复核系统状态。两个理由：
    // 1) 小米的省电策略/通知开关是异步落盘的，只靠 onResume 会读早一步（表现为「我明明设了却还显示去…」）；
    // 2) 授权还没给时自动重试，用户在 Shizuku 管理器里点了允许，这边自己就跳成「已完成」。
    // 进程序不主动弹任何授权框 —— 要弹只能由用户点清单里的「去…」触发。
    // 必须绑生命周期：跟 RESUMED 走，退到后台就停（否则后台每 1.5 秒醒一次白耗电）。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshSystemStates()
                vm.refreshAuthorizationQuietly()
                delay(1500)
            }
        }
    }

    // 从系统设置页回来后的"落盘等待期"：这段时间电池那一项显示转圈，别急着显示「去设置」
    var powerSettling by remember { mutableStateOf(true) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            powerSettling = true
            delay(2500)
            powerSettling = false
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "夸克扫描导出工具",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "提取夸克扫描的临时文件，导出到指定目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ChecklistCard(
                vm = vm,
                // 注意：必须顺带读 vm.notificationsAllowed（它是 Compose 状态）。
                // 只调 notificationGranted() 的话没有任何状态读取，权限变了这一行不会重组
                //（表现为"我允许了通知，清单还显示去允许"）。
                notificationGranted = vm.notificationsAllowed && notificationGranted(),
                batteryChecking = powerSettling && !vm.batteryUnrestricted,
                onAuthorize = { vm.check(context) },
                onAllowNotifications = askNotificationPermission,
                onAllowBackground = openBatterySettings,
                onOpenAlertChannel = {
                    openAlertChannelSettings()
                    vm.markBannerStepDone()
                }
            )

            Spacer(Modifier.height(12.dp))
            PrivilegeCard(vm, onCheck = { vm.check(context) })

            Spacer(Modifier.height(12.dp))
            OptionsCard(
                vm,
                onPickDir = { dirPicker.launch(StoragePaths.documentUriFor(vm.dest)) },
                onToggleMonitor = { enabled ->
                    if (enabled && !notificationGranted()) askNotificationPermission()
                    vm.setMonitorEnabled(context, enabled)
                }
            )

            Spacer(Modifier.height(12.dp))
            ActionRow(vm, onScan = { vm.scan(context) }, onExport = { vm.export(context) })

            vm.scanResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                ScanResultCard(vm, result, onPreview = { file -> vm.preview(context, file) })
            }

            Spacer(Modifier.height(12.dp))
            LogCard(vm, onExportLog = { vm.shareLog(context) })

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("重置所有设置", color = WarnRed)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AUTHOR_LINE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = AUTHOR_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            try {
                                uriHandler.openUri("https://$AUTHOR_URL")
                            } catch (t: Throwable) {
                                // 没有浏览器就算了，不影响使用
                            }
                        }
                        .padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 导出前发现有已导出过的文件：让用户选「忽略」还是「取消这些并导出」
    vm.duplicatePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { vm.dismissDuplicatePrompt() },
            title = { Text("有文件已经导出过") },
            text = {
                Text("选中的 ${prompt.names.size} 个文件在 ${vm.dest} 里已经存在。")
            },
            confirmButton = {
                TextButton(onClick = { vm.resolveDuplicatePrompt(context, deselectDuplicates = true) }) {
                    Text("取消选择已导出的并导出")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.resolveDuplicatePrompt(context, deselectDuplicates = false) }) {
                    Text("忽略")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置所有设置") },
            text = {
                Text(
                    "将恢复为默认值：\n" +
                        "· 授权方式：Shizuku\n" +
                        "· 输出目录：${ExportEngine.DEFAULT_DEST}\n" +
                        "· 导出后清空临时文件：开\n" +
                        "· 扫描完成后通知我：关"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    vm.resetSettings(context)
                }) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PrivilegeCard(vm: AppViewModel, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("访问授权", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = vm.mode == PrivilegeMode.SHIZUKU,
                    onClick = { vm.switchMode(PrivilegeMode.SHIZUKU) },
                    label = { Text(PrivilegeMode.SHIZUKU.title) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = vm.mode == PrivilegeMode.ROOT,
                    onClick = { vm.switchMode(PrivilegeMode.ROOT) },
                    label = { Text(PrivilegeMode.ROOT.title) }
                )
            }

            Spacer(Modifier.height(8.dp))
            val color = when (vm.statusKind) {
                StatusKind.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                StatusKind.OK -> MaterialTheme.colorScheme.primary
                StatusKind.WARN -> MaterialTheme.colorScheme.tertiary
                StatusKind.ERROR -> MaterialTheme.colorScheme.error
            }
            Text(
                text = vm.status,
                style = MaterialTheme.typography.bodyMedium,
                color = color
            )

            Spacer(Modifier.height(10.dp))
            // 按钮文字固定、也不随 busy 变灰：位置和样子从头到尾一致，按下去不会闪。
            // 重复点击由 ViewModel 自己拦（busy 时直接忽略），按钮状态不参与。
            Button(onClick = onCheck) {
                Text("授权 / 检测")
            }
        }
    }
}

/** 检查清单的一行。 */
private data class ChecklistRow(
    val label: String,
    val done: Boolean,
    val actionLabel: String,
    val action: () -> Unit,
    /** true 时右侧显示转圈（系统状态还在落盘，读了也不准）。 */
    val checking: Boolean = false
)

/** 首次使用引导：把「授权 → 通知 → 省电 → 横幅声音」按顺序摆出来，全部完成后就不再显示。 */
@Composable
private fun ChecklistCard(
    vm: AppViewModel,
    notificationGranted: Boolean,
    batteryChecking: Boolean,
    onAuthorize: () -> Unit,
    onAllowNotifications: () -> Unit,
    onAllowBackground: () -> Unit,
    onOpenAlertChannel: () -> Unit
) {
    val rows = listOf(
        ChecklistRow("完成文件访问授权（Shizuku 或 Root）", vm.authorized, "去授权", onAuthorize),
        ChecklistRow("允许本应用发送通知", notificationGranted, "去允许", onAllowNotifications),
        ChecklistRow(
            "允许本应用后台运行（省电策略：无限制）",
            vm.batteryUnrestricted,
            "去设置",
            onAllowBackground,
            checking = batteryChecking
        ),
        ChecklistRow(
            "打开「扫描完成提醒」的悬浮通知与声音（系统默认可能是关的）",
            vm.bannerStepDone,
            "去打开",
            onOpenAlertChannel
        )
    )
    if (rows.all { it.done }) return

    Spacer(Modifier.height(12.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("使用前准备", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${rows.count { it.done }}/${rows.size} 已完成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.forEachIndexed { index, row ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}. ${row.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // 状态放在固定宽度的槽里（已完成 / 转圈 / 空），右侧按钮位置始终不变；
                    // 否则按钮会被"已完成"顶走，看起来就是按钮消失又出现。
                    Box(
                        modifier = Modifier.width(56.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (row.done) {
                            Text(
                                text = "已完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (row.checking) {
                            // 省电策略是系统异步落盘的：刚从设置页回来时先转个圈
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    TextButton(onClick = row.action) { Text(row.actionLabel) }
                }
            }
        }
    }
}

@Composable
private fun OptionsCard(
    vm: AppViewModel,
    onPickDir: () -> Unit,
    onToggleMonitor: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("导出选项", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickDir)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("输出目录", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = vm.dest,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onPickDir) { Text("更改") }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导出后清空夸克临时文件", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(checked = vm.cleanTemp, onCheckedChange = { vm.setCleanTempEnabled(it) })
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "夸克不会立即清理旧扫描，关掉后历史文件可能会一直堆积",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("扫描完成后通知我", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = vm.monitorEnabled,
                    onCheckedChange = { onToggleMonitor(it) }
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "开启后，当夸克生成新的临时文件、并且被从最近任务移除时，自动发送通知提示有新扫描件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "关闭后，程序最小化即挂起，不跑任何后台任务",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (vm.monitorEnabled) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "隐藏常驻通知：关掉系统里本应用的「扫描监视」通知即可；关掉后只是看不到那条常驻通知，监视与提醒照常",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, ScanWatchService.WATCH_CHANNEL_ID)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (t: Throwable) {
                            // 打不开就算了
                        }
                    }) { Text("去隐藏") }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(vm: AppViewModel, onScan: () -> Unit, onExport: () -> Unit) {
    val selectedCount = vm.selected.size
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onScan, enabled = vm.authorized) { Text("扫描") }
            Button(onClick = onExport, enabled = vm.authorized && selectedCount > 0) {
                Text(if (selectedCount > 0) "导出 $selectedCount 个文件" else "导出")
            }
        }
    }
}

@Composable
private fun ScanResultCard(
    vm: AppViewModel,
    result: ExportEngine.ScanResult,
    onPreview: (ExportEngine.TempFile) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("扫描结果", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${result.fileCount} 个文件 · ${ExportEngine.formatSize(result.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                val allSelected = result.fileCount > 0 && vm.selected.size == result.fileCount
                TextButton(onClick = { if (allSelected) vm.clearSelection() else vm.selectAll() }) {
                    Text(if (allSelected) "全不选" else "全选")
                }
            }
            Text(
                text = "已选 ${vm.selected.size}/${result.fileCount} · 点行勾选，点「预览」选择应用打开",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            result.dirs.forEach { dir ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = dir.path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (dir.files.isEmpty()) {
                    Text(
                        text = "（目录里没有文件）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                dir.files.forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggleSelected(file.path) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = vm.selected.contains(file.path),
                            onCheckedChange = { vm.toggleSelected(file.path) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (vm.exportedNames.contains(ExportEngine.targetNameOf(file))) {
                                    Text(
                                        text = "已导出",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarnRed
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    text = "${ExportEngine.formatSize(file.size)} · ${file.type.label} → .${file.type.ext}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(onClick = { onPreview(file) }) { Text("预览") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCard(vm: AppViewModel, onExportLog: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onExportLog) { Text("导出日志") }
            }
            Spacer(Modifier.height(6.dp))
            if (vm.log.isEmpty()) {
                Text(
                    text = "（暂无日志）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            ) {
                vm.log.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
