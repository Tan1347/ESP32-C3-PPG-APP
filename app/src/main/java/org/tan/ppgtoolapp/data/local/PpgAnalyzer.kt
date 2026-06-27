package org.tan.ppgtoolapp.data.local

import kotlin.math.sqrt

/**
 * PPG data statistical analysis
 */
object PpgAnalyzer {

    private const val SPO2_LOW_THRESHOLD = 90
    private const val SPO2_EVENT_MIN_DURATION_SEC = 10

    /**
     * Calculate statistics for a list of PPG records (single-pass)
     */
    fun analyze(records: List<PpgRecord>): PpgStatistics? {
        if (records.isEmpty()) return null

        // HR accumulators
        var hrCount = 0
        var hrSum = 0L
        var hrMin = Int.MAX_VALUE
        var hrMax = Int.MIN_VALUE
        var hrSumSq = 0.0

        // SpO2 accumulators
        var spo2Count = 0
        var spo2Sum = 0L
        var spo2Min = Int.MAX_VALUE
        var spo2Max = Int.MIN_VALUE
        var spo2SumSq = 0.0

        // SpO2 event tracking
        val spo2Events = mutableListOf<Spo2Event>()
        var eventStart: Long? = null
        var eventMinSpo2 = 100

        // Single pass through all records
        for (record in records) {
            // HR statistics
            if (record.hrValid && record.heartRate > 0) {
                hrCount++
                hrSum += record.heartRate
                if (record.heartRate < hrMin) hrMin = record.heartRate
                if (record.heartRate > hrMax) hrMax = record.heartRate
                hrSumSq += record.heartRate.toDouble() * record.heartRate
            }

            // SpO2 statistics
            if (record.spo2Valid && record.spo2 > 0) {
                spo2Count++
                spo2Sum += record.spo2
                if (record.spo2 < spo2Min) spo2Min = record.spo2
                if (record.spo2 > spo2Max) spo2Max = record.spo2
                spo2SumSq += record.spo2.toDouble() * record.spo2
            }

            // SpO2 event detection
            if (record.spo2Valid && record.spo2 > 0) {
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
                    if (eventStart != null) {
                        val duration = (record.timestamp - eventStart).toInt()
                        if (duration >= SPO2_EVENT_MIN_DURATION_SEC) {
                            spo2Events.add(Spo2Event(eventStart, duration, eventMinSpo2))
                        }
                        eventStart = null
                        eventMinSpo2 = 100
                    }
                }
            }
        }

        // Handle ongoing event at end of data
        if (eventStart != null && records.isNotEmpty()) {
            val duration = (records.last().timestamp - eventStart).toInt()
            if (duration >= SPO2_EVENT_MIN_DURATION_SEC) {
                spo2Events.add(Spo2Event(eventStart, duration, eventMinSpo2))
            }
        }

        // Calculate standard deviation
        fun stdDev(count: Int, sum: Long, sumSq: Double): Double {
            if (count < 2) return 0.0
            val mean = sum.toDouble() / count
            val variance = (sumSq - 2 * mean * sum + count * mean * mean) / count
            return sqrt(variance)
        }

        val durationSeconds = if (records.size >= 2) {
            records.last().timestamp - records.first().timestamp
        } else 0L

        return PpgStatistics(
            recordCount = records.size,
            durationSeconds = durationSeconds,
            hrAvg = if (hrCount > 0) hrSum.toDouble() / hrCount else 0.0,
            hrMin = if (hrCount > 0) hrMin else 0,
            hrMax = if (hrCount > 0) hrMax else 0,
            hrStdDev = stdDev(hrCount, hrSum, hrSumSq),
            spo2Avg = if (spo2Count > 0) spo2Sum.toDouble() / spo2Count else 0.0,
            spo2Min = if (spo2Count > 0) spo2Min else 0,
            spo2Max = if (spo2Count > 0) spo2Max else 0,
            spo2StdDev = stdDev(spo2Count, spo2Sum, spo2SumSq),
            spo2Events = spo2Events
        )
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
