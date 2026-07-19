package com.example.babyguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
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
    private lateinit var cardVideo: View
    private lateinit var cardSettings: View
    private lateinit var tvInsightMood: TextView
    private lateinit var tvInsightPosture: TextView
    // Tracks the last-seen (rotation-adjusted) frame dimensions purely to avoid re-laying-out
    // videoContainer on every single frame when nothing has actually changed.
    private var lastFrameEffW: Int = -1
    private var lastFrameEffH: Int = -1
    // Parent-only display rotation (0/90/180/270) of the live-view window itself — a pure local
    // UI transform on videoContainer, never sent to the Baby Unit. Cumulative, wraps at 360.
    private var liveViewWindowRotation = 0f

    private lateinit var switchMasterAlert: SwitchCompat
    private lateinit var switchMasterAlertQuick: SwitchCompat   // in live insights card
    private lateinit var switchMediumAlert: SwitchCompat
    private lateinit var switchLowAlert: SwitchCompat
    private lateinit var switchQuietHours: SwitchCompat
    private lateinit var tvQuietStart: TextView
    private lateinit var tvQuietEnd: TextView
    private lateinit var rowAlarmSound: View
    private lateinit var tvAlarmSoundName: TextView

    // Smooths soundMeter's progress updates (see updateSoundMeter) instead of snapping
    // instantly on every ~5x/sec telemetry tick.
    private var soundMeterAnimator: android.animation.ObjectAnimator? = null

    // Result of RingtoneManager.ACTION_RINGTONE_PICKER, launched from rowAlarmSound.
    private val ringtonePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.alarmSoundUri = uri?.toString() ?: ""
            refreshAlarmSoundLabel()
        }
    }

    private lateinit var alertOverlay: FrameLayout
    private lateinit var tvAlertMessage: TextView
    private lateinit var btnDismissAlert: Button

    // ── State ─────────────────────────────────────────────────────────────────
    private lateinit var prefs: AppPreferences
    private lateinit var dbHelper: AlertDatabaseHelper

    // After the parent dismisses an alert, suppress incoming posture/mood telemetry updates
    // for DISMISS_CALM_MS and show "Calm" in the insights — gives the Baby Unit time to reset
    // its pipeline and stops the display from immediately jumping back to "Standing" again.
    private var dismissCooldownUntil = 0L
    private val DISMISS_CALM_MS = 2_500L
    private val recorder by lazy { VideoRecorder(this) }

    // NOTE: alert firing/cooldowns/logging now live in BabyGuardService — it's the only
    // piece reliably alive when the screen is off or the app is backgrounded. This Activity
    // only refreshes UI in response to the broadcast (see dataReceiver below).

    private var recorderStarted = false

    private var latestStreamFrame: Bitmap? = null
    private var isRecording = false
    private var currentMotionLevel = 0f

    private var isVideoPlaying = false
    private var videoServerSocket: ServerSocket? = null
    private var activeVideoClient: Socket? = null
    private var videoServerThread: Thread? = null

    private var isMicListening = false
    private var audioServerThread: Thread? = null
    private var audioServerSocket: ServerSocket? = null
    private var audioClient: Socket? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // Motion meter: smooth decay — reflects peak and slides down gracefully.
    private val soundDecayRunnable = object : Runnable {
        override fun run() {
            if (currentMotionLevel > 0) {
                currentMotionLevel *= 0.94f // Slowly spring back to zero
                if (currentMotionLevel < 1) currentMotionLevel = 0f
                motionMeter.progress = currentMotionLevel.toInt()
            }
            handler.postDelayed(this, 30)
        }
    }

    // ── Telemetry receiver ─────────────────────────────────────────────────────
    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "BABYGUARD_HIGH_ALERT") {
                showActiveAlert(intent.getStringExtra("headline")
                    ?: "Check on baby — unusual activity detected")
                return
            }
            if (intent?.action == "BABYGUARD_CMD_RESULT") {
                val cmdSucceeded = intent.getBooleanExtra("success", true)
                if (!cmdSucceeded) {
                    Toast.makeText(this@ParentActivity,
                        "Baby Unit not connected — command not sent", Toast.LENGTH_LONG).show()
                }
                return
            }
            val jsonString = intent?.getStringExtra("payload") ?: return
            try {
                val json   = org.json.JSONObject(jsonString)
                val mood    = json.optString("mood", "Calm")
                val posture = json.optString("posture", "Safe")

                // During the post-dismiss calm window, hold "Calm" on screen instead of
                // immediately re-showing whatever the Baby Unit's pipeline last reported.
                if (System.currentTimeMillis() >= dismissCooldownUntil) {
                    tvInsightMood.text    = mood
                    tvInsightPosture.text = posture
                }

                // Motion meter: peak-hold + decay (decay runnable handles the fall-off).
                val newMotion = json.optInt("motion_level", 0).toFloat()
                if (newMotion > currentMotionLevel) {
                    currentMotionLevel = newMotion
                    motionMeter.progress = newMotion.toInt()
                }

                // Sound meter — already arrives ~5x/sec from the Baby Unit's telemetry tick, so
                // unlike motion it doesn't need its own peak-hold/decay runnable. The jump
                // between ticks is animated (see updateSoundMeter) so the needle glides instead
                // of snapping on every update.
                updateSoundMeter(json.optInt("sound_level", 0))

                // Alert firing/cooldowns and the actual dbHelper.saveAlert(...) write now
                // happen inside BabyGuardService (the only piece reliably alive when the
                // screen is off). The Service tells us via this extra whether it just
                // logged a new History entry, so we only refresh the list when needed.
                val loggedNow = intent.getBooleanExtra("loggedNow", false)
                if (loggedNow) loadHistoryToUI()

                val battery = json.optInt("battery", -1)
                if (battery != -1) {
                    llBabyBattery.visibility = View.VISIBLE
                    pbBabyBattery.progress   = battery
                    tvBabyBatteryPct.text    = " $battery%"
                }

                if (pairingLayout.visibility == View.VISIBLE) {
                    pairingLayout.visibility  = View.GONE
                    dashboardLayout.visibility = View.VISIBLE
                    // Soft-UI pop: the neumorphic Live Insights / Log section
                    // springs in rather than just appearing instantly.
                    dashboardLayout.scaleX = 0.9f
                    dashboardLayout.scaleY = 0.9f
                    dashboardLayout.alpha = 0f
                    dashboardLayout.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(320L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                        .start()
                    tvSubtitle.text = "🟢 Connected Securely"
                    // Reveal action FABs now that we are connected
                    updateFabVisibility(connected = true)
                }
            } catch (_: Exception) {}
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent)
        // NOTE: do NOT stopService() here first. BabyGuardService holds the only live socket to
        // the Baby Unit; stopping it on every onCreate (e.g. every time the notification is
        // tapped to reopen the app) killed that connection and forced a multi-second reconnect,
        // during which the dashboard had no data to show and fell back to the QR pairing screen
        // even though the Baby Unit was already connected. startForegroundService() is safe to
        // call when the service is already running — it just re-delivers onStartCommand.
        ContextCompat.startForegroundService(this, Intent(this, BabyGuardService::class.java))

        prefs    = AppPreferences(this)
        dbHelper = AlertDatabaseHelper(this)

        bindViews()
        restoreSettings()
        setupListeners()

        createNotificationChannel()
        generateLanQrCode()
        loadHistoryToUI()
        handler.post(soundDecayRunnable)

        // Without this, the heads-up notification (and the in-app dismiss overlay's reach via
        // tapping it) never appears — NotificationManager.notify() silently no-ops without
        // POST_NOTIFICATIONS on Android 13+.
        requestNotificationPermissionIfNeeded()
        // Many OEMs (Samsung's "Sleeping apps" / adaptive battery in particular) kill backgrounded
        // foreground services anyway despite the foreground-service contract, which would silently
        // stop alert delivery once the parent minimizes the app. Asking to be exempted from
        // battery optimization makes that far less likely.
        requestBatteryOptimizationExemptionIfNeeded()
    }

    // ── Alert delivery permissions ───────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }

    /**
     * Prompts (once per launch, only if not already exempted) to whitelist the app from battery
     * optimization, so BabyGuardService — which must keep its socket + alarm playback alive while
     * the Parent app is minimized or the screen is off — isn't killed by OEM background restrictions.
     */
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            }
        } catch (_: Exception) {
            // Some OEM ROMs restrict/ignore this intent — not fatal, just less reliable in the background.
        }
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
        tvInsightMood        = findViewById(R.id.tvInsightMood)
        tvInsightPosture     = findViewById(R.id.tvInsightPosture)

        switchMasterAlert      = findViewById(R.id.switchMasterAlert)
        switchMasterAlertQuick = findViewById(R.id.switchMasterAlertQuick)
        switchMediumAlert      = findViewById(R.id.switchMediumAlert)
        switchLowAlert         = findViewById(R.id.switchLowAlert)
        switchQuietHours       = findViewById(R.id.switchQuietHours)
        tvQuietStart           = findViewById(R.id.tvQuietStart)
        tvQuietEnd             = findViewById(R.id.tvQuietEnd)
        rowAlarmSound          = findViewById(R.id.rowAlarmSound)
        tvAlarmSoundName       = findViewById(R.id.tvAlarmSoundName)

        alertOverlay    = findViewById(R.id.alertOverlay)
        tvAlertMessage  = findViewById(R.id.tvAlertMessage)
        btnDismissAlert = findViewById(R.id.btnDismissAlert)
    }

    // ── Settings persistence ───────────────────────────────────────────────────

    private fun restoreSettings() {
        switchMasterAlert.isChecked      = prefs.masterAlertEnabled
        switchMasterAlertQuick.isChecked = prefs.masterAlertEnabled
        switchMediumAlert.isChecked      = prefs.mediumAlertEnabled
        switchLowAlert.isChecked         = prefs.lowAlertEnabled
        switchQuietHours.isChecked       = prefs.quietHoursEnabled
        tvQuietStart.text = formatHour(prefs.quietHoursStart)
        tvQuietEnd.text   = formatHour(prefs.quietHoursEnd)
        refreshAlarmSoundLabel()
    }

    /** Resolves the saved alarm-sound URI (if any) to its display title via RingtoneManager. */
    private fun refreshAlarmSoundLabel() {
        val uriStr = prefs.alarmSoundUri
        tvAlarmSoundName.text = if (uriStr.isBlank()) {
            "Default Alarm Sound"
        } else {
            try {
                RingtoneManager.getRingtone(this, android.net.Uri.parse(uriStr))
                    ?.getTitle(this) ?: "Default Alarm Sound"
            } catch (_: Exception) {
                "Default Alarm Sound"
            }
        }
    }

    private fun setupListeners() {
        btnDismissAlert.setOnClickListener { dismissActiveAlert() }

        // Keep both master alert switches in sync
        switchMasterAlert.setOnCheckedChangeListener { _, v ->
            prefs.masterAlertEnabled = v
            if (switchMasterAlertQuick.isChecked != v) switchMasterAlertQuick.isChecked = v
        }
        switchMasterAlertQuick.setOnCheckedChangeListener { _, v ->
            prefs.masterAlertEnabled = v
            if (switchMasterAlert.isChecked != v) switchMasterAlert.isChecked = v
        }
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

        // High Alert sound — opens the system ringtone picker scoped to alarm sounds.
        // The result comes back through ringtonePickerLauncher above.
        rowAlarmSound.setOnClickListener {
            val current = prefs.alarmSoundUri.takeIf { it.isNotBlank() }
                ?.let { android.net.Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select High Alert Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            }
            ringtonePickerLauncher.launch(pickerIntent)
        }

        // FABs — toggle on repeated tap; hidden during pairing, shown after connection
        val fabVideo = findViewById<FloatingActionButton>(R.id.fabVideo)
        fabVideo.setOnClickListener {
            if (cardVideo.visibility == View.VISIBLE) {
                hideCard(cardVideo, R.anim.spring_out)
                stopVideoServer()
            } else {
                showCard(cardVideo, R.anim.slide_up_spring)
                startVideoServer()
            }
        }
        findViewById<FloatingActionButton>(R.id.fabSettings).setOnClickListener {
            if (cardSettings.visibility == View.VISIBLE) {
                hideCard(cardSettings, R.anim.spring_out)
            } else {
                showCard(cardSettings, R.anim.slide_in_right_spring)
            }
        }
        fabMic.setOnClickListener { if (isMicListening) stopAudioListening() else startAudioListening() }

        // Hide camera/mic FABs while on the QR pairing screen
        updateFabVisibility(connected = pairingLayout.visibility != View.VISIBLE)

        // Pairing action — regenerate the LAN QR code (sole pairing method)
        findViewById<android.widget.Button>(R.id.btnPairQr).setOnClickListener {
            val tvHint = findViewById<TextView>(R.id.tvPairingHint)
            tvHint.text = "⚠️ Ensure both devices are on the same WiFi network."
            tvHint.setTextColor(Color.parseColor("#CC7000"))
            generateLanQrCode()
        }

        // Toolbar buttons
        findViewById<ImageButton>(R.id.btnCloseVideo).setOnClickListener {
            hideCard(cardVideo, R.anim.spring_out); stopVideoServer()
        }
        findViewById<ImageButton>(R.id.btnCloseSettings).setOnClickListener {
            hideCard(cardSettings, R.anim.spring_out)
        }
        findViewById<ImageButton>(R.id.btnMainBack).setOnClickListener { finish() }

        // Manual reconnect — for when the Baby Unit looks "connected" here but commands keep
        // failing (a stale TCP connection neither side noticed died). Forces the Parent's socket
        // closed so the Baby Unit's writer detects it and redials within ~2s.
        findViewById<ImageButton>(R.id.btnReconnect).setOnClickListener {
            sendBroadcast(Intent("BABYGUARD_RECONNECT").setPackage(packageName))
            Toast.makeText(this, "Reconnecting to Baby Unit...", Toast.LENGTH_SHORT).show()
        }

        // Settings actions
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            dbHelper.clearAllAlerts(); loadHistoryToUI()
            Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
        }

        // Video panel actions
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { captureCurrentFrame() }
        findViewById<ImageButton>(R.id.btnRecord).setOnClickListener { toggleStreamRecording() }

        // Video rotation button — purely local to the Parent now: it rotates the live-view
        // window (videoContainer) itself on screen, with no command sent to the Baby Unit. The
        // Baby Unit no longer has any rotation concept at all; this just changes how the Parent
        // displays whatever it receives. Scaled down on 90°/270° steps so the rotated window
        // still fits within its original on-screen footprint instead of overflowing.
        findViewById<ImageButton>(R.id.btnRotateVideo).setOnClickListener {
            liveViewWindowRotation = (liveViewWindowRotation + 90f) % 360f

            // Canvas-level rotation now (see drawFrameAspectFit) — the SurfaceView/container is
            // never transformed or scaled, so there's no stretching. Just re-fit the container's
            // box shape for the new rotation and force-redraw the last frame immediately instead
            // of waiting for the next incoming frame.
            latestStreamFrame?.let { frame ->
                applyVideoContainerAspect(frame.width, frame.height, force = true)
                val holder = svLiveVideo.holder
                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    drawFrameAspectFit(canvas, frame, svLiveVideo.width, svLiveVideo.height)
                    holder.unlockCanvasAndPost(canvas)
                }
            }

            // Camera-app-style feedback: spin the button icon to acknowledge the tap
            it.animate()
                .rotationBy(90f)
                .setDuration(280L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }

    }

    /**
     * Reshapes the live-view window (videoContainer) to match the actual aspect ratio of the
     * incoming frame — using the frame's *exact* width:height (not a snapped-to-16:9/9:16
     * guess), so the box always matches whatever the Baby Unit is really sending. This is what
     * makes internal-camera mode match the Baby Unit's actual camera aspect: whatever ratio its
     * CameraX preview captures at is exactly what gets requested here, frame by frame — no
     * hardcoded assumption about phone camera shape. Independent of the Parent-only rotate
     * button (which rotates the whole window as a display transform, see btnRotateVideo); this
     * just follows whatever shape the raw frame arrives in.
     */
    private fun applyVideoContainerAspect(bmpWidth: Int, bmpHeight: Int, force: Boolean = false) {
        // When the live view is rotated 90/270, the rotated content's effective shape is the
        // bitmap's dimensions swapped — the container needs to match that swapped shape, not the
        // raw (unrotated) bitmap shape, or the box ends up the wrong shape for what gets drawn.
        val rotated = liveViewWindowRotation == 90f || liveViewWindowRotation == 270f
        val effW = if (rotated) bmpHeight else bmpWidth
        val effH = if (rotated) bmpWidth else bmpHeight
        if (effW <= 0 || effH <= 0) return
        if (!force && effW == lastFrameEffW && effH == lastFrameEffH) return
        lastFrameEffW = effW; lastFrameEffH = effH
        val container = findViewById<androidx.cardview.widget.CardView>(R.id.videoContainer) ?: return
        val lp = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        lp.dimensionRatio = "H,$effW:$effH"
        container.layoutParams = lp
    }

    private fun formatHour(h: Int) = String.format(Locale.getDefault(), "%02d:00", h)

    /**
     * Smoothly animates soundMeter's progress to [target] instead of snapping instantly —
     * the raw sound_level value still updates ~5x/sec, but the SeekBar glides between
     * readings so the meter doesn't look jittery. Cancels any in-flight animation first so
     * rapid successive ticks don't stack up and lag behind the latest reading.
     */
    private fun updateSoundMeter(target: Int) {
        soundMeterAnimator?.cancel()
        soundMeterAnimator = android.animation.ObjectAnimator.ofInt(soundMeter, "progress", soundMeter.progress, target).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            start()
        }
    }

    /**
     * Show the video + mic FABs only when connected (not during the QR pairing screen).
     * The settings FAB is always visible so users can clear the log or adjust preferences
     * even before pairing.
     */
    private fun updateFabVisibility(connected: Boolean) {
        val v = if (connected) View.VISIBLE else View.GONE
        findViewById<FloatingActionButton>(R.id.fabVideo)?.visibility = v
        fabMic.visibility = v
        // fabSettings always visible
    }

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
                Color.parseColor("#D3D3D3"))
            if (recorderStarted) {
                Thread { recorder.stop() }.start()   // stop off main thread (encoder drain)
                recorderStarted = false
            }
            Toast.makeText(this, "Recording saved to Gallery", Toast.LENGTH_LONG).show()
        }
    }

    // ── Alert handling ─────────────────────────────────────────────────────────
    // Firing/cooldowns/notifications now live in BabyGuardService — this Activity no
    // longer decides whether to alert, only whether to reset stray overlay state.

    /**
     * Shows the in-app dismiss overlay — the ONLY place a HIGH-tier alert now surfaces a "screen"
     * for the parent. Replaces the old system-wide full-screen AlarmActivity, which used to pop
     * over whatever app the parent was using. If the app isn't foregrounded when the alert fires,
     * vibration/ringtone/notification (from BabyGuardService) are how the parent finds out;
     * opening the Parent Unit screen then calls this via the onResume() sync below.
     */
    private fun showActiveAlert(headline: String) {
        tvAlertMessage.text = headline
        if (alertOverlay.visibility != View.VISIBLE) {
            alertOverlay.visibility = View.VISIBLE
            alertOverlay.alpha = 0f
            alertOverlay.animate().alpha(1f).setDuration(250L).start()
        }
    }

    private fun dismissActiveAlert() {
        alertOverlay.visibility = View.GONE
        alertOverlay.clearAnimation()
        // Stop ringtone/vibration/notification in the background service.
        sendBroadcast(Intent("BABYGUARD_ACK").setPackage(packageName))
        // Tell the Baby Unit to flush its pipeline state (hysteresis, streaks, consensus buffer)
        // so it stops sending HIGH-tier telemetry for the now-resolved condition immediately.
        sendBroadcast(Intent("BABYGUARD_CMD").setPackage(packageName)
            .putExtra("cmd", org.json.JSONObject().apply {
                put("cmd", "reset_pipeline")
            }.toString()))
        // Show a brief "Baby awake, calm" state in the insights while the Baby Unit resets —
        // prevents the posture/mood labels from jumping straight back to "Standing / Danger"
        // on the very next telemetry tick.
        tvInsightPosture.text = "Calm"
        tvInsightMood.text    = "Calm"
        dismissCooldownUntil  = System.currentTimeMillis() + DISMISS_CALM_MS
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // HIGH: max importance — heads-up notification with sound
            nm.createNotificationChannel(
                NotificationChannel("BABYGUARD_HIGH", "Critical Alerts",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Immediate alerts requiring action"
                    enableVibration(true)
                    setShowBadge(true)
                })
            // MEDIUM: default importance — shows in shade with sound
            nm.createNotificationChannel(
                NotificationChannel("BABYGUARD_MEDIUM", "Baby Updates",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Medium-priority baby activity updates"
                    setShowBadge(true)
                })
            // LOW: silent badge only — no sound/vibration
            nm.createNotificationChannel(
                NotificationChannel("BABYGUARD_LOW", "Baby Activity",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Low-priority informational updates"
                    setShowBadge(false)
                })
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

    // ── Event description helpers ──────────────────────────────────────────────

    /**
     * Maps tier + eventAction → a clean, parent-readable sentence.
     * Avoids the redundant "Standing: 🚨 DANGER: Standing" pattern.
     */
    private fun formatEventDescription(tier: String, eventAction: String): String = when {
        tier == "HIGH"   && eventAction == "Standing"    -> "Baby standing in crib — fall risk"
        tier == "HIGH"   && eventAction == "Face Down"   -> "Baby rolled face-down — airways may be blocked"
        tier == "HIGH"   && eventAction == "Crying"      -> "Sustained crying — baby needs attention"
        tier == "HIGH"   && eventAction == "Suffocation" -> "Baby's face hidden for 5+ seconds — check now"
        tier == "HIGH"   && eventAction == "Possible Interference" -> "Unexpected presence detected — baby's motion stopped"
        tier == "HIGH"                                   -> "Critical event — check on baby immediately"
        tier == "MEDIUM" && eventAction == "Fussy"       -> "Baby appears fussy or unsettled"
        tier == "MEDIUM" && eventAction == "Face Covered" -> "Baby's face just became covered — early warning"
        tier == "MEDIUM" && eventAction == "Restless"    -> "Baby is restless — sustained movement detected"
        tier == "MEDIUM" && eventAction == "Crying Started" -> "Baby has started crying"
        tier == "MEDIUM" && eventAction == "Extra Presence" -> "Someone or something else entered the frame"
        tier == "MEDIUM"                                 -> "Baby needs attention"
        eventAction == "Active"                          -> "Baby is awake and moving around"
        eventAction == "Sleeping"                        -> "Baby is resting peacefully"
        else                                             -> "Baby is being monitored"
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
        val inflater = layoutInflater
        for ((index, alert) in alerts.withIndex()) {
            val card = inflater.inflate(R.layout.item_log_entry, llAlertHistory, false)

            val (iconEmoji, accentColor) = when (alert.tier) {
                "HIGH"   -> "🚨" to "#FF6B6B"
                "MEDIUM" -> "⚠️" to "#FFB347"
                else     -> "✅" to "#00D4AA"
            }
            card.findViewById<TextView>(R.id.tvLogHeader).apply {
                text = "$iconEmoji  ${alert.timestamp}"
                setTextColor(Color.parseColor(accentColor))
            }
            card.findViewById<TextView>(R.id.tvLogDesc).text =
                formatEventDescription(alert.tier, alert.eventAction)

            // Thumbnail (HIGH alerts only — they include a snapshot)
            if (alert.imageBase64.isNotEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(alert.imageBase64, 0)
                    val bmp   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    card.findViewById<ImageView>(R.id.ivLogThumb).setImageBitmap(bmp)
                    card.findViewById<View>(R.id.cardLogThumb).visibility = View.VISIBLE
                } catch (_: Exception) {}
            }

            llAlertHistory.addView(card)
            popInLogEntry(card, index)
        }
    }

    /**
     * Soft "pops out" entrance for a freshly-added neumorphic log card: starts
     * slightly scaled-down/transparent and springs up to full size, giving the
     * card a soft-UI feel rather than just appearing instantly.
     */
    private fun popInLogEntry(card: View, index: Int) {
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.alpha = 0f
        card.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay((index * 40L).coerceAtMost(200L))
            .setDuration(260L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
            .start()
    }

    /**
     * Draws [bmp] into [canvas] scaled to fit inside ([viewW] x [viewH]) while preserving the
     * bitmap's aspect ratio — letterboxing instead of stretching. Rotation (from the Parent's
     * rotate button) is applied here at the canvas level, not as a View transform — rotating the
     * SurfaceView/container itself (the old approach) required a compensating non-uniform scale
     * to avoid overflow, which is exactly what stretched the picture. Rotating the canvas instead
     * keeps every pixel at 1:1 scale; only the fit box's width/height are swapped for 90/270 so
     * the aspect-fit math accounts for the rotated coordinate frame.
     */
    private fun drawFrameAspectFit(canvas: android.graphics.Canvas, bmp: Bitmap, viewW: Int, viewH: Int) {
        canvas.drawColor(Color.BLACK)
        if (viewW <= 0 || viewH <= 0) return
        val rotated = liveViewWindowRotation == 90f || liveViewWindowRotation == 270f
        val fitW = if (rotated) viewH else viewW
        val fitH = if (rotated) viewW else viewH
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = minOf(fitW / bw, fitH / bh)
        val drawW = bw * scale
        val drawH = bh * scale
        val cx = viewW / 2f
        val cy = viewH / 2f
        val dst = android.graphics.RectF(
            cx - drawW / 2f, cy - drawH / 2f,
            cx + drawW / 2f, cy + drawH / 2f)
        if (liveViewWindowRotation != 0f) {
            canvas.save()
            canvas.rotate(liveViewWindowRotation, cx, cy)
            canvas.drawBitmap(bmp, null, dst, null)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, null, dst, null)
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

                                    runOnUiThread { applyVideoContainerAspect(bmp.width, bmp.height) }

                                    // Draw frame to SurfaceView — aspect-fit, never stretched.
                                    val holder = svLiveVideo.holder
                                    val canvas  = holder.lockCanvas()
                                    if (canvas != null) {
                                        drawFrameAspectFit(canvas, bmp, svLiveVideo.width, svLiveVideo.height)
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
                    Color.parseColor("#D3D3D3"))
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
                    val dis   = DataInputStream(audioClient!!.inputStream)
                    val bSize = android.media.AudioTrack.getMinBufferSize(16000, 4, 2)
                        .coerceAtLeast(1600)  // min ~100ms buffer
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
        audioClient?.close()
        audioServerSocket?.close()
        audioServerThread?.interrupt()
    }

    // ── QR code (LAN pairing) ──────────────────────────────────────────────────

    private fun generateLanQrCode() {
        val ip = getLocalIpAddress() ?: run {
            // Crisp vector icon instead of the low-res raster ic_dialog_alert,
            // which looked blurry when stretched to the 250dp QR-code slot.
            ivQrCode.setImageResource(R.drawable.ic_alert_sharp)
            ivQrCode.scaleType = ImageView.ScaleType.CENTER_INSIDE
            return
        }
        val qrText = "babyguard://connect?ip=$ip&port=8888"
        try {
            val size = 512
            val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
            val bits  = QRCodeWriter().encode(
                qrText, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = android.graphics.Bitmap.createBitmap(size, size,
                android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until size)
                for (y in 0 until size)
                    bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
            ivQrCode.scaleType = ImageView.ScaleType.FIT_CENTER
            ivQrCode.setImageBitmap(bmp)
        } catch (_: Exception) {
            ivQrCode.setImageResource(R.drawable.ic_alert_sharp)
            ivQrCode.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null
            else "%d.%d.%d.%d".format(
                ip and 0xff, ip shr 8 and 0xff,
                ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (_: Exception) { null }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("BABYGUARD_NEW_DATA")
            addAction("BABYGUARD_HIGH_ALERT")
            addAction("BABYGUARD_CMD_RESULT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dataReceiver, filter)
        }
        handler.post(soundDecayRunnable)

        // Covers the case where a HIGH alert fired while this screen wasn't foregrounded (so the
        // broadcast above was missed) — e.g. opening the app from recents rather than tapping
        // the notification. The overlay still needs to show once the parent gets here.
        if (BabyGuardService.isHighAlertActive) {
            showActiveAlert(BabyGuardService.activeHeadline)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(dataReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(soundDecayRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(soundDecayRunnable)
        stopAudioListening()
        stopVideoServer()
        try { unregisterReceiver(dataReceiver) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pairingLayout.visibility == View.VISIBLE) {
            // Don't go back from pairing — finish the activity
            finish()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

}
