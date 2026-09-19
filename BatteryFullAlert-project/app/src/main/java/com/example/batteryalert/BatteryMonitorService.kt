package com.example.batteryalert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps a BroadcastReceiver alive to watch
 * ACTION_BATTERY_CHANGED. ACTION_BATTERY_CHANGED is a "sticky" system
 * broadcast that CANNOT be declared in the manifest on modern Android —
 * it must be registered dynamically at runtime, which is what this
 * service does in onCreate().
 */
class BatteryMonitorService : Service() {

    private var batteryReceiver: BroadcastReceiver? = null
    private var alarmRingtone: Ringtone? = null
    private var hasAlertedForThisChargeCycle = false

    companion object {
        const val SERVICE_CHANNEL_ID = "battery_monitor_service_channel"
        const val ALERT_CHANNEL_ID = "battery_full_alert_channel"
        const val SERVICE_NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification())
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: ask the system to recreate the service if it's killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was already unregistered; safe to ignore.
            }
        }
        alarmRingtone?.let {
            if (it.isPlaying) it.stop()
        }
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleBatteryChanged(intent)
            }
        }
        // ACTION_BATTERY_CHANGED only works with a dynamically registered receiver.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)

        if (level == -1 || scale == -1) return

        val percent = (level * 100) / scale
        val isPluggedIn = plugged != 0
        val isFull = percent >= 100 || status == BatteryManager.BATTERY_STATUS_FULL

        if (isFull && isPluggedIn) {
            if (!hasAlertedForThisChargeCycle) {
                hasAlertedForThisChargeCycle = true
                playAlarmSound()
                showFullBatteryHeadsUpNotification()
            }
        } else {
            // Reset once unplugged or below full, so the alert can fire again next cycle.
            if (!isPluggedIn || percent < 100) {
                hasAlertedForThisChargeCycle = false
            }
        }
    }

    private fun playAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(
                this, RingtoneManager.TYPE_ALARM
            ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            alarmRingtone = RingtoneManager.getRingtone(this, alarmUri)
            alarmRingtone?.let { ringtone ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ringtone.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                ringtone.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showFullBatteryHeadsUpNotification() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Battery Full")
            .setContentText("Battery is at 100% and plugged in. You can unplug the charger.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // On channels below Android 8, priority + full-screen-ish flags drive
            // heads-up behavior; on Android 8+ the channel importance controls it.
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun buildServiceNotification() =
        NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Battery Monitor Running")
            .setContentText("Watching for full charge")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Low-importance channel for the persistent foreground service notification.
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Battery Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(serviceChannel)

            // High-importance channel so the alert can appear as heads-up.
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Battery Full Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when the battery reaches 100% while charging"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(alertChannel)
        }
    }
}
