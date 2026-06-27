package org.tan.ppgtoolapp.data.local

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for PPG result binary files stored on TF card
 * Binary format (14 bytes per record):
 *   timestamp(4) + heart_rate(2) + spo2(2) + hr_valid(1) + spo2_valid(1) + reserved(3) + checksum(1)
 */
object CsvParser {

    private const val RECORD_SIZE = 14
    private const val CHECKSUM_OFFSET = 13
    private const val BUFFER_SIZE = 8192  // 8KB buffer for streaming

    /**
     * Parse a binary PPG result file (streaming, memory efficient)
     */
    fun parsePpgResultFile(file: File): List<PpgRecord> {
        if (!file.exists()) return emptyList()

        val records = mutableListOf<PpgRecord>()
        val buffer = ByteArray(BUFFER_SIZE)
        val recordBuf = ByteArray(RECORD_SIZE)
        var recordPos = 0

        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                var pos = 0
                while (pos < bytesRead) {
                    val remaining = RECORD_SIZE - recordPos
                    val toCopy = minOf(remaining, bytesRead - pos)
                    System.arraycopy(buffer, pos, recordBuf, recordPos, toCopy)
                    recordPos += toCopy
                    pos += toCopy

                    if (recordPos == RECORD_SIZE) {
                        val record = parseRecord(recordBuf, 0)
                        if (record != null) {
                            records.add(record)
                        }
                        recordPos = 0
                    }
                }
            }
        }

        return records
    }

    /**
     * Parse a single 14-byte record
     */
    private fun parseRecord(data: ByteArray, offset: Int): PpgRecord? {
        // Verify checksum (XOR of first 13 bytes)
        val expectedChecksum = data[offset + CHECKSUM_OFFSET].toInt() and 0xFF
        var calculatedChecksum = 0
        for (i in 0 until CHECKSUM_OFFSET) {
            calculatedChecksum = calculatedChecksum xor (data[offset + i].toInt() and 0xFF)
        }
        if (expectedChecksum != calculatedChecksum) {
            return null  // Checksum mismatch, skip record
        }

        val buf = ByteBuffer.wrap(data, offset, RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        val timestamp = buf.getInt().toLong() and 0xFFFFFFFFL
        val heartRate = buf.getShort().toInt() and 0xFFFF
        val spo2 = buf.getShort().toInt() and 0xFFFF
        val hrValid = (buf.get().toInt() and 0xFF) != 0
        val spo2Valid = (buf.get().toInt() and 0xFF) != 0

        return PpgRecord(
            timestamp = timestamp,
            heartRate = heartRate,
            spo2 = spo2,
            hrValid = hrValid,
            spo2Valid = spo2Valid
        )
    }
}
