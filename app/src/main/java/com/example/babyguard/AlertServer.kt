package com.example.babyguard

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class AlertServer(private val onAlertReceived: (String) -> Unit) : Thread() {

    private var serverSocket: ServerSocket? = null
    @Volatile var isRunning = true

    override fun run() {
        try {
            serverSocket = ServerSocket(8888).apply { reuseAddress = true }
            Log.i("BabyGuard_Server", "🎧 Persistent Server active on Port 8888")

            while (isRunning) {
                val socket: Socket = serverSocket!!.accept()
                Log.d("BabyGuard_Server", "🚪 Connection established from Baby Unit")
                
                // Keep the connection open and read line-by-line
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (isRunning && !socket.isClosed) {
                    try {
                        val message = reader.readLine() ?: break
                        onAlertReceived(message)
                    } catch (e: Exception) { break }
                }
                socket.close()
                Log.d("BabyGuard_Server", "🚪 Connection closed")
            }
        } catch (e: Exception) {
            if (isRunning) Log.e("BabyGuard_Server", "❌ Server Error: ${e.message}")
        }
    }

    fun close() {
        isRunning = false
        serverSocket?.close()
    }
}