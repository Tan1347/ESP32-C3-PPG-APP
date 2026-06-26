package org.tan.ppgtoolapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.tan.ppgtoolapp.viewmodel.SettingsViewModel

@Composable
fun UartRecordScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uartState by viewModel.uartState.collectAsState()

    // 显示串口记录结果
    LaunchedEffect(uartState.result) {
        uartState.result?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearUartResult()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题说明
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DeveloperBoard, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("串口数据记录", style = MaterialTheme.typography.titleMedium)
                    Text("通过 BLE 控制设备录制串口数据到 TF 卡", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 波特率选择
        var baudExpanded by remember { mutableStateOf(false) }
        Text("波特率", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(
                onClick = { baudExpanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("${uartState.selectedBaudRate}")
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = baudExpanded, onDismissRequest = { baudExpanded = false }) {
                SettingsViewModel.BAUD_RATES.forEach { rate ->
                    DropdownMenuItem(
                        text = { Text("$rate") },
                        onClick = { viewModel.setBaudRate(rate); baudExpanded = false }
                    )
                }
            }
        }

        // 数据位、校验、停止位 (一行三列)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 数据位
            var dbExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text("数据位", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { dbExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${uartState.selectedDataBits}")
                    }
                    DropdownMenu(expanded = dbExpanded, onDismissRequest = { dbExpanded = false }) {
                        listOf(5, 6, 7, 8).forEach { bits ->
                            DropdownMenuItem(text = { Text("$bits") },
                                onClick = { viewModel.setDataBits(bits); dbExpanded = false })
                        }
                    }
                }
            }

            // 校验位
            var parExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text("校验", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { parExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (uartState.selectedParity) { 0 -> "无"; 1 -> "偶"; 2 -> "奇"; else -> "无" })
                    }
                    DropdownMenu(expanded = parExpanded, onDismissRequest = { parExpanded = false }) {
                        listOf(0 to "无", 1 to "偶", 2 to "奇").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) },
                                onClick = { viewModel.setParity(value); parExpanded = false })
                        }
                    }
                }
            }

            // 停止位
            var stopExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.weight(1f)) {
                Text("停止位", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { stopExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("${uartState.selectedStopBits}")
                    }
                    DropdownMenu(expanded = stopExpanded, onDismissRequest = { stopExpanded = false }) {
                        listOf(1, 2).forEach { bits ->
                            DropdownMenuItem(text = { Text("$bits") },
                                onClick = { viewModel.setStopBits(bits); stopExpanded = false })
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 开始/停止按钮
        val configStr = "${uartState.selectedBaudRate} ${uartState.selectedDataBits}${when(uartState.selectedParity){0->"N";1->"E";2->"O";else->"N"}}${uartState.selectedStopBits}"
        if (uartState.isRecording) {
            Button(
                onClick = { viewModel.stopUartRecord() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("停止记录 ($configStr)")
            }
        } else {
            Button(
                onClick = { viewModel.startUartRecord() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始记录 ($configStr)")
            }
        }

        Text(
            "数据存储到 TF 卡 /uart0/ 目录，单文件 10MB 限制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
