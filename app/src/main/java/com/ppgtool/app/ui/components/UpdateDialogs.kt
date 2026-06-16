package com.ppgtool.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ppgtool.app.data.network.ReleaseInfo

@Composable
fun UpdateAvailableDialog(
    releaseInfo: ReleaseInfo,
    currentVersion: String,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sizeStr = if (releaseInfo.apkSize > 0) {
        String.format("%.1f MB", releaseInfo.apkSize / 1024.0 / 1024.0)
    } else ""

    // 截断更新内容到 250 个字符
    val truncatedBody = if (releaseInfo.body.length > 250) {
        releaseInfo.body.take(250) + "..."
    } else {
        releaseInfo.body
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本") },
        text = {
            Column {
                Text("新版本: ${releaseInfo.tagName}")
                Text("当前版本: $currentVersion")
                if (sizeStr.isNotEmpty()) {
                    Text("大小: $sizeStr")
                }
                if (truncatedBody.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("更新内容:", style = MaterialTheme.typography.labelMedium)
                    Text(truncatedBody, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text("立即更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后")
            }
        }
    )
}

@Composable
fun DownloadProgressDialog(
    progress: Int,
    progressText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 不允许关闭 */ },
        title = { Text("正在下载更新") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    progressText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("后台下载")
            }
        }
    )
}

@Composable
fun InstallConfirmDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载完成") },
        text = { Text("新版本已下载完成，是否立即安装？") },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text("立即安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("稍后安装")
            }
        }
    )
}
