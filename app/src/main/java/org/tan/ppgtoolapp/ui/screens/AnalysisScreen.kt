package org.tan.ppgtoolapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.tan.ppgtoolapp.data.local.CsvParser
import org.tan.ppgtoolapp.data.local.PpgAnalyzer
import org.tan.ppgtoolapp.data.local.PpgRecord
import org.tan.ppgtoolapp.data.local.PpgStatistics
import org.tan.ppgtoolapp.util.ExportHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    filePath: String,
    fileName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var statistics by remember { mutableStateOf<PpgStatistics?>(null) }
    var records by remember { mutableStateOf<List<PpgRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        try {
            val file = File(filePath)
            records = CsvParser.parsePpgResultFile(file)
            statistics = PpgAnalyzer.analyze(records)
            isLoading = false
        } catch (e: Exception) {
            error = "解析失败: ${e.message}"
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName.substringAfterLast("/")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 导出 CSV
                    IconButton(onClick = {
                        val file = ExportHelper.exportCsv(context, records, fileName)
                        if (file != null) {
                            ExportHelper.shareFile(context, file, "text/csv")
                        } else {
                            Toast.makeText(context, "CSV 导出失败", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.TableChart, contentDescription = "导出 CSV")
                    }
                    // 导出 PDF
                    if (statistics != null) {
                        IconButton(onClick = {
                            val file = ExportHelper.exportPdf(context, statistics!!, fileName)
                            if (file != null) {
                                ExportHelper.shareFile(context, file, "application/pdf")
                            } else {
                                Toast.makeText(context, "PDF 导出失败", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "导出 PDF")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            statistics == null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("无有效数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val stats = statistics!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 概览
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("概览", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("记录数", "${stats.recordCount}")
                                StatItem("时长", PpgAnalyzer.formatDuration(stats.durationSeconds))
                            }
                        }
                    }

                    // 心率
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("心率 (HR)", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("平均", String.format("%.1f BPM", stats.hrAvg))
                                StatItem("最低", "${stats.hrMin} BPM")
                                StatItem("最高", "${stats.hrMax} BPM")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("标准差: ${String.format("%.1f", stats.hrStdDev)} BPM",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 血氧
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("血氧 (SpO2)", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("平均", String.format("%.1f%%", stats.spo2Avg))
                                StatItem("最低", "${stats.spo2Min}%")
                                StatItem("最高", "${stats.spo2Max}%")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("标准差: ${String.format("%.1f", stats.spo2StdDev)}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 血氧事件
                    if (stats.spo2Events.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(8.dp))
                                    Text("血氧去饱和事件", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("${stats.spo2Events.size} 次 SpO2 < 90% 超过 10 秒",
                                    style = MaterialTheme.typography.bodyMedium)
                                stats.spo2Events.forEach { event ->
                                    Spacer(Modifier.height(4.dp))
                                    Text("  最低 SpO2: ${event.minSpo2}%, 持续: ${event.durationSeconds}秒",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("未检测到血氧去饱和事件", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
