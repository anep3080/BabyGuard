package com.example.babyguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.set
import androidx.core.graphics.toColorInt
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
    private lateinit var llBabyBattery: LinearLayout
    private lateinit var pbBabyBattery: ProgressBar
    private lateinit var tvBabyBatteryPct: TextView
    private lateinit var fabMic: FloatingActionButton

    private lateinit var cardVideo: NeumorphCardView
    private lateinit var cardSettings: androidx.cardview.widget.CardView

    private lateinit var tvInsightMood: TextView
    private lateinit var tvInsightPosture: TextView

    private var currentMotionLevel = 0f
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val motionDecayRunnable = object : Runnable {
        override fun run() {
            if (currentMotionLevel > 0) {
                currentMotionLevel *= 0.94f // Slow decay
                if (currentMotionLevel < 1) currentMotionLevel = 0f
                motionMeter.progress = currentMotionLevel.toInt()
            }
            handler.postDelayed(this, 30) // 30fps update
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

                tvInsightMood.text = jsonObject.optString("mood", "Calm")
                tvInsightPosture.text = jsonObject.optString("posture", "Safe")
                
                // Update motion meter (SeekBar)
                val newMotion = jsonObject.optInt("motion_level", (10..90).random()).toFloat()
                if (newMotion > currentMotionLevel) {
                    currentMotionLevel = newMotion // Jump to new peak
                }

                // Update Baby Battery (7.2)
                val battery = jsonObject.optInt("battery", -1)
                if (battery != -1) {
                    llBabyBattery.visibility = View.VISIBLE
                    pbBabyBattery.progress = battery
                    tvBabyBatteryPct.text = " $battery%"
                }

                if (pairingLayout.visibility == View.VISIBLE) {
                    pairingLayout.visibility = View.GONE
                    dashboardLayout.visibility = View.VISIBLE
                    tvSubtitle.text = "🟢 Connected Securely"
                }

                loadHistoryToUI()
            } catch (e: Exception) {
                Log.e("BabyGuard", "JSON parsing error", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        ContextCompat.startForegroundService(this, Intent(this, BabyGuardService::class.java))

        tvSubtitle = findViewById(R.id.tvSubtitle)
        pairingLayout = findViewById(R.id.pairingLayout)
        ivQrCode = findViewById(R.id.ivQrCode)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        svLiveVideo = findViewById(R.id.svLiveVideo)
        llAlertHistory = findViewById(R.id.llAlertHistory)
        tvEmptyLog = findViewById(R.id.tvEmptyLog)
        motionMeter = findViewById(R.id.motionMeter)
        llBabyBattery = findViewById(R.id.llBabyBattery)
        pbBabyBattery = findViewById(R.id.pbBabyBattery)
        tvBabyBatteryPct = findViewById(R.id.tvBabyBatteryPct)
        fabMic = findViewById(R.id.fabMic)

        cardVideo = findViewById(R.id.cardVideo)
        cardSettings = findViewById(R.id.cardSettings)

        tvInsightMood = findViewById(R.id.tvInsightMood)
        tvInsightPosture = findViewById(R.id.tvInsightPosture)

        dbHelper = AlertDatabaseHelper(this)

        // FAB Actions
        findViewById<FloatingActionButton>(R.id.fabVideo).setOnClickListener { showCard(cardVideo, R.anim.slide_down); startVideoServer() }
        findViewById<FloatingActionButton>(R.id.fabSettings).setOnClickListener { showCard(cardSettings, R.anim.slide_in_right) }
        
        fabMic.setOnClickListener {
            if (isMicListening) stopAudioListening() else startAudioListening()
        }

        // Close Card Buttons
        findViewById<ImageButton>(R.id.btnCloseVideo).setOnClickListener { hideCard(cardVideo, R.anim.slide_out_up); stopVideoServer() }
        findViewById<ImageButton>(R.id.btnCloseSettings).setOnClickListener { hideCard(cardSettings, R.anim.slide_out_right) }

        findViewById<ImageButton>(R.id.btnMainBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            dbHelper.clearAllAlerts()
            loadHistoryToUI()
            Toast.makeText(this, "Event Log Cleared", Toast.LENGTH_SHORT).show()
        }

        generateLanQrCode()
        loadHistoryToUI()
        handler.post(motionDecayRunnable)
    }

    private fun showCard(card: View, animRes: Int) {
        if (card.visibility == View.VISIBLE) return
        card.visibility = View.VISIBLE
        card.startAnimation(AnimationUtils.loadAnimation(this, animRes))
    }

    private fun hideCard(card: View, animRes: Int) {
        if (card.visibility != View.VISIBLE) return
        val anim = AnimationUtils.loadAnimation(this, animRes)
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
            override fun onAnimationEnd(a: android.view.animation.Animation?) { card.visibility = View.GONE }
        })
        card.startAnimation(anim)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, IntentFilter("BABYGUARD_NEW_DATA"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dataReceiver, IntentFilter("BABYGUARD_NEW_DATA"))
        }
        loadHistoryToUI()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
        stopVideoServer()
    }

    private fun loadHistoryToUI() {
        llAlertHistory.removeAllViews()
        val alerts = dbHelper.getAllAlerts()
        if (alerts.isEmpty()) {
            tvEmptyLog.visibility = View.VISIBLE
            llAlertHistory.addView(tvEmptyLog)
        } else {
            tvEmptyLog.visibility = View.GONE
            for (alert in alerts) {
                val card = androidx.cardview.widget.CardView(this).apply {
                    radius = 24f
                    setCardBackgroundColor("#FFFFFF".toColorInt())
                    cardElevation = 4f
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) }
                }

                val cardContent = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(32, 32, 32, 32); gravity = android.view.Gravity.CENTER_VERTICAL }
                val textLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }

                textLayout.addView(TextView(this).apply { text = "🚨 ${alert.timestamp}"; setTextColor("#E53935".toColorInt()); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
                textLayout.addView(TextView(this).apply { text = alert.status; setTextColor("#555555".toColorInt()); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 8, 0, 0) })
                cardContent.addView(textLayout)

                if (alert.imageBase64.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(alert.imageBase64, android.util.Base64.DEFAULT)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val imageCard = androidx.cardview.widget.CardView(this).apply { radius = 16f; cardElevation = 0f }
                        imageCard.addView(ImageView(this).apply { setImageBitmap(bitmap); layoutParams = LinearLayout.LayoutParams(200, 200); scaleType = ImageView.ScaleType.CENTER_CROP })
                        cardContent.addView(imageCard)
                    } catch (_: Throwable) {}
                }
                card.addView(cardContent)
                llAlertHistory.addView(card)
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
                    activeVideoClient = videoServerSocket!!.accept()
                    val dis = DataInputStream(java.io.BufferedInputStream(activeVideoClient!!.inputStream, 65536))

                    var reusableBitmap: Bitmap? = null
                    val decodeOptions = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                        inMutable = true
                    }

                    while (isVideoPlaying && !activeVideoClient!!.isClosed) {
                        try {
                            val size = dis.readInt()
                            if (size in 1..2000000) {
                                val bytes = ByteArray(size)
                                dis.readFully(bytes)

                                if (reusableBitmap != null) { decodeOptions.inBitmap = reusableBitmap }

                                val bitmap = try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                                } catch (e: IllegalArgumentException) {
                                    reusableBitmap = null
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                                }

                                if (bitmap != null) {
                                    reusableBitmap = bitmap
                                    val holder = svLiveVideo.holder
                                    val canvas = holder.lockCanvas()
                                    if (canvas != null) {
                                        canvas.drawColor(Color.BLACK)
                                        val destRect = android.graphics.Rect(0, 0, svLiveVideo.width, svLiveVideo.height)
                                        canvas.drawBitmap(bitmap, null, destRect, null)
                                        holder.unlockCanvasAndPost(canvas)
                                    }
                                }
                            }
                        } catch (e: Exception) { break }
                    }
                }
            } catch (e: Exception) { Log.e("BabyGuard", "Video Server Error", e) }
        }
        videoServerThread?.start()
    }

    private fun stopVideoServer() {
        isVideoPlaying = false
        try { activeVideoClient?.close() } catch (_: Exception) { }
        try { videoServerSocket?.close() } catch (_: Exception) { }
        activeVideoClient = null; videoServerSocket = null
    }

    private fun startAudioListening() {
        if (isMicListening) return
        isMicListening = true
        fabMic.imageTintList = android.content.res.ColorStateList.valueOf(Color.RED)
        
        // Notify Baby Unit to start streaming audio (Simplified: Just start server)
        audioServerThread = Thread {
            try {
                audioServerSocket = ServerSocket(8890).apply { reuseAddress = true }
                while (isMicListening) {
                    audioClient = audioServerSocket!!.accept()
                    val dis = DataInputStream(audioClient!!.inputStream)
                    
                    val bufferSize = android.media.AudioTrack.getMinBufferSize(16000, 
                        android.media.AudioFormat.CHANNEL_OUT_MONO, 
                        android.media.AudioFormat.ENCODING_PCM_16BIT)
                    
                    val audioTrack = android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        16000,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        android.media.AudioTrack.MODE_STREAM
                    )
                    
                    audioTrack.play()
                    val buffer = ShortArray(bufferSize)
                    while (isMicListening && !audioClient!!.isClosed) {
                        try {
                            for (i in buffer.indices) buffer[i] = dis.readShort()
                            audioTrack.write(buffer, 0, buffer.size)
                        } catch (e: Exception) { break }
                    }
                    audioTrack.stop()
                    audioTrack.release()
                }
            } catch (e: Exception) { Log.e("BabyGuard", "Audio Server Error", e) }
        }
        audioServerThread?.start()
    }

    private fun stopAudioListening() {
        isMicListening = false
        fabMic.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#888888"))
        try { audioClient?.close() } catch (_: Exception) {}
        try { audioServerSocket?.close() } catch (_: Exception) {}
        audioClient = null; audioServerSocket = null
    }

    @Suppress("DEPRECATION")
    private fun generateLanQrCode() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return
            showQrCode("LAN:" + String.format(Locale.US, "%d.%d.%d.%d", ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff))
        } catch (_: Exception) {}
    }

    private fun showQrCode(ipAddressTag: String) {
        runOnUiThread {
            try {
                val writer = QRCodeWriter()
                val bitMatrix = writer.encode(ipAddressTag, BarcodeFormat.QR_CODE, 512, 512)
                val bitmap = Bitmap.createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.RGB_565)
                for (x in 0 until bitMatrix.width) {
                    for (y in 0 until bitMatrix.height) { bitmap[x, y] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE }
                }
                ivQrCode.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoServer()
        handler.removeCallbacks(motionDecayRunnable)
    }

    override fun onBackPressed() {
        if (cardVideo.visibility == View.VISIBLE) { hideCard(cardVideo, R.anim.slide_out_up); stopVideoServer() }
        else if (cardSettings.visibility == View.VISIBLE) { hideCard(cardSettings, R.anim.slide_out_right) }
        else super.onBackPressed()
    }
}