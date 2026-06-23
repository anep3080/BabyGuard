package com.example.babyguard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the alert-decision pipeline. This is the ONLY piece of the app guaranteed to stay
 * alive when the parent's screen is off or the app is backgrounded — ParentActivity's old
 * dataReceiver (registered in onResume/unregistered in onPause) silently dropped every
 * alert once the screen turned off, since nothing was listening to fire it. All tier
 * dispatch, cooldowns, quiet-hours suppression, notification building, and the History DB
 * write now happen here. ParentActivity is left as a thin UI-refresh consumer of the same
 * "BABYGUARD_NEW_DATA" broadcast.
 */
class BabyGuardService : Service() {

    private var alertServer: AlertServer? = null
    private lateinit var dbHelper: AlertDatabaseHelper
    private lateinit var prefs: AppPreferences
    private val FOREGROUND_CHANNEL_ID = "BabyGuard_Background"

    // ── HIGH-tier acknowledge state ───────────────────────────────────────────
    // canTriggerHigh mirrors the old ParentActivity behaviour: a fresh HIGH alarm can't
    // re-fire for 60s after the parent last acknowledged one (via AlarmActivity's dismiss).
    private var lastAcknowledgeTime = 0L
    private var activeRingtone: Ringtone? = null
    companion object {
        // Guards fireHighAlert from re-firing on every ~200ms HIGH-tier telemetry tick while the
        // same episode is ongoing — without this, a sustained condition (e.g. baby still
        // standing) restarts the ringtone/vibration/notification on every single incoming
        // packet. Companion-scoped (not just an instance field) so ParentActivity.onResume()
        // can check it directly and immediately show the in-app dismiss overlay if an alert
        // is already active when the app comes to the foreground.
        @Volatile var isHighAlertActive = false
        @Volatile var activeHeadline: String = ""
    }

    // ── History log dedup (moved from ParentActivity) ─────────────────────────
    // lastLoggedStatus lets handleIncoming bypass the cooldown below whenever the status text
    // actually changed — see handleIncoming. The cooldown now only throttles literal repeats of
    // the same reading (which would otherwise flood the DB at full telemetry rate), it no longer
    // suppresses genuine changes — that was the cause of "the log doesn't record every change":
    // a sustained HIGH episode used to get exactly one log row every 5 minutes even if the
    // specific status text kept changing underneath it.
    private val lastLoggedAt = mutableMapOf<String, Long>()
    private val lastLoggedStatus = mutableMapOf<String, String>()
    private val LOG_COOLDOWN = mapOf("HIGH" to 15_000L, "MEDIUM" to 30_000L, "LOW" to 120_000L)

    // ── Notification firing cooldown, separate from the log-dedup above ───────
    // A persistent condition (e.g. "Fussy", "Restless") would otherwise re-fire on every
    // ~200ms telemetry tick if not gated here. Lowered from 90s/600s — that left a long window
    // where a genuinely new MEDIUM/LOW episode went completely unnotified.
    private val lastAlertFiredAt = mutableMapOf<String, Long>()
    private val ALERT_FIRE_COOLDOWN = mapOf("MEDIUM" to 20_000L, "LOW" to 120_000L)

    private val ackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastAcknowledgeTime = System.currentTimeMillis()
            stopAlarmPlayback()
        }
    }

    // Relays a command broadcast from the Parent UI down to the Baby Unit over the same
    // persistent alert socket. (As of the camera-switch/USB-camera button removal, nothing in
    // ParentActivity sends BABYGUARD_CMD anymore, but this relay is left in place in case a
    // future remote command is added.) If there's no live Baby Unit connection (or the write
    // fails), tells ParentActivity so it can surface that to the user instead of the button
    // silently appearing to do nothing.
    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cmd = intent?.getStringExtra("cmd") ?: return
            val sent = alertServer?.sendCommand(cmd) ?: false
            if (!sent) {
                Log.w("BabyGuardService", "Command not delivered (no live Baby Unit connection): $cmd")
                sendBroadcast(Intent("BABYGUARD_CMD_RESULT").setPackage(packageName)
                    .putExtra("success", false).putExtra("cmd", org.json.JSONObject(cmd).optString("cmd")))
            }
        }
    }

    // Manual "Reconnect" tap from the Parent UI (see ParentActivity's btnReconnect). Drops the
    // current accepted socket so the Baby Unit's AlertClient — which now detects a dead write via
    // checkError() — redials within ~2s instead of the connection staying silently stuck.
    private val reconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            alertServer?.forceReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = AlertDatabaseHelper(this)
        prefs = AppPreferences(this)
        createNotificationChannels()
        startForegroundServiceNotification()
        startListeningForAlerts()

        val filter = IntentFilter("BABYGUARD_ACK")
        val cmdFilter = IntentFilter("BABYGUARD_CMD")
        val reconnectFilter = IntentFilter("BABYGUARD_RECONNECT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(ackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(cmdReceiver, cmdFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(reconnectReceiver, reconnectFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(ackReceiver, filter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cmdReceiver, cmdFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(reconnectReceiver, reconnectFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startListeningForAlerts() {
        if (alertServer == null) {
            Log.i("BabyGuardService", "Listening on Port 8888...")
            alertServer = AlertServer { incomingJson -> handleIncoming(incomingJson) }
            alertServer?.start()
        }
    }

    // ── Core alert pipeline ───────────────────────────────────────────────────

    private fun handleIncoming(jsonString: String) {
        var loggedNow = false
        try {
            val json = org.json.JSONObject(jsonString)

            // Acknowledgement for a command the Parent sent down (switch_lens, camera_source) —
            // not sensor telemetry, so it must be intercepted here before the normal
            // tier-dispatch/logging logic below, which would otherwise log/alert on a bogus
            // "status":"---" reading. Lets ParentActivity show the real outcome (e.g. "no USB
            // camera attached") instead of a generic "not connected" message for every failure.
            if (json.optString("type") == "cmd_ack") {
                val cmd = json.optString("cmd")
                val success = json.optBoolean("success", false)
                sendBroadcast(Intent("BABYGUARD_CMD_RESULT").setPackage(packageName)
                    .putExtra("success", success).putExtra("cmd", cmd))
                return
            }

            val status  = json.optString("status", "---")
            val tier    = json.optString("tier", "LOW")
            val action  = json.optString("event_action", "Normal")
            val image   = json.optString("image", "")

            // Crying already escalates to tier=="HIGH" at the source (CameraActivity's sustained-
            // cry detector sets currentTier/currentAction directly), so a separate sound-level
            // threshold check here would only ever fire a duplicate of the same alert.
            // Lowered from 60s: that left a full minute after every dismiss where a genuinely
            // NEW HIGH episode (baby covered again, stood back up again) fired no alarm at all —
            // silently, since this check skips fireHighAlert entirely rather than just delaying
            // it. 10s is enough to avoid the alarm instantly re-triggering on the same lingering
            // reading right as the parent dismisses it, without leaving a real recurrence unheard.
            val canTriggerHigh = System.currentTimeMillis() - lastAcknowledgeTime > 10_000

            if (prefs.masterAlertEnabled) {
                when {
                    tier == "HIGH" && canTriggerHigh && !isHighAlertActive -> fireHighAlert(action, status)
                    tier == "MEDIUM" && prefs.mediumAlertEnabled && canFireAlert(tier, action) ->
                        fireMediumAlert(status)
                    tier == "LOW" && prefs.lowAlertEnabled && canFireAlert(tier, action) ->
                        fireLowAlert(status)
                }
            }

            val shouldLog = when (tier) {
                "HIGH"   -> true
                "MEDIUM" -> prefs.mediumAlertEnabled
                "LOW"    -> prefs.lowAlertEnabled
                else     -> false
            }
            val logKey       = "$tier-$action"
            val cooldown     = LOG_COOLDOWN[tier] ?: 60_000L
            val lastLogged   = lastLoggedAt[logKey] ?: 0L
            val statusChanged = lastLoggedStatus[logKey] != status
            // Cooldown only throttles literal repeats of the same status text for the same
            // tier+action — any actual change always logs immediately regardless of cooldown.
            val dedupPassed  = statusChanged || (System.currentTimeMillis() - lastLogged > cooldown)

            if (shouldLog && dedupPassed && (image.isNotEmpty() || tier != "LOW")) {
                lastLoggedAt[logKey] = System.currentTimeMillis()
                lastLoggedStatus[logKey] = status
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                dbHelper.saveAlert(ts, status, image, tier, action)
                loggedNow = true
            }
        } catch (e: Exception) {
            Log.e("BabyGuardService", "handleIncoming error", e)
        }

        try {
            val intent = Intent("BABYGUARD_NEW_DATA")
            intent.setPackage(packageName)
            intent.putExtra("payload", jsonString)
            intent.putExtra("loggedNow", loggedNow)
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e("BabyGuardService", "Broadcast error", e)
        }
    }

    private fun canFireAlert(tier: String, action: String): Boolean {
        val key = "$tier-$action"
        val cooldown = ALERT_FIRE_COOLDOWN[tier] ?: 0L
        val last = lastAlertFiredAt[key] ?: 0L
        val ok = System.currentTimeMillis() - last > cooldown
        if (ok) lastAlertFiredAt[key] = System.currentTimeMillis()
        return ok
    }

    // ── Tier dispatch ──────────────────────────────────────────────────────────

    private fun fireHighAlert(action: String, status: String) {
        stopAlarmPlayback()
        isHighAlertActive = true
        val headline = formatAlertHeadline(action)
        activeHeadline = headline
        try {
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
                .vibrate(longArrayOf(0, 500, 250, 500), 0)
        } catch (_: Exception) {}
        try {
            // Use the parent's chosen alarm sound (Settings → High Alert Sound) if one was
            // picked; otherwise fall back to the system default alarm ringtone.
            val chosenUri = prefs.alarmSoundUri
            val soundUri = if (chosenUri.isNotBlank()) android.net.Uri.parse(chosenUri)
                           else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            activeRingtone = RingtoneManager.getRingtone(this, soundUri).apply { play() }
        } catch (_: Exception) {}

        // No more system-wide full-screen intent / AlarmActivity — the dismiss screen now only
        // appears inside the Parent Unit app itself. If the app is foregrounded, this broadcast
        // makes ParentActivity show its in-app alertOverlay immediately. If it isn't, the
        // vibration/ringtone/notification above are how the parent finds out — opening (or
        // returning to) the Parent Unit screen then shows the overlay via the isHighAlertActive
        // check in ParentActivity.onResume().
        sendBroadcast(Intent("BABYGUARD_HIGH_ALERT").setPackage(packageName).putExtra("headline", headline))

        val openAppIntent = Intent(this, ParentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 20, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, "BABYGUARD_HIGH")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 CRITICAL ALERT")
            .setContentText("$action — Check Baby Immediately!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2, notification)
    }

    private fun fireMediumAlert(status: String) {
        if (prefs.quietHoursEnabled && prefs.isQuietHoursActive()) {
            Log.d("BabyGuardService", "Medium alert suppressed by quiet hours: $status")
            return
        }
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(500) } catch (_: Exception) {}
        // PRIORITY_HIGH (not DEFAULT) — only matters on pre-O devices where channel importance
        // doesn't exist, but it's what makes a warning pop up as a heads-up banner there too,
        // matching the BABYGUARD_MEDIUM channel's IMPORTANCE_HIGH below for Android 8+.
        showSimpleNotification(
            "⚠️ Baby Needs Attention", status,
            "BABYGUARD_MEDIUM", NotificationCompat.PRIORITY_HIGH, 1)
    }

    private fun fireLowAlert(status: String) {
        if (prefs.quietHoursEnabled && prefs.isQuietHoursActive()) return
        showSimpleNotification(
            "ℹ️ Baby Activity", status,
            "BABYGUARD_LOW", NotificationCompat.PRIORITY_LOW, 0)
    }

    private fun showSimpleNotification(title: String, message: String, channel: String, priority: Int, notifId: Int) {
        val pending = PendingIntent.getActivity(
            this, notifId, Intent(this, ParentActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pending)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, builder.build())
    }

    private fun stopAlarmPlayback() {
        isHighAlertActive = false
        activeHeadline = ""
        try { activeRingtone?.stop() } catch (_: Exception) {}
        activeRingtone = null
        try { (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel() } catch (_: Exception) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(2) } catch (_: Exception) {}
    }

    private fun formatAlertHeadline(action: String): String = when (action) {
        "Standing"               -> "Baby is standing — risk of falling from crib"
        "Face Down"               -> "Baby rolled face-down — check airways now"
        "Crying"                  -> "Baby has been crying — needs attention"
        "Suffocation"              -> "Baby's face hidden for 5 seconds — check immediately"
        "Possible Interference"    -> "Unexpected presence detected and baby's motion stopped — check now"
        else                       -> "Check on baby — unusual activity detected"
    }

    // ── Notification channels / foreground notification ──────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(FOREGROUND_CHANNEL_ID, "Background Monitor", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel("BABYGUARD_HIGH", "Critical Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Immediate alerts requiring action"
                    enableVibration(true)
                    setShowBadge(true)
                })
            nm.createNotificationChannel(
                // IMPORTANCE_HIGH (not DEFAULT) — a "warning" tier alert should pop up as a
                // heads-up banner instead of silently landing in the shade, since it means the
                // baby needs attention. Channel importance is what actually controls heads-up
                // behavior on Android 8+ (the NotificationCompat priority above only matters on
                // pre-O devices, where importance doesn't exist).
                NotificationChannel("BABYGUARD_MEDIUM", "Baby Updates", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Medium-priority baby activity updates"
                    setShowBadge(true)
                    enableVibration(true)
                })
            nm.createNotificationChannel(
                NotificationChannel("BABYGUARD_LOW", "Baby Activity", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Low-priority informational updates"
                    setShowBadge(false)
                })
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
        try { unregisterReceiver(ackReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(cmdReceiver) } catch (_: Exception) {}
        stopAlarmPlayback()
    }
}
