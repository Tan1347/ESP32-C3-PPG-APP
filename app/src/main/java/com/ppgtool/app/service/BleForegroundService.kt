package com.ppgtool.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ppgtool.app.R
import com.ppgtool.app.data.ble.BleManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BleForegroundService : Service() {

    @Inject
    lateinit var bleManager: BleManager

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BleForegroundService = this@BleForegroundService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE 连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 BLE 设备连接"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PPG Monitor")
            .setContentText("已连接到 PPG 设备")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    companion object {
        const val CHANNEL_ID = "ble_service"
        const val NOTIFICATION_ID = 1
    }
}
