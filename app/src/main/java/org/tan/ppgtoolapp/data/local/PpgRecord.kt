package org.tan.ppgtoolapp.data.local

/**
 * Parsed PPG result record from binary file
 * Binary format: timestamp(4) + heart_rate(2) + spo2(2) + hr_valid(1) + spo2_valid(1) + reserved(3) + checksum(1) = 14 bytes
 */
data class PpgRecord(
    val timestamp: Long,      // Unix timestamp (seconds)
    val heartRate: Int,       // BPM
    val spo2: Int,            // SpO2 percentage
    val hrValid: Boolean,
    val spo2Valid: Boolean
)

/**
 * Statistical analysis results
 */
data class PpgStatistics(
    val recordCount: Int,
    val durationSeconds: Long,
    val hrAvg: Double,
    val hrMin: Int,
    val hrMax: Int,
    val hrStdDev: Double,
    val spo2Avg: Double,
    val spo2Min: Int,
    val spo2Max: Int,
    val spo2StdDev: Double,
    val spo2Events: List<Spo2Event>  // SpO2 < 90% events
)

/**
 * SpO2 desaturation event
 */
data class Spo2Event(
    val startTime: Long,      // Unix timestamp
    val durationSeconds: Int,  // Duration in seconds
    val minSpo2: Int           // Minimum SpO2 during event
)
