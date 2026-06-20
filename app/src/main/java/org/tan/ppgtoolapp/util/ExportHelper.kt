package org.tan.ppgtoolapp.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import org.tan.ppgtoolapp.data.local.PpgAnalyzer
import org.tan.ppgtoolapp.data.local.PpgRecord
import org.tan.ppgtoolapp.data.local.PpgStatistics
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Export PPG data as CSV or PDF
 */
object ExportHelper {

    /**
     * Export PPG records as CSV file
     */
    fun exportCsv(context: Context, records: List<PpgRecord>, fileName: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "PPG/export")
            dir.mkdirs()
            val file = File(dir, "${fileName.removeSuffix(".csv")}_export.csv")

            FileWriter(file).use { writer ->
                // Header
                writer.append("Timestamp,DateTime,HeartRate(BPM),SpO2(%),HR_Valid,SPO2_Valid\n")
                // Data
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                for (record in records) {
                    val dateTime = sdf.format(Date(record.timestamp * 1000))
                    writer.append("${record.timestamp},$dateTime,${record.heartRate},${record.spo2},${if (record.hrValid) 1 else 0},${if (record.spo2Valid) 1 else 0}\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Export statistics as CSV file
     */
    fun exportStatisticsCsv(context: Context, stats: PpgStatistics, fileName: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "PPG/export")
            dir.mkdirs()
            val file = File(dir, "${fileName.removeSuffix(".csv")}_stats.csv")

            FileWriter(file).use { writer ->
                writer.append("Metric,Value\n")
                writer.append("Records,${stats.recordCount}\n")
                writer.append("Duration,${PpgAnalyzer.formatDuration(stats.durationSeconds)}\n")
                writer.append("HR_Average(BPM),${String.format("%.1f", stats.hrAvg)}\n")
                writer.append("HR_Min(BPM),${stats.hrMin}\n")
                writer.append("HR_Max(BPM),${stats.hrMax}\n")
                writer.append("HR_StdDev,${String.format("%.1f", stats.hrStdDev)}\n")
                writer.append("SpO2_Average(%),${String.format("%.1f", stats.spo2Avg)}\n")
                writer.append("SpO2_Min(%),${stats.spo2Min}\n")
                writer.append("SpO2_Max(%),${stats.spo2Max}\n")
                writer.append("SpO2_StdDev,${String.format("%.1f", stats.spo2StdDev)}\n")
                writer.append("SpO2_Events,${stats.spo2Events.size}\n")
                for ((i, event) in stats.spo2Events.withIndex()) {
                    writer.append("SpO2_Event_${i + 1},Min=${event.minSpo2}% Duration=${event.durationSeconds}s\n")
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Export statistics as PDF report
     */
    fun exportPdf(context: Context, stats: PpgStatistics, fileName: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "PPG/export")
            dir.mkdirs()
            val file = File(dir, "${fileName.removeSuffix(".csv")}_report.pdf")

            val document = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val titlePaint = Paint().apply { textSize = 24f; isFakeBoldText = true }
            val headerPaint = Paint().apply { textSize = 16f; isFakeBoldText = true }
            val bodyPaint = Paint().apply { textSize = 12f }
            val warnPaint = Paint().apply { textSize = 12f; color = 0xFFFF0000.toInt() }

            var y = 40f

            // Title
            canvas.drawText("PPG Analysis Report", 40f, y, titlePaint)
            y += 30f
            canvas.drawText("File: $fileName", 40f, y, bodyPaint)
            y += 20f
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            canvas.drawText("Generated: ${sdf.format(Date())}", 40f, y, bodyPaint)
            y += 40f

            // Overview
            canvas.drawText("Overview", 40f, y, headerPaint)
            y += 25f
            canvas.drawText("Records: ${stats.recordCount}", 60f, y, bodyPaint); y += 18f
            canvas.drawText("Duration: ${PpgAnalyzer.formatDuration(stats.durationSeconds)}", 60f, y, bodyPaint); y += 30f

            // Heart Rate
            canvas.drawText("Heart Rate (HR)", 40f, y, headerPaint); y += 25f
            canvas.drawText("Average: ${String.format("%.1f", stats.hrAvg)} BPM", 60f, y, bodyPaint); y += 18f
            canvas.drawText("Range: ${stats.hrMin} - ${stats.hrMax} BPM", 60f, y, bodyPaint); y += 18f
            canvas.drawText("Std Dev: ${String.format("%.1f", stats.hrStdDev)} BPM", 60f, y, bodyPaint); y += 30f

            // SpO2
            canvas.drawText("Blood Oxygen (SpO2)", 40f, y, headerPaint); y += 25f
            canvas.drawText("Average: ${String.format("%.1f", stats.spo2Avg)}%", 60f, y, bodyPaint); y += 18f
            canvas.drawText("Range: ${stats.spo2Min}% - ${stats.spo2Max}%", 60f, y, bodyPaint); y += 18f
            canvas.drawText("Std Dev: ${String.format("%.1f", stats.spo2StdDev)}%", 60f, y, bodyPaint); y += 30f

            // SpO2 Events
            if (stats.spo2Events.isNotEmpty()) {
                canvas.drawText("SpO2 Desaturation Events", 40f, y, headerPaint); y += 25f
                canvas.drawText("${stats.spo2Events.size} event(s) with SpO2 < 90% for > 10s", 60f, y, warnPaint); y += 18f
                for (event in stats.spo2Events) {
                    canvas.drawText("  Min SpO2: ${event.minSpo2}%, Duration: ${event.durationSeconds}s", 60f, y, bodyPaint)
                    y += 18f
                    if (y > 800f) break // Page limit
                }
            } else {
                canvas.drawText("No SpO2 desaturation events detected", 60f, y, Paint().apply { textSize = 12f; color = 0xFF008000.toInt() })
            }

            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
            document.close()

            file
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Share file via Android Share Sheet
     */
    fun shareFile(context: Context, file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }
}
