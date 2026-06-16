package com.ppgtool.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ppgtool.app.ui.theme.*
import com.ppgtool.app.viewmodel.MonitorViewModel
import com.ppgtool.app.viewmodel.PpgData

@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel = hiltViewModel()
) {
    val ppgData by viewModel.ppgData.collectAsState()
    val isMeasuring by viewModel.isMeasuring.collectAsState()
    val deviceStatus by viewModel.deviceStatus.collectAsState()

    // 启动设备状态轮询
    LaunchedEffect(Unit) {
        viewModel.fetchDeviceStatus()
        viewModel.startStatusPolling()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 波形显示
        PpgWaveform(
            redValues = ppgData.redValues,
            irValues = ppgData.irValues,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        )

        // 设备状态卡片
        DeviceStatusCard(
            battery = deviceStatus.battery,
            firmwareVersion = deviceStatus.firmwareVersion,
            sdFreeMb = deviceStatus.sdFreeMb,
            isOnline = deviceStatus.isOnline
        )

        // 数据卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "心率",
                value = "${ppgData.hr}",
                unit = "BPM",
                color = PpgGreen,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "血氧",
                value = "${ppgData.spo2}",
                unit = "%",
                color = when {
                    ppgData.spo2 >= 95 -> SpO2Normal
                    ppgData.spo2 >= 90 -> SpO2Warning
                    else -> SpO2Danger
                },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricCard(
                title = "灌注指数",
                value = "${ppgData.pi}",
                unit = "%",
                color = PpgBlue,
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = "信号质量",
                value = "${ppgData.quality}",
                unit = "",
                color = if (ppgData.quality >= 60) SpO2Normal else SpO2Warning,
                modifier = Modifier.weight(1f)
            )
        }

        // 控制按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { if (isMeasuring) viewModel.stopMeasuring() else viewModel.startMeasuring() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMeasuring) PpgRed else PpgGreen
                )
            ) {
                Icon(
                    if (isMeasuring) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isMeasuring) "停止测量" else "开始测量")
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceStatusCard(
    battery: com.ppgtool.app.data.network.BatteryInfo?,
    firmwareVersion: String,
    sdFreeMb: Int,
    isOnline: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 电量
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    when {
                        battery == null -> Icons.Filled.BatteryUnknown
                        battery.soc >= 80 -> Icons.Filled.BatteryFull
                        battery.soc >= 50 -> Icons.Filled.Battery5Bar
                        battery.soc >= 30 -> Icons.Filled.Battery3Bar
                        battery.soc >= 10 -> Icons.Filled.Battery1Bar
                        else -> Icons.Filled.Battery0Bar
                    },
                    contentDescription = null,
                    tint = when {
                        battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        battery.soc >= 30 -> SpO2Normal
                        battery.soc >= 10 -> SpO2Warning
                        else -> SpO2Danger
                    },
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    if (battery != null) "${battery.soc}%" else "--",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 固件版本
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    firmwareVersion.ifBlank { "--" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // SD 卡
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.SdStorage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    if (sdFreeMb > 0) "${sdFreeMb}MB" else "--",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PpgWaveform(
    redValues: List<Float>,
    irValues: List<Float>,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val width = size.width
            val height = size.height

            // 绘制红光波形
            if (redValues.size >= 2) {
                val path = Path()
                val step = width / (redValues.size - 1).coerceAtLeast(1)
                val maxVal = redValues.max().coerceAtLeast(1f)
                val minVal = redValues.min()
                val range = (maxVal - minVal).coerceAtLeast(1f)

                redValues.forEachIndexed { index, value ->
                    val x = index * step
                    val y = height - ((value - minVal) / range) * height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, WaveformRed, style = Stroke(width = 2f))
            }

            // 绘制红外光波形
            if (irValues.size >= 2) {
                val path = Path()
                val step = width / (irValues.size - 1).coerceAtLeast(1)
                val maxVal = irValues.max().coerceAtLeast(1f)
                val minVal = irValues.min()
                val range = (maxVal - minVal).coerceAtLeast(1f)

                irValues.forEachIndexed { index, value ->
                    val x = index * step
                    val y = height - ((value - minVal) / range) * height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, WaveformIR, style = Stroke(width = 2f))
            }
        }
    }
}
