package com.example.babyguard

import android.util.Log
import java.io.PrintWriter
import java.net.Socket

// This runs on a background thread so it doesn't freeze your camera!
class AlertClient(
    private val targetIpAddress: String,
    private val port: Int,
    private val jsonPayload: String
) : Thread() {

    override fun run() {
        try {
            // 1. Run over to the S25's IP Address and knock on the Door (Port 8888)
            val socket = Socket(targetIpAddress, port)

            // 2. Slide the JSON message under the door
            val output = PrintWriter(socket.outputStream, true)
            output.println(jsonPayload)

            // 3. Walk away and close the connection
            output.close()
            socket.close()

            Log.d("BabyGuard_Client", "🚀 Successfully fired alert to Parent: $jsonPayload")

        } catch (e: Exception) {
            Log.e("BabyGuard_Client", "❌ Failed to send alert: ${e.message}")
        }
    }
}