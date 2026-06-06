package com.example.babyguard

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Enhanced WiFi Direct Manager for BabyGuard.
 * Designed for maximum reliability in persistent monitoring scenarios.
 */
class WifiDirectManager(
    private val context: Context,
    private val isParentUnit: Boolean,
    private val onPeerListChanged: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionSuccess: (String) -> Unit
) {
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, Looper.getMainLooper(), null)
    
    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var isConnecting = false
    
    private val prefs = context.getSharedPreferences("BabyGuard_Pairing", Context.MODE_PRIVATE)
    private var lastPairedDeviceAddress: String? = prefs.getString("last_device_address", null)

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val maintenanceRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkAndMaintainConnection()
                handler.postDelayed(this, 5000) 
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAndMaintainConnection() {
        manager?.requestConnectionInfo(channel) { info ->
            if (!info.groupFormed && !isConnecting) {
                Log.d("BabyGuard_P2P", "Link down. Forcing discovery...")
                startDiscovery()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        manager?.removeGroup(channel, null)
        manager?.stopPeerDiscovery(channel, null)

        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { 
                Log.d("BabyGuard_P2P", "Discovery cycle started.") 
            }
            override fun onFailure(reason: Int) { 
                Log.e("BabyGuard_P2P", "Discovery failed: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (isConnecting) return
        isConnecting = true

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = if (isParentUnit) 15 else 0 
        }

        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { 
                Log.i("BabyGuard_P2P", "Handshake started with ${device.deviceName}")
                lastPairedDeviceAddress = device.deviceAddress
                prefs.edit().putString("last_device_address", device.deviceAddress).apply()
            }
            override fun onFailure(reason: Int) { 
                Log.e("BabyGuard_P2P", "Handshake failed: $reason")
                isConnecting = false
                startDiscovery()
            }
        })
    }

    fun register() {
        if (receiver != null) return
        isMonitoring = true
        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel) { peers ->
                            val devices = peers.deviceList.toList()
                            onPeerListChanged(devices)
                            
                            lastPairedDeviceAddress?.let { target ->
                                devices.find { it.deviceAddress == target }?.let { device ->
                                    if (device.status == WifiP2pDevice.AVAILABLE && !isConnecting) {
                                        Log.i("BabyGuard_P2P", "Found known partner. Reconnecting...")
                                        connect(device)
                                    }
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }
                        
                        if (networkInfo?.isConnected == true) {
                            isConnecting = false
                            manager?.requestConnectionInfo(channel) { info ->
                                if (info.groupFormed) {
                                    val targetIp = if (info.isGroupOwner) {
                                        "192.168.49.1" 
                                    } else {
                                        info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                                    }
                                    Log.i("BabyGuard_P2P", "Network Established. Remote IP: $targetIp")
                                    onConnectionSuccess(targetIp)
                                }
                            }
                        } else {
                            isConnecting = false
                        }
                    }
                }
            }
        }
        
        try {
            context.registerReceiver(receiver, intentFilter)
            handler.post(maintenanceRunnable)
        } catch (e: Exception) {
            Log.e("BabyGuard_P2P", "Critical registration error", e)
        }
    }

    fun unregister() {
        isMonitoring = false
        isConnecting = false
        handler.removeCallbacks(maintenanceRunnable)
        receiver?.let { 
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
    }
}