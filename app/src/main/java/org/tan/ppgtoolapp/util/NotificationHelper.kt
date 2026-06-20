package org.tan.ppgtoolapp.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Helper for download progress notifications
 */
object NotificationHelper {

    private const val CHANNEL_ID = "ppg_download"
    private const val CHANNEL_NAME = "File Downloads"
    private const val NOTIFICATION_ID = 1001

    /**
     * Create notification channel (Android 8+)
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PPG file download progress"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Show download progress notification
     */
    fun showProgress(context: Context, fileName: String, progress: Int) {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(fileName)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Show download complete notification
     */
    fun showComplete(context: Context, fileName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(fileName)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    /**
     * Show download failed notification
     */
    fun showFailed(context: Context, fileName: String, reason: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("$fileName: $reason")
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID + 2, notification)
    }

    /**
     * Cancel all download notifications
     */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(NOTIFICATION_ID + 1)
        manager.cancel(NOTIFICATION_ID + 2)
    }
}
