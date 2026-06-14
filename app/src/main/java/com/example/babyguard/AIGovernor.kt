package com.example.babyguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class AIGovernor(context: Context) {

    private val prefs = AppPreferences(context)
    private var currentTemperature = 0f
    private var isThrottling = false

    // ── Mode presets ──────────────────────────────────────────────────────────
    // analysisDelayMs : how often CameraActivity calls safetyPipeline.processFrame
    // heartbeatMs     : how often SafetyPipeline forces a full YOLO scan when DORMANT
    // throttleTemp    : CPU temperature at which we halve the analysis rate

    data class ModeConfig(
        val analysisDelayMs: Long,
        val heartbeatMs: Long,
        val throttleTemp: Float,
        val throttledDelayMs: Long
    )

    private val modeConfigs = mapOf(
        AppPreferences.AIMode.ECO         to ModeConfig(2000L, 8000L, 39.0f, 5000L),
        AppPreferences.AIMode.BALANCED    to ModeConfig( 500L, 4000L, 41.0f, 1500L),
        AppPreferences.AIMode.PERFORMANCE to ModeConfig( 250L, 2000L, 42.0f,  800L)
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            currentTemperature = raw / 10f

            val threshold = currentConfig.throttleTemp
            val wasThrottling = isThrottling
            isThrottling = currentTemperature >= threshold

            if (isThrottling != wasThrottling) {
                if (isThrottling)
                    Log.w("BabyGuard_Governor",
                        "⚠️ Throttling ACTIVE: ${currentTemperature}°C ≥ ${threshold}°C")
                else
                    Log.i("BabyGuard_Governor",
                        "✅ Temperature normal: ${currentTemperature}°C")
            }
        }
    }

    init {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    private val currentConfig get() =
        modeConfigs[prefs.aiMode] ?: modeConfigs[AppPreferences.AIMode.BALANCED]!!

    /** Milliseconds CameraActivity should wait between processFrame calls. */
    fun getRequiredDelay(): Long =
        if (isThrottling) currentConfig.throttledDelayMs else currentConfig.analysisDelayMs

    /** Milliseconds SafetyPipeline waits before forcing a YOLO heartbeat scan. */
    fun getHeartbeatInterval(): Long = currentConfig.heartbeatMs

    fun isThrottlingActive(): Boolean = isThrottling
    fun getCurrentTemperature(): Float = currentTemperature
    fun getCurrentMode(): AppPreferences.AIMode = prefs.aiMode
    fun setMode(mode: AppPreferences.AIMode) { prefs.aiMode = mode }
}
