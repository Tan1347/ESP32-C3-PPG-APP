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
            error = "Parse failed: ${e.message}"
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName.substringAfterLast("/")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Export CSV
                    IconButton(onClick = {
                        val file = ExportHelper.exportCsv(context, records, fileName)
                        if (file != null) {
                            ExportHelper.shareFile(context, file, "text/csv")
                        } else {
                            Toast.makeText(context, "CSV export failed", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Filled.TableChart, contentDescription = "Export CSV")
                    }
                    // Export PDF
                    if (statistics != null) {
                        IconButton(onClick = {
                            val file = ExportHelper.exportPdf(context, statistics!!, fileName)
                            if (file != null) {
                                ExportHelper.shareFile(context, file, "application/pdf")
                            } else {
                                Toast.makeText(context, "PDF export failed", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export PDF")
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
                    Text("No valid data", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    // Overview
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Overview", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("Records", "${stats.recordCount}")
                                StatItem("Duration", PpgAnalyzer.formatDuration(stats.durationSeconds))
                            }
                        }
                    }

                    // Heart Rate
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Heart Rate (HR)", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("Average", String.format("%.1f BPM", stats.hrAvg))
                                StatItem("Min", "${stats.hrMin} BPM")
                                StatItem("Max", "${stats.hrMax} BPM")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Std Dev: ${String.format("%.1f", stats.hrStdDev)} BPM",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // SpO2
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Blood Oxygen (SpO2)", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatItem("Average", String.format("%.1f%%", stats.spo2Avg))
                                StatItem("Min", "${stats.spo2Min}%")
                                StatItem("Max", "${stats.spo2Max}%")
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Std Dev: ${String.format("%.1f", stats.spo2StdDev)}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // SpO2 Events
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
                                    Text("SpO2 Desaturation Events", style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("${stats.spo2Events.size} event(s) with SpO2 < 90% for > 10s",
                                    style = MaterialTheme.typography.bodyMedium)
                                stats.spo2Events.forEach { event ->
                                    Spacer(Modifier.height(4.dp))
                                    Text("  Min SpO2: ${event.minSpo2}%, Duration: ${event.durationSeconds}s",
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
                                Text("No SpO2 desaturation events detected", style = MaterialTheme.typography.bodyMedium)
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
