package com.example.hotspotunlock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Keeps the app process (and therefore the Bluetooth HID registration) alive in the background
 * and keeps retrying the connection to the laptop. Without this, Android kills the app shortly
 * after you leave it and the phone stops being a keyboard.
 */
class HidService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.hotspotunlock.STOP"
        private const val TAG = "HidService"
        private const val CHANNEL_ID = "hid_keepalive"
        private const val NOTIFICATION_ID = 1
        private const val PREFS_NAME = "HotspotUnlockPrefs"
        private const val KEY_DEVICE_ADDR = "target_device_addr"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BluetoothHidManager.get(this).stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = BluetoothHidManager.get(this)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_DEVICE_ADDR, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { manager.setTargetDevice(it) }
        manager.start()
        manager.enableAutoReconnect()
        return START_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Laptop connection", NotificationManager.IMPORTANCE_LOW)
        )

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HidService::class.java).setAction(ACTION_STOP), flags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Hotspot Unlock")
            .setContentText("Keeping your laptop connection ready")
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
