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
import android.net.wifi.p2p.WifiP2pDevice
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
import androidx.core.graphics.toColorInt
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
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
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var headerLayout: LinearLayout
    private lateinit var tvSubtitle: TextView
    private lateinit var pairingLayout: LinearLayout
    private lateinit var ivQrCode: ImageView
    private lateinit var dashboardLayout: LinearLayout
    private lateinit var cardPrevDevice: NeumorphCardView
    private lateinit var btnPairBack: Button
    
    private lateinit var btnNeumorphStream: NeumorphCardView
    private lateinit var tvStreamBtnText: TextView
    private lateinit var videoPopupOverlay: FrameLayout
    private lateinit var videoPopupContent: LinearLayout
    private lateinit var btnCloseVideoPopup: ImageButton
    private lateinit var btnOverlaySwitch: ImageButton
    private lateinit var btnOverlayRotate: ImageButton
    
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var sliderMogMeter: SeekBar
    private lateinit var sliderMotionThreshold: SeekBar
    private lateinit var cbAlarmCrying: CheckBox
    private lateinit var cbAlarmProne: CheckBox
    
    private lateinit var svLiveVideo: SurfaceView
    private lateinit var llAlertHistory: LinearLayout

    private lateinit var tvInsightMood: TextView
    private lateinit var tvInsightPosture: TextView

    private lateinit var btnMethodLan: Button
    private lateinit var btnMethodDirect: Button
    private lateinit var layoutLanPairing: LinearLayout
    private lateinit var layoutDirectPairing: LinearLayout
    private lateinit var llPeerList: LinearLayout

    private lateinit var dbHelper: AlertDatabaseHelper
    private var isVideoPlaying = false
    private var videoServerSocket: ServerSocket? = null
    private var activeVideoClient: Socket? = null
    private var videoServerThread: Thread? = null
    
    private var babyIp: String? = null
    private var wifiDirectManager: WifiDirectManager? = null

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonString = intent?.getStringExtra("payload") ?: return
            val senderIp = intent.getStringExtra("baby_ip")
            
            if (senderIp != null && babyIp != senderIp) {
                babyIp = senderIp
                saveLastConnectedIp(senderIp)
            }

            try {
                val jsonObject = org.json.JSONObject(jsonString)
                
                // Update Insights
                if (jsonObject.has("emotion")) {
                    tvInsightMood.text = jsonObject.getString("emotion")
                }
                if (jsonObject.has("posture")) {
                    tvInsightPosture.text = jsonObject.getString("posture")
                }
                
                // Update Motion Meter
                if (jsonObject.has("motion_intensity")) {
                    val intensity = jsonObject.getInt("motion_intensity")
                    sliderMogMeter.progress = intensity
                }

                if (pairingLayout.isVisible) {
                    onPairingSuccess()
                }
                loadHistoryToUI()
            } catch (e: Exception) {
                Log.e("BabyGuard", "JSON parsing error", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        setContentView(R.layout.activity_parent)

        headerLayout = findViewById(R.id.headerLayout)
        
        ViewCompat.setOnApplyWindowInsetsListener(headerLayout) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight + 24.dpToPx(), v.paddingRight, v.paddingBottom)
            insets
        }

        initViews()
        checkPermissions()

        ContextCompat.startForegroundService(this, Intent(this, BabyGuardService::class.java))

        dbHelper = AlertDatabaseHelper(this)

        setupPairingMethods()
        setupWifiDirect()
        setupSettings()

        fabSettings.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        btnCloseVideoPopup.setOnClickListener { stopVideoServer() }

        btnPairBack.setOnClickListener {
            val lastIp = getLastConnectedIp()
            if (lastIp != null) {
                babyIp = lastIp
                onPairingSuccess("🟢 Restoring Connection...")
            }
        }

        btnNeumorphStream.setOnClickListener {
            if (isVideoPlaying) stopVideoServer() else startVideoServer()
        }

        // Floating Overlay Controls
        btnOverlaySwitch.setOnClickListener {
            babyIp?.let { ip ->
                CommandClient(ip, "SWITCH_CAM").start()
                Toast.makeText(this, "Switching Baby Unit Camera...", Toast.LENGTH_SHORT).show()
            }
        }

        btnOverlayRotate.setOnClickListener {
            babyIp?.let { ip ->
                CommandClient(ip, "ROTATE_CAM").start()
                Toast.makeText(this, "Rotating Baby Unit View...", Toast.LENGTH_SHORT).show()
            }
        }

        checkPreviousConnection()
        generateLanQrCode()
        loadHistoryToUI()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        pairingLayout = findViewById(R.id.pairingLayout)
        ivQrCode = findViewById(R.id.ivQrCode)
        dashboardLayout = findViewById(R.id.dashboardLayout)
        cardPrevDevice = findViewById(R.id.cardPrevDevice)
        btnPairBack = findViewById(R.id.btnPairBack)
        
        btnNeumorphStream = findViewById(R.id.btnNeumorphStream)
        tvStreamBtnText = findViewById(R.id.tvStreamBtnText)
        videoPopupOverlay = findViewById(R.id.videoPopupOverlay)
        videoPopupContent = videoPopupOverlay.getChildAt(0) as LinearLayout
        btnCloseVideoPopup = findViewById(R.id.btnCloseVideoPopup)
        btnOverlaySwitch = findViewById(R.id.btnOverlaySwitch)
        btnOverlayRotate = findViewById(R.id.btnOverlayRotate)
        
        fabSettings = findViewById(R.id.fabSettings)
        sliderMogMeter = findViewById(R.id.sliderMogMeter)
        
        // Settings in Drawer
        sliderMotionThreshold = findViewById(R.id.sliderMotionThreshold)
        cbAlarmCrying = findViewById(R.id.cbAlarmCrying)
        cbAlarmProne = findViewById(R.id.cbAlarmProne)
        
        svLiveVideo = findViewById(R.id.svLiveVideo)
        llAlertHistory = findViewById(R.id.llAlertHistory)
        tvInsightMood = findViewById(R.id.tvInsightMood)
        tvInsightPosture = findViewById(R.id.tvInsightPosture)

        btnMethodLan = findViewById(R.id.btnMethodLan)
        btnMethodDirect = findViewById(R.id.btnMethodDirect)
        layoutLanPairing = findViewById(R.id.layoutLanPairing)
        layoutDirectPairing = findViewById(R.id.layoutDirectPairing)
        llPeerList = findViewById(R.id.llPeerList)
    }

    private fun setupSettings() {
        sliderMotionThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && babyIp != null) {
                    CommandClient(babyIp!!, "MOG2:$progress").start()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val configListener = CompoundButton.OnCheckedChangeListener { _, _ ->
            sendConfigToBaby()
        }
        cbAlarmCrying.setOnCheckedChangeListener(configListener)
        cbAlarmProne.setOnCheckedChangeListener(configListener)

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            dbHelper.clearAllAlerts()
            loadHistoryToUI()
            drawerLayout.closeDrawer(GravityCompat.START)
            Toast.makeText(this, "Event Log Cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            tvSubtitle.text = "🔄 Resyncing System..."
            stopVideoServer()
            pairingLayout.isVisible = true
            dashboardLayout.isGone = true
            generateLanQrCode()
        }
    }

    private fun sendConfigToBaby() {
        if (babyIp == null) return
        val config = org.json.JSONObject().apply {
            put("alarm_crying", cbAlarmCrying.isChecked)
            put("alarm_prone", cbAlarmProne.isChecked)
        }.toString()
        CommandClient(babyIp!!, "CONFIG:$config").start()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        val toRequest = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1002)
        }
    }

    private fun setupPairingMethods() {
        btnMethodLan.setOnClickListener {
            btnMethodLan.backgroundTintList = ContextCompat.getColorStateList(this, R.color.baby_blue)
            btnMethodLan.setTextColor(Color.WHITE)
            btnMethodDirect.backgroundTintList = Color.parseColor("#EEEEEE").toColorStateList()
            btnMethodDirect.setTextColor(Color.parseColor("#888888"))
            
            layoutLanPairing.isVisible = true
            layoutDirectPairing.isGone = true
        }

        btnMethodDirect.setOnClickListener {
            btnMethodDirect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.baby_blue)
            btnMethodDirect.setTextColor(Color.WHITE)
            btnMethodLan.backgroundTintList = Color.parseColor("#EEEEEE").toColorStateList()
            btnMethodLan.setTextColor(Color.parseColor("#888888"))
            
            layoutDirectPairing.isVisible = true
            layoutLanPairing.isGone = true
            
            wifiDirectManager?.startDiscovery()
        }
    }

    private fun setupWifiDirect() {
        wifiDirectManager = WifiDirectManager(
            this,
            isParentUnit = true,
            onPeerListChanged = { devices -> updatePeerList(devices) },
            onConnectionSuccess = { ip ->
                babyIp = ip
                saveLastConnectedIp(ip)
                runOnUiThread { onPairingSuccess("🟢 WiFi Direct Connected") }
            }
        )
    }

    private fun updatePeerList(devices: List<WifiP2pDevice>) {
        llPeerList.removeAllViews()
        if (devices.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Searching for devices...\n(Ensure Baby Unit is in Pairing Mode)"
                gravity = android.view.Gravity.CENTER
                setPadding(0, 48.dpToPx(), 0, 0)
                setTextColor(Color.GRAY)
            }
            llPeerList.addView(tv)
            return
        }

        for (device in devices) {
            val card = NeumorphCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 24.dpToPx()) }
                
                try {
                    val setShapeTypeMethod = javaClass.getMethod("setShapeType", Int::class.javaPrimitiveType)
                    setShapeTypeMethod.invoke(this, 0)
                } catch (_: Exception) {}
                
                setBackgroundColor(Color.parseColor("#FAF9F6"))
            }
            
            val innerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = 24.dpToPx()
                setPadding(p, p, p, p)
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvName = TextView(this).apply {
                text = "Baby Unit: ${device.deviceName}"
                textSize = 18f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            
            val tvAddr = TextView(this).apply {
                text = "ID: ${device.deviceAddress}"
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, 8.dpToPx(), 0, 0)
            }

            innerLayout.addView(tvName)
            innerLayout.addView(tvAddr)
            card.addView(innerLayout)
            
            card.setOnClickListener { 
                wifiDirectManager?.connect(device)
                Toast.makeText(this, "Pairing with ${device.deviceName}...", Toast.LENGTH_SHORT).show()
            }

            llPeerList.addView(card)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun onPairingSuccess(subtitle: String = "🟢 System Active & Secure") {
        pairingLayout.isGone = true
        dashboardLayout.isVisible = true
        tvSubtitle.text = subtitle
    }

    private fun Int.toColorStateList() = android.content.res.ColorStateList.valueOf(this)

    private fun checkPreviousConnection() {
        val lastIp = getLastConnectedIp()
        if (lastIp != null) {
            cardPrevDevice.isVisible = true
        }
    }

    private fun saveLastConnectedIp(ip: String) {
        getSharedPreferences("BabyGuard", Context.MODE_PRIVATE).edit().putString("last_ip", ip).apply()
    }

    private fun getLastConnectedIp(): String? {
        return getSharedPreferences("BabyGuard", Context.MODE_PRIVATE).getString("last_ip", null)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("BABYGUARD_NEW_DATA")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dataReceiver, filter)
        }
        wifiDirectManager?.register()
        loadHistoryToUI()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(dataReceiver) } catch (e: Exception) {}
        wifiDirectManager?.unregister()
        stopVideoServer()
    }

    private fun loadHistoryToUI() {
        llAlertHistory.removeAllViews()
        for (alert in dbHelper.getAllAlerts()) {
            val card = androidx.cardview.widget.CardView(this).apply {
                radius = 32f
                setCardBackgroundColor("#FFFFFF".toColorInt())
                cardElevation = 2f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 32) }
            }

            val cardContent = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(40, 40, 40, 40); gravity = android.view.Gravity.CENTER_VERTICAL }
            val textLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }

            // Priority Badge
            val priorityColor = when(alert.priority) {
                "HIGH" -> "#F44336"
                "MEDIUM" -> "#FFC107"
                else -> "#4CAF50"
            }
            val tvPriority = TextView(this).apply {
                text = alert.priority
                setTextColor(Color.WHITE)
                textSize = 10sp
                setPadding(16, 4, 16, 4)
                background = ContextCompat.getDrawable(this@ParentActivity, R.drawable.pill_background)
                backgroundTintList = Color.parseColor(priorityColor).toColorStateList()
            }
            textLayout.addView(tvPriority)

            textLayout.addView(TextView(this).apply { text = alert.status; setTextColor("#333333".toColorInt()); textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD) })
            textLayout.addView(TextView(this).apply { text = alert.timestamp; setTextColor("#888888".toColorInt()); textSize = 14f; setPadding(0, 8, 0, 0) })
            cardContent.addView(textLayout)

            if (alert.imageBase64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(alert.imageBase64, android.util.Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val imageCard = androidx.cardview.widget.CardView(this).apply { radius = 24f; cardElevation = 0f }
                    imageCard.addView(ImageView(this).apply { setImageBitmap(bitmap); layoutParams = LinearLayout.LayoutParams(180, 180); scaleType = ImageView.ScaleType.CENTER_CROP })
                    cardContent.addView(imageCard)
                } catch (_: Throwable) {}
            }
            card.addView(cardContent)
            llAlertHistory.addView(card)
        }
    }

    private fun startVideoServer() {
        if (isVideoPlaying) return
        isVideoPlaying = true
        videoPopupOverlay.visibility = View.VISIBLE
        
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.scale_fade_in)
        videoPopupContent.startAnimation(fadeIn)
        
        tvStreamBtnText.text = "🛑 STOP VIDEO STREAM"

        videoServerThread = Thread {
            try {
                videoServerSocket = ServerSocket(8889).apply { reuseAddress = true }
                while (isVideoPlaying) {
                    val socket = videoServerSocket?.accept() ?: break
                    activeVideoClient = socket
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
        if (!isVideoPlaying) return
        isVideoPlaying = false
        runOnUiThread {
            val fadeOut = AnimationUtils.loadAnimation(this, R.anim.scale_fade_out)
            fadeOut.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    videoPopupOverlay.visibility = View.GONE
                }
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            })
            videoPopupContent.startAnimation(fadeOut)
            tvStreamBtnText.text = "📹 VIEW LIVE STREAM"
        }
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
                    for (y in 0 until bitMatrix.height) { 
                        bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE) 
                    }
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