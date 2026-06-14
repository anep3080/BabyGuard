package com.example.babyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BabyGuardService : Service() {

    private var alertServer: AlertServer? = null
    private lateinit var dbHelper: AlertDatabaseHelper
    private val FOREGROUND_CHANNEL_ID = "BabyGuard_Background"
    private val ALERT_CHANNEL_ID = "BabyGuard_Alarms"

    override fun onCreate() {
        super.onCreate()
        dbHelper = AlertDatabaseHelper(this)
        createNotificationChannels()
        startForegroundServiceNotification()
        startListeningForAlerts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListeningForAlerts() {
        if (alertServer == null) {
            Log.i("BabyGuardService", "Listening on Port 8888...")
            alertServer = AlertServer { incomingJson ->
                try {
                    // BROADCAST TO UI: This is what switches the Parent Unit to the Dashboard!
                    val intent = Intent("BABYGUARD_NEW_DATA")
                    intent.setPackage(packageName)
                    intent.putExtra("payload", incomingJson)
                    sendBroadcast(intent)
                } catch (e: Exception) {
                    Log.e("BabyGuardService", "Broadcast error", e)
                }
            }
            alertServer?.start()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bgChannel = NotificationChannel(FOREGROUND_CHANNEL_ID, "Background Monitor", NotificationManager.IMPORTANCE_LOW)
            val alarmChannel = NotificationChannel(ALERT_CHANNEL_ID, "Danger Alarms", NotificationManager.IMPORTANCE_HIGH)
            manager.createNotificationChannel(bgChannel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    private fun startForegroundServiceNotification() {
        val intent = Intent(this, ParentActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("BabyGuard Active")
            .setContentText("Parent Monitor is running...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        alertServer?.close()
        alertServer = null
    }
}