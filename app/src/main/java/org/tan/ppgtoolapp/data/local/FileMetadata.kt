package org.tan.ppgtoolapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Downloaded file metadata stored in Room database
 */
@Entity(tableName = "downloaded_files")
data class FileMetadata(
    @PrimaryKey
    val fileName: String,
    val fileSize: Long,
    val downloadTime: Long,      // System.currentTimeMillis()
    val deviceMac: String,
    val localPath: String,       // Local file path
    val fileType: FileType = FileType.UNKNOWN
)

enum class FileType {
    PPG_RAW,      // /raw/xxx.bin
    PPG_RESULT,   // /csv/xxx.csv
    DHT11,        // /env/xxx.bin
    LOG,          // /log/xxx.log
    UNKNOWN
}
