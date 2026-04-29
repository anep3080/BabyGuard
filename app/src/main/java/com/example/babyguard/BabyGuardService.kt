package com.example.babyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BabyGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY tells Android: "If you kill me for memory, restart me immediately!"
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "BabyGuard_Monitor_Channel"

        // Create the Notification Channel (Required for modern Android)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BabyGuard Background Monitor",
                NotificationManager.IMPORTANCE_LOW // Low importance so it doesn't beep every second
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // If the user taps the background notification, open the Parent Dashboard!
        val intent = Intent(this, ParentActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build the persistent notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BabyGuard is Active")
            .setContentText("Monitoring baby in the background...")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // We can change this icon later!
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot be swiped away!
            .build()

        // Officially start the VIP foreground service
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We aren't binding, we are running indefinitely
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}