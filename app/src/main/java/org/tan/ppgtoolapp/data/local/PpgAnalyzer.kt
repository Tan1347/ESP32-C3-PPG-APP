package org.tan.ppgtoolapp.data.local

import kotlin.math.sqrt

/**
 * PPG data statistical analysis
 */
object PpgAnalyzer {

    private const val SPO2_LOW_THRESHOLD = 90
    private const val SPO2_EVENT_MIN_DURATION_SEC = 10

    /**
     * Calculate statistics for a list of PPG records
     */
    fun analyze(records: List<PpgRecord>): PpgStatistics? {
        if (records.isEmpty()) return null

        val validHr = records.filter { it.hrValid && it.heartRate > 0 }.map { it.heartRate }
        val validSpo2 = records.filter { it.spo2Valid && it.spo2 > 0 }.map { it.spo2 }

        val durationSeconds = if (records.size >= 2) {
            records.last().timestamp - records.first().timestamp
        } else 0L

        return PpgStatistics(
            recordCount = records.size,
            durationSeconds = durationSeconds,
            hrAvg = if (validHr.isNotEmpty()) validHr.average() else 0.0,
            hrMin = validHr.minOrNull() ?: 0,
            hrMax = validHr.maxOrNull() ?: 0,
            hrStdDev = calculateStdDev(validHr),
            spo2Avg = if (validSpo2.isNotEmpty()) validSpo2.average() else 0.0,
            spo2Min = validSpo2.minOrNull() ?: 0,
            spo2Max = validSpo2.maxOrNull() ?: 0,
            spo2StdDev = calculateStdDev(validSpo2),
            spo2Events = detectSpo2Events(records)
        )
    }

    /**
     * Detect SpO2 desaturation events (SpO2 < 90% for > 10 seconds)
     */
    private fun detectSpo2Events(records: List<PpgRecord>): List<Spo2Event> {
        val events = mutableListOf<Spo2Event>()
        var eventStart: Long? = null
        var eventMinSpo2 = 100

        for (record in records) {
            if (!record.spo2Valid || record.spo2 <= 0) continue

            if (record.spo2 < SPO2_LOW_THRESHOLD) {
                if (eventStart == null) {
                    eventStart = record.timestamp
                    eventMinSpo2 = record.spo2
                } else {
                    if (record.spo2 < eventMinSpo2) {
                        eventMinSpo2 = record.spo2
                    }
                }
            } else {
                // SpO2 recovered
                if (eventStart != null) {
                    val duration = (record.timestamp - eventStart).toInt()
                    if (duration >= SPO2_EVENT_MIN_DURATION_SEC) {
                        events.add(Spo2Event(eventStart, duration, eventMinSpo2))
                    }
                    eventStart = null
                    eventMinSpo2 = 100
                }
            }
        }

        // Handle ongoing event at end of data
        if (eventStart != null && records.isNotEmpty()) {
            val duration = (records.last().timestamp - eventStart).toInt()
            if (duration >= SPO2_EVENT_MIN_DURATION_SEC) {
                events.add(Spo2Event(eventStart, duration, eventMinSpo2))
            }
        }

        return events
    }

    /**
     * Calculate standard deviation
     */
    private fun calculateStdDev(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    /**
     * Format duration as HH:MM:SS
     */
    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
