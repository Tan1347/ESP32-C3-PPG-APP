package com.ppgtool.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun OtaScreen(navController: NavController) {
    var currentVersion by remember { mutableStateOf("1.0.0") }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var uploadProgress by remember { mutableIntStateOf(0) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 当前版本
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("当前固件版本", style = MaterialTheme.typography.bodySmall)
                    Text(currentVersion, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        HorizontalDivider()

        Text("升级固件", style = MaterialTheme.typography.titleMedium)

        // 选择固件文件
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { /* 打开文件选择器 */ }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择固件文件", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        selectedFile ?: "点击选择 .bin 文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 上传进度
        if (isUploading) {
            Column {
                LinearProgressIndicator(
                    progress = { uploadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("上传中... $uploadProgress%", style = MaterialTheme.typography.bodySmall)
            }
        }

        // 上传结果
        uploadResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (result.contains("成功"))
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        if (result.contains("成功")) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(result)
                }
            }
        }

        // 升级按钮
        Button(
            onClick = {
                isUploading = true
                uploadProgress = 0
                // TODO: 调用 OTA 上传
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedFile != null && !isUploading
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
