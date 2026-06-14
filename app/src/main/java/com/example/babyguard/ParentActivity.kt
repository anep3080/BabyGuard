package com.example.babyguard

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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

    // ── Views ─────────────────────────────────────────────────────────────────
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

    private lateinit var switchMasterAlert: SwitchCompat
    private lateinit var switchMediumAlert: SwitchCompat
    private lateinit var switchLowAlert: SwitchCompat
    private lateinit var switchQuietHours: SwitchCompat
    private lateinit var tvQuietStart: TextView
    private lateinit var tvQuietEnd: TextView

    private lateinit var alertOverlay: FrameLayout
    private lateinit var tvAlertMessage: TextView
    private lateinit var btnDismissAlert: Button

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var prefs: AppPreferences
    private lateinit var dbHelper: AlertDatabaseHelper
    private val recorder by lazy { VideoRecorder(this) }
    private var recorderStarted = false

    private var activeRingtone: Ringtone? = null
    private var latestStreamFrame: Bitmap? = null
    private var isRecording = false
    private var lastAcknowledgeTime = 0L
    private var currentMotionLevel = 0f
    private var currentSoundLevel = 0f

    private var isVideoPlaying = false
    private var videoServerSocket: ServerSocket? = null
    private var activeVideoClient: Socket? = null
    private var videoServerThread: Thread? = null

    private var isMicListening = false
    private var audioServerThread: Thread? = null
    private var audioServerSocket: ServerSocket? = null
    private var audioClient: Socket? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val motionDecayRunnable = object : Runnable {
        override fun run() {
            if (currentMotionLevel > 0) {
                currentMotionLevel *= 0.94f
                if (currentMotionLevel < 1) currentMotionLevel = 0f
                motionMeter.progress = currentMotionLevel.toInt()
            }
            if (currentSoundLevel > 0) {
                currentSoundLevel *= 0.92f
                if (currentSoundLevel < 1) currentSoundLevel = 0f
                soundMeter.progress = currentSoundLevel.toInt()
            }
            handler.postDelayed(this, 30)
        }
    }

    // ── Telemetry receiver ─────────────────────────────────────────────────────
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonString = intent?.getStringExtra("payload") ?: return
            try {
                val json   = org.json.JSONObject(jsonString)
                val status = json.optString("status", "---")
                val mood   = json.optString("mood", "Calm")
                val posture = json.optString("posture", "Safe")
                val tier   = json.optString("tier", "LOW")
                val action = json.optString("event_action", "Normal")
                val image  = json.optString("image", "")

                tvInsightMood.text    = mood
                tvInsightPosture.text = posture

                val newMotion = json.optInt("motion_level", 0).toFloat()
                if (newMotion > currentMotionLevel) currentMotionLevel = newMotion
                val newSound = json.optInt("sound_level", 0).toFloat()
                if (newSound > currentSoundLevel) currentSoundLevel = newSound

                val masterOn = switchMasterAlert.isChecked
                val canTriggerHigh = System.currentTimeMillis() - lastAcknowledgeTime > 60_000

                if (masterOn) {
                    when (tier) {
                        "HIGH"   -> if (canTriggerHigh)                  triggerHighAlert(action, status)
                        "MEDIUM" -> if (switchMediumAlert.isChecked)     triggerMediumAlert(status)
                    }
                }

                val shouldLog = when (tier) {
                    "HIGH"   -> true
                    "MEDIUM" -> switchMediumAlert.isChecked
                    "LOW"    -> switchLowAlert.isChecked
                    else     -> false
                }
                if (shouldLog && (image.isNotEmpty() || tier != "LOW")) {
                    val ts = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(java.util.Date())
                    dbHelper.saveAlert(ts, status, image, tier, action)
                    loadHistoryToUI()
                }

                val battery = json.optInt("battery", -1)
                if (battery != -1) {
                    llBabyBattery.visibility = View.VISIBLE
                    pbBabyBattery.progress   = battery
                    tvBabyBatteryPct.text    = " $battery%"
                }

                if (pairingLayout.visibility == View.VISIBLE) {
                    pairingLayout.visibility  = View.GONE
                    dashboardLayout.visibility = View.VISIBLE
                    tvSubtitle.text = "🟢 Connected Securely"
                }
            } catch (_: Exception) {}
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)
        stopService(Intent(this, BabyGuardService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, BabyGuardService::class.java))

        prefs    = AppPreferences(this)
        dbHelper = AlertDatabaseHelper(this)

        bindViews()
        restoreSettings()
        setupListeners()

        createNotificationChannel()
        generateLanQrCode()
        loadHistoryToUI()
        handler.post(motionDecayRunnable)
    }

    private fun bindViews() {
        tvSubtitle      = findViewById(R.id.tvSubtitle)
        pairingLayout   = findViewById(R.id.pairingLayout)
        ivQrCode        = findViewById(R.id.ivQrCode)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        svLiveVideo     = findViewById(R.id.svLiveVideo)
        llAlertHistory  = findViewById(R.id.llAlertHistory)
        tvEmptyLog      = findViewById(R.id.tvEmptyLog)
        motionMeter     = findViewById(R.id.motionMeter)
        soundMeter      = findViewById(R.id.soundMeter)
        llBabyBattery   = findViewById(R.id.llBabyBattery)
        pbBabyBattery   = findViewById(R.id.pbBabyBattery)
        tvBabyBatteryPct = findViewById(R.id.tvBabyBatteryPct)
        fabMic          = findViewById(R.id.fabMic)
        cardVideo       = findViewById(R.id.cardVideo)
        cardSettings    = findViewById(R.id.cardSettings)
        tvInsightMood   = findViewById(R.id.tvInsightMood)
        tvInsightPosture = findViewById(R.id.tvInsightPosture)

        switchMasterAlert = findViewById(R.id.switchMasterAlert)
        switchMediumAlert = findViewById(R.id.switchMediumAlert)
        switchLowAlert    = findViewById(R.id.switchLowAlert)
        switchQuietHours  = findViewById(R.id.switchQuietHours)
        tvQuietStart      = findViewById(R.id.tvQuietStart)
        tvQuietEnd        = findViewById(R.id.tvQuietEnd)

        alertOverlay    = findViewById(R.id.alertOverlay)
        tvAlertMessage  = findViewById(R.id.tvAlertMessage)
        btnDismissAlert = findViewById(R.id.btnDismissAlert)
    }

    // ── Settings persistence ───────────────────────────────────────────────────

    private fun restoreSettings() {
        switchMasterAlert.isChecked = prefs.masterAlertEnabled
        switchMediumAlert.isChecked = prefs.mediumAlertEnabled
        switchLowAlert.isChecked    = prefs.lowAlertEnabled
        switchQuietHours.isChecked  = prefs.quietHoursEnabled
        tvQuietStart.text = formatHour(prefs.quietHoursStart)
        tvQuietEnd.text   = formatHour(prefs.quietHoursEnd)
    }

    private fun setupListeners() {
        btnDismissAlert.setOnClickListener { dismissActiveAlert() }

        switchMasterAlert.setOnCheckedChangeListener { _, v -> prefs.masterAlertEnabled = v }
        switchMediumAlert.setOnCheckedChangeListener { _, v -> prefs.mediumAlertEnabled = v }
        switchLowAlert.setOnCheckedChangeListener    { _, v -> prefs.lowAlertEnabled    = v }
        switchQuietHours.setOnCheckedChangeListener  { _, v -> prefs.quietHoursEnabled  = v }

        // Quiet hours time pickers
        tvQuietStart.setOnClickListener {
            TimePickerDialog(this, { _, h, _ ->
                prefs.quietHoursStart = h
                tvQuietStart.text = formatHour(h)
            }, prefs.quietHoursStart, 0, true).show()
        }
        tvQuietEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, _ ->
                prefs.quietHoursEnd = h
                tvQuietEnd.text = formatHour(h)
            }, prefs.quietHoursEnd, 0, true).show()
        }

        // FABs
        findViewById<FloatingActionButton>(R.id.fabVideo).setOnClickListener {
            showCard(cardVideo, R.anim.slide_down); startVideoServer()
        }
        findViewById<FloatingActionButton>(R.id.fabSettings).setOnClickListener {
            showCard(cardSettings, R.anim.slide_in_right)
        }
        fabMic.setOnClickListener { if (isMicListening) stopAudioListening() else startAudioListening() }

        // Toolbar buttons
        findViewById<ImageButton>(R.id.btnCloseVideo).setOnClickListener {
            hideCard(cardVideo, R.anim.slide_out_up); stopVideoServer()
        }
        findViewById<ImageButton>(R.id.btnCloseSettings).setOnClickListener {
            hideCard(cardSettings, R.anim.slide_out_right)
        }
        findViewById<ImageButton>(R.id.btnMainBack).setOnClickListener { finish() }

        // Settings actions
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            dbHelper.clearAllAlerts(); loadHistoryToUI()
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
        }

        // Video panel actions
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { captureCurrentFrame() }
        findViewById<ImageButton>(R.id.btnRecord).setOnClickListener { toggleStreamRecording() }
    }

    private fun formatHour(h: Int) = String.format(Locale.getDefault(), "%02d:00", h)

    // ── Photo / Video capture ──────────────────────────────────────────────────

    private fun captureCurrentFrame() {
        val frame = latestStreamFrame ?: return
        val filename = "BabyGuard_Capture_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/BabyGuard")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    frame.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentResolver.update(uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null, null)
                }
                Toast.makeText(this, "Saved to Gallery (/Pictures/BabyGuard)",
                    Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleStreamRecording() {
        isRecording = !isRecording
        val btn = findViewById<ImageButton>(R.id.btnRecord)
        if (isRecording) {
            recorderStarted = false        // let startVideoServer loop call recorder.start()
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } else {
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#80000000"))
            if (recorderStarted) {
                Thread { recorder.stop() }.start()   // stop off main thread (encoder drain)
                recorderStarted = false
            }
            Toast.makeText(this, "Recording saved to Gallery", Toast.LENGTH_LONG).show()
        }
    }

    // ── Alert handling ─────────────────────────────────────────────────────────

    private fun triggerHighAlert(action: String, status: String) {
        if (alertOverlay.visibility == View.VISIBLE) return
        runOnUiThread {
            alertOverlay.visibility = View.VISIBLE
            tvAlertMessage.text = "$action: $status"
            val blink = AlphaAnimation(1.0f, 0.2f).apply {
                duration = 400; repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            alertOverlay.startAnimation(blink)
        }
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            .vibrate(longArrayOf(0, 500, 250, 500), 0)
        activeRingtone = RingtoneManager.getRingtone(
            this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).apply { play() }
        showSystemNotification("🚨 CRITICAL ALERT", "$action - Check Baby!", 2)
    }

    private fun triggerMediumAlert(status: String) {
        // Suppress medium alerts during quiet hours
        if (prefs.quietHoursEnabled && prefs.isQuietHoursActive()) {
            Log.d("BabyGuard", "Medium alert suppressed by quiet hours: $status")
            return
        }
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(500)
        showSystemNotification("Baby Update", status, 1)
    }

    private fun dismissActiveAlert() {
        lastAcknowledgeTime = System.currentTimeMillis()
        runOnUiThread { alertOverlay.visibility = View.GONE; alertOverlay.clearAnimation() }
        activeRingtone?.stop()
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
    }

    private fun showSystemNotification(title: String, message: String, priority: Int) {
        val pending = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, ParentActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, "BABYGUARD_ALERTS")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(if (priority == 2) 2 else 0)
            .setAutoCancel(true)
            .setContentIntent(pending)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(priority, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel("BABYGUARD_ALERTS", "Alerts",
                        NotificationManager.IMPORTANCE_HIGH))
        }
    }

    // ── Card animations ────────────────────────────────────────────────────────

    private fun showCard(card: View, animRes: Int) {
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE
            card.startAnimation(AnimationUtils.loadAnimation(this, animRes))
        }
    }

    private fun hideCard(card: View, animRes: Int) {
        if (card.visibility != View.VISIBLE) return
        val anim = AnimationUtils.loadAnimation(this, animRes)
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(a: Animation?) {}
            override fun onAnimationRepeat(a: Animation?) {}
            override fun onAnimationEnd(a: Animation?) { card.visibility = View.GONE }
        })
        card.startAnimation(anim)
    }

    // ── History log ────────────────────────────────────────────────────────────

    private fun loadHistoryToUI() {
        llAlertHistory.removeAllViews()
        val alerts = dbHelper.getAllAlerts()
        if (alerts.isEmpty()) {
            tvEmptyLog.visibility = View.VISIBLE
            llAlertHistory.addView(tvEmptyLog)
            return
        }
        tvEmptyLog.visibility = View.GONE
        for (alert in alerts) {
            val card = androidx.cardview.widget.CardView(this).apply {
                radius = 24f; setCardBackgroundColor(Color.WHITE); cardElevation = 4f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 24) }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(32, 32, 32, 32)
            }
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val color = when (alert.tier) {
                "HIGH"   -> "#E53935"
                "MEDIUM" -> "#FB8C00"
                else     -> "#43A047"
            }
            textLayout.addView(TextView(this).apply {
                text = "${if (alert.tier == "HIGH") "🚨" else "🔔"} ${alert.timestamp}"
                setTextColor(Color.parseColor(color)); textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            textLayout.addView(TextView(this).apply {
                text = "${alert.eventAction}: ${alert.status}"
                setTextColor(Color.parseColor("#555555")); textSize = 14f
                setPadding(0, 8, 0, 0)
            })
            content.addView(textLayout)
            if (alert.imageBase64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(alert.imageBase64, 0)
                    val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val iv = ImageView(this).apply {
                        setImageBitmap(bmp)
                        layoutParams = LinearLayout.LayoutParams(180, 180)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    val ic = androidx.cardview.widget.CardView(this).apply {
                        radius = 16f; addView(iv)
                    }
                    content.addView(ic)
                } catch (_: Exception) {}
            }
            card.addView(content)
            llAlertHistory.addView(card)
        }
    }

    // ── Video server ───────────────────────────────────────────────────────────

    private fun startVideoServer() {
        if (isVideoPlaying) return
        isVideoPlaying = true
        videoServerThread = Thread {
            try {
                videoServerSocket = ServerSocket(8889).apply { reuseAddress = true }
                while (isVideoPlaying) {
                    activeVideoClient = videoServerSocket!!.accept()
                    val dis = DataInputStream(
                        java.io.BufferedInputStream(activeVideoClient!!.inputStream, 65536))
                    val opt = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565; inMutable = true
                    }
                    var reusable: Bitmap? = null

                    while (isVideoPlaying && !activeVideoClient!!.isClosed) {
                        try {
                            val size = dis.readInt()
                            if (size in 1..2_000_000) {
                                val bytes = ByteArray(size); dis.readFully(bytes)
                                if (reusable != null) opt.inBitmap = reusable
                                val bmp = try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                                } catch (_: Exception) {
                                    reusable = null
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                                }
                                if (bmp != null) {
                                    reusable = bmp
                                    latestStreamFrame = bmp

                                    // Draw frame to SurfaceView
                                    val holder = svLiveVideo.holder
                                    val canvas  = holder.lockCanvas()
                                    if (canvas != null) {
                                        canvas.drawColor(Color.BLACK)
                                        canvas.drawBitmap(bmp, null,
                                            android.graphics.Rect(0, 0,
                                                svLiveVideo.width, svLiveVideo.height), null)
                                        holder.unlockCanvasAndPost(canvas)
                                    }

                                    // ── VideoRecorder integration ────────────────
                                    if (isRecording) {
                                        if (!recorderStarted) {
                                            recorder.start(bmp.width, bmp.height)
                                            recorderStarted = true
                                        }
                                        recorder.encodeFrame(bmp)
                                    }
                                }
                            }
                        } catch (_: Exception) { break }
                    }
                }
            } catch (_: Exception) {}
        }.apply { start() }
    }

    private fun stopVideoServer() {
        // If still recording, stop the recorder cleanly before closing the stream
        if (isRecording && recorderStarted) {
            isRecording = false
            recorderStarted = false
            Thread { recorder.stop() }.start()
            runOnUiThread {
                val btn = findViewById<ImageButton>(R.id.btnRecord)
                btn?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#80000000"))
            }
        }
        isVideoPlaying = false
        activeVideoClient?.close(); videoServerSocket?.close()
        activeVideoClient = null; videoServerSocket = null
    }

    // ── Audio listening ────────────────────────────────────────────────────────

    private fun startAudioListening() {
        if (isMicListening) return
        isMicListening = true
        fabMic.imageTintList =
            android.content.res.ColorStateList.valueOf(Color.RED)
        audioServerThread = Thread {
            try {
                audioServerSocket = ServerSocket(8890).apply { reuseAddress = true }
                while (isMicListening) {
                    audioClient = audioServerSocket!!.accept()
                    val dis = DataInputStream(audioClient!!.inputStream)
                    val bSize = android.media.AudioTrack.getMinBufferSize(16000, 4, 2)
                    val track = android.media.AudioTrack(3, 16000, 4, 2, bSize, 1).apply { play() }
                    val buf = ShortArray(bSize)
                    while (isMicListening && !audioClient!!.isClosed) {
                        try {
                            for (i in buf.indices) buf[i] = dis.readShort()
                            track.write(buf, 0, buf.size)
                        } catch (_: Exception) { break }
                    }
                    track.stop(); track.release()
                }
            } catch (_: Exception) {}
        }.apply { start() }
    }

    private fun stopAudioListening() {
        isMicListening = false
        fabMic.imageTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#888888"))
        audioClient?.close(); audioServerSocket?.close()
        audioClient = null; audioServerSocket = null
    }

    // ── QR code ────────────────────────────────────────────────────────────────

    private fun generateLanQrCode() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip   = wifi.connectionInfo.ipAddress
            if (ip == 0) return
            showQrCode("LAN:" + String.format(Locale.US,
                "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff,
                ip shr 16 and 0xff, ip shr 24 and 0xff))
        } catch (_: Exception) {}
    }

    private fun showQrCode(tag: String) {
        runOnUiThread {
            try {
                val bit = QRCodeWriter().encode(tag, BarcodeFormat.QR_CODE, 512, 512)
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
                for (x in 0 until 512) for (y in 0 until 512)
                    bmp.setPixel(x, y, if (bit.get(x, y)) Color.BLACK else Color.WHITE)
                ivQrCode.setImageBitmap(bmp)
            } catch (_: Exception) {}
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("BABYGUARD_NEW_DATA")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(dataReceiver, filter)
        loadHistoryToUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
        stopVideoServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoServer()
        handler.removeCallbacks(motionDecayRunnable)
        activeRingtone?.stop()
    }

    override fun onBackPressed() {
        when {
            alertOverlay.visibility == View.VISIBLE  -> dismissActiveAlert()
            cardVideo.visibility    == View.VISIBLE  -> {
                hideCard(cardVideo, R.anim.slide_out_up); stopVideoServer()
            }
            cardSettings.visibility == View.VISIBLE  -> hideCard(cardSettings, R.anim.slide_out_right)
            else -> super.onBackPressed()
        }
    }
}
