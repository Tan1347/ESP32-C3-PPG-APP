package org.tan.ppgtoolapp.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.tan.ppgtoolapp.data.local.FileMetadata
import org.tan.ppgtoolapp.data.local.FileType
import org.tan.ppgtoolapp.data.local.PpgAnalyzer
import org.tan.ppgtoolapp.viewmodel.DataViewModel

@Composable
fun DataScreen(
    onNavigateToAnalysis: (String, String) -> Unit = { _, _ -> },
    viewModel: DataViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(state.error) {
        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // WiFi hint
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Wifi, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Data Download", style = MaterialTheme.typography.titleMedium)
                    Text("Phone and device must be on the same WiFi", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Refresh + batch buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.loadFileList() },
                enabled = !state.isLoading,
                modifier = Modifier.weight(1f)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }

            // Download All button
            if (state.fileList.isNotEmpty()) {
                val pendingCount = state.fileList.count { file -> !state.downloadedFiles.any { it.fileName == file } }
                OutlinedButton(
                    onClick = { viewModel.downloadFiles(state.fileList) },
                    enabled = !state.isDownloading && pendingCount > 0
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("All ($pendingCount)")
                }
            }
        }

        // Download progress
        if (state.isDownloading) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Downloading: ${state.downloadFileName}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    val downloadedKB = state.downloadBytes / 1024
                    val totalKB = state.downloadTotal / 1024
                    Text(
                        if (totalKB > 0) "${downloadedKB}KB / ${totalKB}KB (${state.downloadProgress}%)"
                        else "${state.downloadProgress}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Device file list
        if (state.fileList.isNotEmpty()) {
            Text("TF Card Files (${state.fileList.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.fileList) { fileName ->
                    val isDownloaded = state.downloadedFiles.any { it.fileName == fileName }
                    FileListItem(
                        filename = fileName,
                        isDownloaded = isDownloaded,
                        onDownload = { viewModel.downloadFile(fileName) }
                    )
                }
            }
        } else if (!state.isLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Tap 'Refresh File List' to load files",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Downloaded files
        if (state.downloadedFiles.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Downloaded (${state.downloadedFiles.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.downloadedFiles) { metadata ->
                    DownloadedFileItem(
                        metadata = metadata,
                        onDelete = { viewModel.deleteFile(metadata) },
                        onAnalyze = {
                            val encodedPath = java.net.URLEncoder.encode(metadata.localPath, "UTF-8")
                            val encodedName = java.net.URLEncoder.encode(metadata.fileName, "UTF-8")
                            onNavigateToAnalysis(encodedPath, encodedName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileListItem(filename: String, isDownloaded: Boolean, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when {
                    filename.endsWith(".csv") -> Icons.Filled.TableChart
                    filename.endsWith(".bin") -> Icons.Filled.Memory
                    filename.endsWith(".log") -> Icons.AutoMirrored.Filled.Article
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(filename.substringAfterLast("/"), modifier = Modifier.weight(1f))
            if (isDownloaded) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
                }
            }
        }
    }
}

@Composable
private fun DownloadedFileItem(metadata: FileMetadata, onDelete: () -> Unit, onAnalyze: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete File") },
            text = { Text("Delete ${metadata.fileName.substringAfterLast("/")}?") },
            confirmButton = { TextButton(onClick = { onDelete(); showDeleteDialog = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (metadata.fileType) {
                    FileType.PPG_RAW -> Icons.Filled.DataObject
                    FileType.PPG_RESULT -> Icons.Filled.TableChart
                    FileType.DHT11 -> Icons.Filled.Thermostat
                    FileType.LOG -> Icons.AutoMirrored.Filled.Article
                    FileType.UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(metadata.fileName.substringAfterLast("/"), style = MaterialTheme.typography.bodyMedium)
                Text("${metadata.fileSize / 1024}KB", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (metadata.fileType == FileType.PPG_RESULT) {
                IconButton(onClick = onAnalyze) {
                    Icon(Icons.Filled.Analytics, contentDescription = "Analyze", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
