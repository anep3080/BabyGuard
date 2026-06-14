package com.example.babyguard

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import soup.neumorphism.NeumorphCardView
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

@SuppressLint("SetTextI18n")
class ParentActivity : AppCompatActivity() {
    private lateinit var tvSubtitle: TextView
    private lateinit var pairingLayout: View
    private lateinit var ivQrCode: ImageView
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var svLiveVideo: SurfaceView
    private lateinit var llAlertHistory: LinearLayout
    private lateinit var tvEmptyLog: TextView
    private lateinit var motionMeter: SeekBar
    private lateinit var soundMeter: SeekBar
    private lateinit var llBabyBattery: LinearLayout
    private lateinit var pbBabyBattery: ProgressBar
    private lateinit var tvBabyBatteryPct: TextView
    private lateinit var fabMic: FloatingActionButton
    private lateinit var cardVideo: NeumorphCardView
    private lateinit var cardSettings: androidx.cardview.widget.CardView
    private lateinit var tvInsightMood: TextView
    private lateinit var tvInsightPosture: TextView

    private lateinit var alertOverlay: FrameLayout
    private lateinit var tvAlertMessage: TextView
    private lateinit var btnDismissAlert: Button
    private var activeRingtone: Ringtone? = null

    private var lastAcknowledgeTime = 0L
    private var currentMotionLevel = 0f
    private var currentSoundLevel = 0f
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    private val motionDecayRunnable = object : Runnable {
        override fun run() {
            if (currentMotionLevel > 0) { currentMotionLevel *= 0.94f; if (currentMotionLevel < 1) currentMotionLevel = 0f; motionMeter.progress = currentMotionLevel.toInt() }
            if (currentSoundLevel > 0) { currentSoundLevel *= 0.92f; if (currentSoundLevel < 1) currentSoundLevel = 0f; soundMeter.progress = currentSoundLevel.toInt() }
            handler.postDelayed(this, 30)
        }
    }

    private lateinit var dbHelper: AlertDatabaseHelper
    private var isVideoPlaying = false
    private var videoServerSocket: ServerSocket? = null
    private var activeVideoClient: Socket? = null
    private var videoServerThread: Thread? = null

    private var isMicListening = false
    private var audioServerThread: Thread? = null
    private var audioServerSocket: ServerSocket? = null
    private var audioClient: Socket? = null

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonString = intent?.getStringExtra("payload") ?: return
            try {
                val jsonObject = org.json.JSONObject(jsonString)
                val status = jsonObject.optString("status", "---")
                val mood = jsonObject.optString("mood", "Calm")
                val posture = jsonObject.optString("posture", "Safe")
                val tier = jsonObject.optString("tier", "LOW")
                val action = jsonObject.optString("event_action", "Normal")
                val image = jsonObject.optString("image", "")

                tvInsightMood.text = mood; tvInsightPosture.text = posture
                
                val newMotion = jsonObject.optInt("motion_level", 0).toFloat()
                if (newMotion > currentMotionLevel) currentMotionLevel = newMotion
                val newSound = jsonObject.optInt("sound_level", 0).toFloat()
                if (newSound > currentSoundLevel) currentSoundLevel = newSound

                // 8.5 SMART ALERTING: Ignore HIGH alerts for 60s after acknowledgment
                val canTriggerAlert = System.currentTimeMillis() - lastAcknowledgeTime > 60000
                if (tier == "HIGH" && canTriggerAlert) { triggerHighAlert(action, status) } 
                else if (tier == "MEDIUM") { triggerMediumAlert(status) }

                if (image.isNotEmpty()) {
                    val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
                    dbHelper.saveAlert(timestamp, status, image, tier, action); loadHistoryToUI()
                }

                val battery = jsonObject.optInt("battery", -1)
                if (battery != -1) { llBabyBattery.visibility = View.VISIBLE; pbBabyBattery.progress = battery; tvBabyBatteryPct.text = " $battery%" }

                if (pairingLayout.visibility == View.VISIBLE) { pairingLayout.visibility = View.GONE; dashboardLayout.visibility = View.VISIBLE; tvSubtitle.text = "🟢 Connected Securely" }
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)
        stopService(Intent(this, BabyGuardService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, BabyGuardService::class.java))

        tvSubtitle = findViewById(R.id.tvSubtitle); pairingLayout = findViewById(R.id.pairingLayout)
        ivQrCode = findViewById(R.id.ivQrCode); dashboardLayout = findViewById(R.id.dashboardLayout)
        svLiveVideo = findViewById(R.id.svLiveVideo); llAlertHistory = findViewById(R.id.llAlertHistory)
        tvEmptyLog = findViewById(R.id.tvEmptyLog); motionMeter = findViewById(R.id.motionMeter)
        soundMeter = findViewById(R.id.soundMeter); llBabyBattery = findViewById(R.id.llBabyBattery)
        pbBabyBattery = findViewById(R.id.pbBabyBattery); tvBabyBatteryPct = findViewById(R.id.tvBabyBatteryPct)
        fabMic = findViewById(R.id.fabMic); cardVideo = findViewById(R.id.cardVideo)
        cardSettings = findViewById(R.id.cardSettings); tvInsightMood = findViewById(R.id.tvInsightMood)
        tvInsightPosture = findViewById(R.id.tvInsightPosture)
        alertOverlay = findViewById(R.id.alertOverlay); tvAlertMessage = findViewById(R.id.tvAlertMessage)
        btnDismissAlert = findViewById(R.id.btnDismissAlert); btnDismissAlert.setOnClickListener { dismissActiveAlert() }

        dbHelper = AlertDatabaseHelper(this)
        findViewById<FloatingActionButton>(R.id.fabVideo).setOnClickListener { showCard(cardVideo, R.anim.slide_down); startVideoServer() }
        findViewById<FloatingActionButton>(R.id.fabSettings).setOnClickListener { showCard(cardSettings, R.anim.slide_in_right) }
        fabMic.setOnClickListener { if (isMicListening) stopAudioListening() else startAudioListening() }
        findViewById<ImageButton>(R.id.btnCloseVideo).setOnClickListener { hideCard(cardVideo, R.anim.slide_out_up); stopVideoServer() }
        findViewById<ImageButton>(R.id.btnCloseSettings).setOnClickListener { hideCard(cardSettings, R.anim.slide_out_right) }
        findViewById<ImageButton>(R.id.btnMainBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { dbHelper.clearAllAlerts(); loadHistoryToUI(); Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show() }

        createNotificationChannel(); generateLanQrCode(); loadHistoryToUI(); handler.post(motionDecayRunnable)
    }

    override fun onResume() { 
        super.onResume()
        val filter = IntentFilter("BABYGUARD_NEW_DATA")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(dataReceiver, filter)
        loadHistoryToUI() 
    }

    override fun onPause() { super.onPause(); unregisterReceiver(dataReceiver); stopVideoServer() }

    private fun showCard(card: View, animRes: Int) { if (card.visibility != View.VISIBLE) { card.visibility = View.VISIBLE; card.startAnimation(AnimationUtils.loadAnimation(this, animRes)) } }
    private fun hideCard(card: View, animRes: Int) {
        if (card.visibility != View.VISIBLE) return
        val anim = AnimationUtils.loadAnimation(this, animRes); anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(a: Animation?) {}
            override fun onAnimationRepeat(a: Animation?) {}
            override fun onAnimationEnd(a: Animation?) { card.visibility = View.GONE }
        })
        card.startAnimation(anim)
    }

    private fun triggerHighAlert(action: String, status: String) {
        if (alertOverlay.visibility == View.VISIBLE) return
        runOnUiThread {
            alertOverlay.visibility = View.VISIBLE; tvAlertMessage.text = "$action: $status"
            val blink = AlphaAnimation(1.0f, 0.2f).apply { duration = 400; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
            alertOverlay.startAnimation(blink)
        }
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator; vibrator.vibrate(longArrayOf(0, 500, 250, 500), 0)
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM); activeRingtone = RingtoneManager.getRingtone(this, alarmUri).apply { play() }
        showSystemNotification("🚨 CRITICAL ALERT", "$action - Check Baby!", 2)
    }

    private fun triggerMediumAlert(status: String) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator; vibrator.vibrate(500)
        showSystemNotification("Baby Update", status, 1)
    }

    private fun dismissActiveAlert() {
        lastAcknowledgeTime = System.currentTimeMillis()
        runOnUiThread { alertOverlay.visibility = View.GONE; alertOverlay.clearAnimation() }
        activeRingtone?.stop(); (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    private fun showSystemNotification(title: String, message: String, priority: Int) {
        val intent = Intent(this, ParentActivity::class.java); val pending = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "BABYGUARD_ALERTS").setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(message).setPriority(if (priority == 2) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).setContentIntent(pending)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(priority, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("BABYGUARD_ALERTS", "Alerts", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun loadHistoryToUI() {
        llAlertHistory.removeAllViews()
        val alerts = dbHelper.getAllAlerts()
        if (alerts.isEmpty()) { tvEmptyLog.visibility = View.VISIBLE; llAlertHistory.addView(tvEmptyLog) }
        else {
            tvEmptyLog.visibility = View.GONE
            for (alert in alerts) {
                val card = androidx.cardview.widget.CardView(this).apply { radius = 24f; setCardBackgroundColor(Color.WHITE); cardElevation = 4f; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 24) } }
                val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(32, 32, 32, 32) }
                val textLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
                val color = when(alert.tier) { "HIGH" -> "#E53935"; "MEDIUM" -> "#FB8C00"; else -> "#43A047" }
                textLayout.addView(TextView(this).apply { text = "${if(alert.tier == "HIGH") "🚨" else "🔔"} ${alert.timestamp}"; setTextColor(Color.parseColor(color)); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
                textLayout.addView(TextView(this).apply { text = "${alert.eventAction}: ${alert.status}"; setTextColor(Color.parseColor("#555555")); textSize = 14f; setPadding(0, 8, 0, 0) })
                content.addView(textLayout)
                if (alert.imageBase64.isNotEmpty()) {
                    try { val bytes = android.util.Base64.decode(alert.imageBase64, 0); val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); val iv = ImageView(this).apply { setImageBitmap(bmp); layoutParams = LinearLayout.LayoutParams(180, 180); scaleType = ImageView.ScaleType.CENTER_CROP }; val ic = androidx.cardview.widget.CardView(this).apply { radius = 16f; addView(iv) }; content.addView(ic) } catch (_: Exception) {}
                }
                card.addView(content); llAlertHistory.addView(card)
            }
        }
    }

    private fun startVideoServer() {
        if (isVideoPlaying) return
        isVideoPlaying = true
        videoServerThread = Thread {
            try {
                videoServerSocket = ServerSocket(8889).apply { reuseAddress = true }
                while (isVideoPlaying) {
                    activeVideoClient = videoServerSocket!!.accept(); val dis = DataInputStream(java.io.BufferedInputStream(activeVideoClient!!.inputStream, 65536))
                    val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true }; var reusable: Bitmap? = null
                    while (isVideoPlaying && !activeVideoClient!!.isClosed) {
                        try {
                            val size = dis.readInt()
                            if (size in 1..2000000) {
                                val bytes = ByteArray(size); dis.readFully(bytes)
                                if (reusable != null) opt.inBitmap = reusable
                                val bmp = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) } catch (_: Exception) { reusable = null; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) }
                                if (bmp != null) { reusable = bmp; val holder = svLiveVideo.holder; val canvas = holder.lockCanvas(); if (canvas != null) { canvas.drawColor(Color.BLACK); canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, svLiveVideo.width, svLiveVideo.height), null); holder.unlockCanvasAndPost(canvas) } }
                            }
                        } catch (_: Exception) { break }
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }
    }

    private fun stopVideoServer() { isVideoPlaying = false; activeVideoClient?.close(); videoServerSocket?.close(); activeVideoClient = null; videoServerSocket = null }

    private fun startAudioListening() {
        if (isMicListening) return
        isMicListening = true; fabMic.imageTintList = android.content.res.ColorStateList.valueOf(Color.RED)
        audioServerThread = Thread {
            try {
                audioServerSocket = ServerSocket(8890).apply { reuseAddress = true }
                while (isMicListening) {
                    audioClient = audioServerSocket!!.accept(); val dis = DataInputStream(audioClient!!.inputStream)
                    val bSize = android.media.AudioTrack.getMinBufferSize(16000, 4, 2); val track = android.media.AudioTrack(3, 16000, 4, 2, bSize, 1).apply { play() }
                    val buf = ShortArray(bSize)
                    while (isMicListening && !audioClient!!.isClosed) { try { for (i in buf.indices) buf[i] = dis.readShort(); track.write(buf, 0, buf.size) } catch (_: Exception) { break } }
                    track.stop(); track.release()
                }
            } catch (_: Exception) {}
        }.apply { start() }
    }

    private fun stopAudioListening() { isMicListening = false; fabMic.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#888888")); audioClient?.close(); audioServerSocket?.close(); audioClient = null; audioServerSocket = null }

    @Suppress("DEPRECATION")
    private fun generateLanQrCode() {
        try { val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; val ip = wifi.connectionInfo.ipAddress; if (ip == 0) return; showQrCode("LAN:" + String.format(Locale.US, "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)) } catch (_: Exception) {}
    }

    private fun showQrCode(tag: String) {
        runOnUiThread { 
            try { 
                val bit = QRCodeWriter().encode(tag, BarcodeFormat.QR_CODE, 512, 512)
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
                for (x in 0 until 512) {
                    for (y in 0 until 512) {
                        bmp.setPixel(x, y, if (bit.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
                ivQrCode.setImageBitmap(bmp) 
            } catch (_: Exception) {} 
        }
    }

    override fun onDestroy() { super.onDestroy(); stopVideoServer(); handler.removeCallbacks(motionDecayRunnable); activeRingtone?.stop() }
    override fun onBackPressed() { if (alertOverlay.visibility == View.VISIBLE) dismissActiveAlert() else if (cardVideo.visibility == View.VISIBLE) { hideCard(cardVideo, R.anim.slide_out_up); stopVideoServer() } else if (cardSettings.visibility == View.VISIBLE) hideCard(cardSettings, R.anim.slide_out_right) else super.onBackPressed() }
}