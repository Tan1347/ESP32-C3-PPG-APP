package org.tan.ppgtoolapp.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.tan.ppgtoolapp.data.network.ReleaseInfo
import org.tan.ppgtoolapp.viewmodel.OtaResult
import org.tan.ppgtoolapp.viewmodel.OtaViewModel
import org.tan.ppgtoolapp.viewmodel.OperationState

@Composable
private fun RepoSettingsDialog(
    currentRepo: String,
    defaultRepo: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentRepo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("固件仓库设置") },
        text = {
            Column {
                Text(
                    "输入 GitHub 仓库地址，格式: owner/repo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("仓库地址") },
                    placeholder = { Text(defaultRepo) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "默认: $defaultRepo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.ifBlank { defaultRepo })
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun OtaScreen(
    navController: NavController,
    viewModel: OtaViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    // 加载设备状态
    LaunchedEffect(Unit) {
        viewModel.loadDeviceStatus()
    }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.selectLocalFile(it) }
    }

    // 显示 Release 选择对话框
    if (state.showReleaseDialog) {
        ReleaseListDialog(
            releases = state.releaseList,
            onSelect = { viewModel.downloadAndExtract(it) },
            onDismiss = { viewModel.dismissReleaseDialog() }
        )
    }

    // 显示仓库配置对话框
    if (state.showRepoDialog) {
        RepoSettingsDialog(
            currentRepo = state.firmwareRepo,
            defaultRepo = OtaViewModel.DEFAULT_FIRMWARE_REPO,
            onSave = { viewModel.saveFirmwareRepo(it) },
            onDismiss = { viewModel.dismissRepoDialog() }
        )
    }

    // 显示错误提示
    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    // 显示结果提示
    LaunchedEffect(state.result) {
        state.result?.let {
            when (it) {
                is OtaResult.Success -> Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
                is OtaResult.Error -> Toast.makeText(context, it.message, Toast.LENGTH_LONG).show()
            }
            viewModel.clearResult()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 当前版本 (clickable to refresh)
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.loadDeviceStatus() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("当前固件版本", style = MaterialTheme.typography.bodySmall)
                    Text(state.deviceVersion, style = MaterialTheme.typography.headlineMedium)
                }
                Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 固件仓库配置
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.showRepoDialog() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("固件仓库", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.firmwareRepo.ifBlank { OtaViewModel.DEFAULT_FIRMWARE_REPO },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()

        Text("固件来源", style = MaterialTheme.typography.titleMedium)

        // GitHub Release 下载
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.loadReleases() },
            enabled = state.operation is OperationState.Idle
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("从 GitHub 下载", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (state.isLoadingReleases) "正在获取版本列表..." else "从 Release 下载最新固件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.isLoadingReleases) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }

        // 本地文件选择
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { filePickerLauncher.launch("*/*") },
            enabled = state.operation is OperationState.Idle
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择本地文件", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.selectedFileName ?: "点击选择 .7z 或 .bin 文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 下载/解压进度
        val op = state.operation
        if (op is OperationState.Downloading || op is OperationState.Extracting) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val progress = when (op) {
                        is OperationState.Downloading -> op.progress / 100f
                        else -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    val text = when (op) {
                        is OperationState.Downloading -> op.text
                        is OperationState.Extracting -> op.text
                        else -> ""
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 固件信息
        if (state.firmwareFile != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("固件已就绪", style = MaterialTheme.typography.titleSmall)
                        Text(state.selectedFileName ?: "", style = MaterialTheme.typography.bodySmall)
                        val sizeMB = state.firmwareFile!!.length() / 1024.0 / 1024.0
                        Text(String.format("大小: %.2f MB", sizeMB), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 上传进度
        if (op is OperationState.Uploading) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("正在上传固件...", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { op.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(op.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 升级按钮
        Button(
            onClick = { viewModel.startOta() },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.firmwareFile != null && state.operation is OperationState.Idle
        ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开始升级")
        }

        // 安全提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("注意事项", style = MaterialTheme.typography.titleSmall)
                    Text("• 升级过程中请勿断开连接", style = MaterialTheme.typography.bodySmall)
                    Text("• 请确保设备电量充足", style = MaterialTheme.typography.bodySmall)
                    Text("• 升级失败会自动回滚到旧版本", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ReleaseListDialog(
    releases: List<ReleaseInfo>,
    onSelect: (ReleaseInfo) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择版本") },
        text = {
            Column {
                releases.forEach { release ->
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { onSelect(release) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(release.tagName, style = MaterialTheme.typography.titleSmall)
                            if (release.apkSize > 0) {
                                val sizeMB = release.apkSize / 1024.0 / 1024.0
                                Text(
                                    String.format("大小: %.1f MB", sizeMB),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (release.body.isNotBlank()) {
                                Text(
                                    release.body.take(100) + if (release.body.length > 100) "..." else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
