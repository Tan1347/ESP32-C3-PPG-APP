package com.ppgtool.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ppgtool.app.ui.navigation.Screen

@Composable
fun SettingsScreen(navController: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("设备设置", style = MaterialTheme.typography.titleMedium)

        SettingsItem(
            icon = Icons.Filled.Wifi,
            title = "WiFi 配网",
            subtitle = "管理设备 WiFi 连接",
            onClick = { navController.navigate(Screen.WifiProvision.route) }
        )

        SettingsItem(
            icon = Icons.Filled.SystemUpdate,
            title = "固件升级 (OTA)",
            subtitle = "上传新固件到设备",
            onClick = { navController.navigate(Screen.OtaUpgrade.route) }
        )

        SettingsItem(
            icon = Icons.Filled.Schedule,
            title = "同步时间",
            subtitle = "将手机时间同步到设备",
            onClick = { }
        )

        SettingsItem(
            icon = Icons.Filled.Tune,
            title = "设备参数",
            subtitle = "采样率、LED 电流等",
            onClick = { }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("应用设置", style = MaterialTheme.typography.titleMedium)

        SettingsItem(
            icon = Icons.Filled.Palette,
            title = "主题",
            subtitle = "深色 / 浅色",
            onClick = { }
        )

        SettingsItem(
            icon = Icons.Filled.Notifications,
            title = "通知",
            subtitle = "测量完成提醒",
            onClick = { }
        )

        SettingsItem(
            icon = Icons.Filled.Folder,
            title = "存储路径",
            subtitle = "下载文件保存位置",
            onClick = { }
        )
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}
