package com.ppgtool.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DataScreen() {
    var files by remember { mutableStateOf(listOf<String>()) }
    var isLoading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // WiFi 连接提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("数据下载", style = MaterialTheme.typography.titleMedium)
                    Text("需要手机与设备在同一 WiFi 网络", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 刷新按钮
        Button(
            onClick = { isLoading = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("刷新文件列表")
        }

        Spacer(Modifier.height(16.dp))

        // 文件列表
        Text("TF 卡文件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { file ->
                    FileListItem(filename = file, onDownload = { })
                }
            }
        }
    }
}

@Composable
fun FileListItem(filename: String, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when {
                    filename.endsWith(".csv") -> Icons.Filled.TableChart
                    filename.endsWith(".bin") -> Icons.Filled.Memory
                    filename.endsWith(".log") -> Icons.Filled.Article
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(filename, modifier = Modifier.weight(1f))
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "下载")
            }
        }
    }
}
