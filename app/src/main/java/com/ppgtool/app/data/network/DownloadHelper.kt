package com.ppgtool.app.data.network

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object DownloadHelper {

    fun getUpdateDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "updates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun installApk(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "安装包文件不存在", Toast.LENGTH_SHORT).show()
            return
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val apkType = "application/vnd.android.package-archive"

        // 方法1: 直接 ACTION_VIEW
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, apkType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(viewIntent)
            return
        } catch (_: Exception) {}

        // 方法2: 尝试已知的系统安装器组件
        val installerPackages = listOf(
            "com.miui.packageinstaller" to "com.miui.packageinstaller.ui.InstallStart",
            "com.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
            "com.google.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
            "com.huawei.packageinstaller" to "com.huawei.packageinstaller.ui.InstallStart",
            "com.samsung.android.packageinstaller" to "com.samsung.android.packageinstaller.ui.InstallStart",
            "com.coloros.packageinstaller" to "com.coloros.packageinstaller.PackageInstallerActivity",
            "com.vivo.packageinstaller" to "com.vivo.packageinstaller.ui.InstallStart"
        )

        for ((pkg, cls) in installerPackages) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, apkType)
                    setClassName(pkg, cls)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }

        // 方法3: 最终回退
        try {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                data = contentUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开安装器，请手动安装: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }
}
