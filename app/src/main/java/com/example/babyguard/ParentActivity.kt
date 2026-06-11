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
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.set
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

@SuppressLint("SetTextI18n")
class ParentActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var pairingLayout: LinearLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var btnLiveVideo: Button
    private lateinit var svLiveVideo: SurfaceView // Using Hardware SurfaceView!
    private lateinit var svHistoryContainer: LinearLayout
    private lateinit var llAlertHistory: LinearLayout
    private lateinit var btnPairBack: Button

    private lateinit var tvInsightMood: TextView
    private lateinit var tvInsightPosture: TextView

    private lateinit var dbHelper: AlertDatabaseHelper
    private var isVideoPlaying = false
    private var videoServerSocket: ServerSocket? = null
    private var activeVideoClient: Socket? = null
    private var videoServerThread: Thread? = null

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonString = intent?.getStringExtra("payload") ?: return
            try {
                val jsonObject = org.json.JSONObject(jsonString)

                tvInsightMood.text = jsonObject.optString("mood", "Calm")
                tvInsightPosture.text = jsonObject.optString("posture", "Safe")

                if (pairingLayout.isVisible) {
                    pairingLayout.isGone = true
                    dashboardLayout.isVisible = true
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

        drawerLayout = findViewById(R.id.drawerLayout)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        pairingLayout = findViewById(R.id.pairingLayout)
        ivQrCode = findViewById(R.id.ivQrCode)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        btnLiveVideo = findViewById(R.id.btnLiveVideo)
        svLiveVideo = findViewById(R.id.svLiveVideo)
        svHistoryContainer = findViewById(R.id.svHistoryContainer)
        llAlertHistory = findViewById(R.id.llAlertHistory)
        tvInsightMood = findViewById(R.id.tvInsightMood)
        tvInsightPosture = findViewById(R.id.tvInsightPosture)
        btnPairBack = findViewById(R.id.btnPairBack)

        dbHelper = AlertDatabaseHelper(this)

        findViewById<ImageButton>(R.id.btnOpenSettings).setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        findViewById<ImageButton>(R.id.btnOpenSettingsBottom).setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        findViewById<ImageButton>(R.id.btnToggleHistory).setOnClickListener { svHistoryContainer.isVisible = svHistoryContainer.isGone }

        btnPairBack.setOnClickListener {
            pairingLayout.isGone = true
            dashboardLayout.isVisible = true
            tvSubtitle.text = "Waiting for Camera to reconnect..."
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            dbHelper.clearAllAlerts()
            loadHistoryToUI()
            drawerLayout.closeDrawer(GravityCompat.START)
            Toast.makeText(this, "Event Log Cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            tvSubtitle.text = "🔄 Forcing Reconnect..."

            stopVideoServer()
            stopService(Intent(this, BabyGuardService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, BabyGuardService::class.java))

            pairingLayout.isVisible = true
            dashboardLayout.isGone = true
            generateLanQrCode()
        }

        btnLiveVideo.setOnClickListener {
            if (isVideoPlaying) stopVideoServer() else startVideoServer()
        }

        generateLanQrCode()
        loadHistoryToUI()
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
        for (alert in dbHelper.getAllAlerts()) {
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

    private fun startVideoServer() {
        isVideoPlaying = true
        btnLiveVideo.text = "🛑 Stop Video Stream"
        svLiveVideo.isVisible = true

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
        btnLiveVideo.text = "📹 Start Video Stream"
        svLiveVideo.isGone = true
        try { activeVideoClient?.close() } catch (_: Exception) { }
        try { videoServerSocket?.close() } catch (_: Exception) { }
        activeVideoClient = null; videoServerSocket = null
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
    }
}