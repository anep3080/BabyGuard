package com.example.babyguard

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.set
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import soup.neumorphism.NeumorphCardView
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

@SuppressLint("SetTextI18n")
class ParentActivity : AppCompatActivity() {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnOpenSettings: ImageButton
    private lateinit var btnOpenSettingsBottom: ImageButton
    private lateinit var tvSubtitle: TextView
    private lateinit var qrCard: NeumorphCardView
    private lateinit var ivQrCode: ImageView
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var btnLiveVideo: Button
    private lateinit var ivLiveVideo: ImageView
    private lateinit var btnSnapshot: Button
    private lateinit var btnToggleHistory: ImageButton
    private lateinit var svHistoryContainer: LinearLayout
    private lateinit var llAlertHistory: LinearLayout

    private lateinit var tvInsightMood: TextView
    private lateinit var tvInsightPosture: TextView
    private lateinit var switchAlarm: SwitchCompat
    private lateinit var btnClearLog: Button
    private lateinit var btnReconnect: Button

    private var alertServer: AlertServer? = null
    private lateinit var dbHelper: AlertDatabaseHelper
    private var lastDbSaveTime = 0L
    private var masterAlarmEnabled = true

    private var isVideoPlaying = false
    private var videoServerSocket: ServerSocket? = null
    private var activeVideoClient: Socket? = null
    private var videoServerThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)

        drawerLayout = findViewById(R.id.drawerLayout)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnOpenSettingsBottom = findViewById(R.id.btnOpenSettingsBottom)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        qrCard = findViewById(R.id.qrCard)
        ivQrCode = findViewById(R.id.ivQrCode)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        btnLiveVideo = findViewById(R.id.btnLiveVideo)
        ivLiveVideo = findViewById(R.id.ivLiveVideo)
        btnSnapshot = findViewById(R.id.btnSnapshot)
        btnToggleHistory = findViewById(R.id.btnToggleHistory)
        svHistoryContainer = findViewById(R.id.svHistoryContainer)
        llAlertHistory = findViewById(R.id.llAlertHistory)
        tvInsightMood = findViewById(R.id.tvInsightMood)
        tvInsightPosture = findViewById(R.id.tvInsightPosture)
        switchAlarm = findViewById(R.id.switchAlarm)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnReconnect = findViewById(R.id.btnReconnect)

        dbHelper = AlertDatabaseHelper(this)

        btnOpenSettings.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        btnOpenSettingsBottom.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        btnToggleHistory.setOnClickListener {
            if (svHistoryContainer.isGone) svHistoryContainer.isVisible = true
            else svHistoryContainer.isGone = true
        }

        switchAlarm.setOnCheckedChangeListener { _, isChecked -> masterAlarmEnabled = isChecked }

        btnClearLog.setOnClickListener {
            dbHelper.clearAllAlerts()
            loadHistoryToUI()
            drawerLayout.closeDrawer(GravityCompat.START)
            Toast.makeText(this, "Alert Log Cleared", Toast.LENGTH_SHORT).show()
        }

        btnReconnect.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            tvSubtitle.text = "🔄 Restarting Server..."
            alertServer?.close()
            alertServer = null
            startListeningForAlerts()
        }

        btnLiveVideo.setOnClickListener { if (isVideoPlaying) stopVideoServer() else startVideoServer() }

        btnSnapshot.setOnClickListener {
            try { saveImageToGallery((ivLiveVideo.drawable as BitmapDrawable).bitmap, "Live") }
            catch (_: Exception) { Toast.makeText(this, "No frame available!", Toast.LENGTH_SHORT).show() }
        }

        generateLanQrCode()
        startListeningForAlerts()
    }

    private fun saveImageToGallery(bitmap: Bitmap, prefix: String = "Live") {
        val filename = "BabyGuard_${prefix}_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BabyGuard")
            }
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }
            if (fos != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
                Toast.makeText(this, "✅ Saved!", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) { } finally { fos?.close() }
    }

    @Suppress("DEPRECATION") // Safe to suppress for FYP Local Network use
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
                    for (y in 0 until bitMatrix.height) {
                        bitmap[x, y] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                    }
                }
                ivQrCode.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        }
    }

    private fun startListeningForAlerts() {
        if (alertServer == null) {
            alertServer = AlertServer { incomingJson ->
                runOnUiThread {
                    try {
                        val jsonObject = org.json.JSONObject(incomingJson)
                        val currentStatus = jsonObject.getString("status")
                        val base64Image = jsonObject.optString("image", "")

                        tvInsightMood.text = jsonObject.optString("mood", "Calm")
                        tvInsightPosture.text = jsonObject.optString("posture", "Safe")

                        qrCard.isGone = true
                        dashboardLayout.isVisible = true
                        tvSubtitle.text = "Connected via Local Home Network"

                        if (currentStatus.contains("DANGER") || currentStatus.contains("RISK")) {
                            if (masterAlarmEnabled) {
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastDbSaveTime > 5000) {
                                    lastDbSaveTime = currentTime
                                    dbHelper.saveAlert(java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date()), currentStatus, base64Image)
                                    loadHistoryToUI()
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e("BabyGuard", "Error parsing payload", e)
                    }
                }
            }
            alertServer?.start()
            runOnUiThread { loadHistoryToUI() }
        }
    }

    private fun loadHistoryToUI() {
        llAlertHistory.removeAllViews()
        for (alert in dbHelper.getAllAlerts()) {

            val card = androidx.cardview.widget.CardView(this).apply {
                radius = 24f
                setCardBackgroundColor("#EAEAEA".toColorInt())
                cardElevation = 0f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 24)
                }
            }

            val cardContent = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 32, 32, 32)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            textLayout.addView(TextView(this).apply { text = "🚨 ${alert.timestamp}"; setTextColor("#E53935".toColorInt()); textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) })
            textLayout.addView(TextView(this).apply { text = alert.status; setTextColor("#555555".toColorInt()); textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 8, 0, 0) })

            cardContent.addView(textLayout)

            if (alert.imageBase64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(alert.imageBase64, android.util.Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    val imageCard = androidx.cardview.widget.CardView(this).apply {
                        radius = 16f
                        cardElevation = 0f
                    }

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
        btnLiveVideo.text = "🛑 Stop Video"
        ivLiveVideo.isVisible = true
        btnSnapshot.isVisible = true
        videoServerThread = Thread {
            try {
                videoServerSocket = ServerSocket(8889)
                while (isVideoPlaying) {
                    activeVideoClient = videoServerSocket!!.accept()
                    val dis = DataInputStream(activeVideoClient!!.inputStream)
                    while (isVideoPlaying) {
                        val size = dis.readInt()
                        if (size > 0) {
                            val bytes = ByteArray(size)
                            dis.readFully(bytes)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            runOnUiThread { ivLiveVideo.setImageBitmap(bitmap) }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        videoServerThread?.start()
    }

    private fun stopVideoServer() {
        isVideoPlaying = false
        btnLiveVideo.text = "📹 Watch Live Video"
        ivLiveVideo.isGone = true
        btnSnapshot.isGone = true
        try { activeVideoClient?.close() } catch (_: Exception) { }
        try { videoServerSocket?.close() } catch (_: Exception) { }
        activeVideoClient = null; videoServerSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        alertServer?.close()
        stopVideoServer()
    }
}