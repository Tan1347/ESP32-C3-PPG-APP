package org.tan.ppgtoolapp.data.network

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream

object SevenZipHelper {

    data class ExtractResult(
        val firmwareFile: File?,
        val extractedFiles: List<String>
    )

    /**
     * 从 7z 文件中提取固件 bin 文件
     * @param archiveFile 7z 压缩包
     * @param outputDir 解压目录
     * @return 解压结果，包含找到的固件文件
     */
    fun extractFirmware(archiveFile: File, outputDir: File): ExtractResult {
        if (!outputDir.exists()) outputDir.mkdirs()

        val extractedFiles = mutableListOf<String>()
        var firmwareFile: File? = null

        try {
            SevenZFile.builder().setFile(archiveFile).get().use { sevenZFile ->
                var entry = sevenZFile.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outputFile = File(outputDir, entry.name)

                        // Zip Slip 防护：校验解压路径是否在目标目录内
                        val canonicalOutputPath = outputFile.canonicalPath
                        val canonicalOutputDir = outputDir.canonicalPath + File.separator
                        if (!canonicalOutputPath.startsWith(canonicalOutputDir)) {
                            throw SecurityException("Zip Slip 检测: ${entry.name} 尝试写入目标目录外")
                        }

                        // 确保父目录存在
                        outputFile.parentFile?.mkdirs()

                        FileOutputStream(outputFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                            }
                        }

                        extractedFiles.add(entry.name)

                        // 查找 bin 固件文件（优先匹配 firmware.bin, *.bin）
                        if (entry.name.endsWith(".bin", ignoreCase = true)) {
                            if (firmwareFile == null || entry.name.equals("firmware.bin", ignoreCase = true)) {
                                firmwareFile = outputFile
                            }
                        }
                    }
                    entry = sevenZFile.nextEntry
                }
            }
        } catch (e: Exception) {
            throw RuntimeException("解压 7z 文件失败: ${e.message}", e)
        }

        return ExtractResult(firmwareFile, extractedFiles)
    }

    /**
     * 检查文件是否为有效的 7z 文件
     */
    fun is7zFile(file: File): Boolean {
        if (!file.exists() || file.length() < 6) return false
        return try {
            val header = ByteArray(6)
            file.inputStream().use { it.read(header) }
            // 7z 文件签名: 37 7A BC AF 27 1C
            header[0] == 0x37.toByte() &&
            header[1] == 0x7A.toByte() &&
            header[2] == 0xBC.toByte() &&
            header[3] == 0xAF.toByte() &&
            header[4] == 0x27.toByte() &&
            header[5] == 0x1C.toByte()
        } catch (e: Exception) {
            false
        }
    }
}
