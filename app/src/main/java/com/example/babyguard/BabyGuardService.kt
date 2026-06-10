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
    private var lastDbSaveTime = 0L
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
            Log.i("BabyGuardService", "🛡️ Shield Activated. Listening on Port 8888...")
            alertServer = AlertServer { incomingJson ->
                try {
                    val jsonObject = org.json.JSONObject(incomingJson)
                    val currentStatus = jsonObject.getString("status")
                    val isCrying = jsonObject.optBoolean("is_crying", false)
                    val base64Image = jsonObject.optString("image", "")

                    // Explicitly name the package so Android 14+ doesn't block the UI update!
                    val intent = Intent("BABYGUARD_NEW_DATA")
                    intent.setPackage(packageName)
                    intent.putExtra("payload", incomingJson)
                    sendBroadcast(intent)

                    if (currentStatus.contains("DANGER") || currentStatus.contains("RISK") || isCrying) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDbSaveTime > 5000) {
                            lastDbSaveTime = currentTime
                            val timeString = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                            dbHelper.saveAlert(timeString, currentStatus, base64Image)
                            fireDangerNotification(currentStatus)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BabyGuardService", "Error processing data", e)
                }
            }
            alertServer?.start()
        }
    }

    private fun fireDangerNotification(dangerText: String) {
        val intent = Intent(this, ParentActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("🚨 BabyGuard Alert!")
            .setContentText(dangerText)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val bgChannel = NotificationChannel(FOREGROUND_CHANNEL_ID, "Background Monitor", NotificationManager.IMPORTANCE_LOW)
            bgChannel.setShowBadge(false)
            val alarmChannel = NotificationChannel(ALERT_CHANNEL_ID, "Danger Alarms", NotificationManager.IMPORTANCE_HIGH)
            alarmChannel.enableVibration(true)
            manager.createNotificationChannel(bgChannel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    private fun startForegroundServiceNotification() {
        val intent = Intent(this, ParentActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("BabyGuard is Active")
            .setContentText("Monitoring baby in the background...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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