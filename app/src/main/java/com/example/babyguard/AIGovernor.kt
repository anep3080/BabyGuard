package com.example.babyguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

class AIGovernor(context: Context) {

    private var currentTemperature = 0f
    private var isThrottling = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            currentTemperature = temp / 10f // Convert to Celsius
            
            // 5.1 Dynamic Throttling: Threshold 38.0°C
            val previousThrottling = isThrottling
            isThrottling = currentTemperature >= 38.0f
            
            if (isThrottling != previousThrottling) {
                if (isThrottling) {
                    Log.w("BabyGuard_Governor", "⚠️ Thermal Throttling ACTIVE: ${currentTemperature}°C. Slowing AI to 1 FPS.")
                } else {
                    Log.i("BabyGuard_Governor", "✅ Temperature safe: ${currentTemperature}°C. AI performance restored.")
                }
            }
        }
    }

    init {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    /**
     * Returns the required delay between AI vision frames in milliseconds.
     * Normal: ~333ms (3 FPS)
     * Throttled: 1000ms (1 FPS)
     */
    fun getRequiredDelay(): Long {
        return if (isThrottling) 1000L else 333L
    }

    fun isThrottlingActive(): Boolean = isThrottling
    
    fun getCurrentTemperature(): Float = currentTemperature
}