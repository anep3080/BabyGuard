package com.example.babyguard

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

// We extend "Thread" so this runs completely in the background without freezing the S25!
class AlertServer(private val onAlertReceived: (String) -> Unit) : Thread() {

    private var serverSocket: ServerSocket? = null
    @Volatile var isRunning = true

    override fun run() {
        try {
            // 1. Open Port (Door) 8888 on the Parent Unit
            serverSocket = ServerSocket(8888)
            Log.i("BabyGuard_Server", "🎧 Server listening on Port 8888...")

            while (isRunning) {
                // 2. This line pauses the background thread and waits.
                // It stays frozen here until the Baby Unit knocks on the door!
                val socket: Socket = serverSocket!!.accept()
                Log.d("BabyGuard_Server", "🚪 Knock received! Opening door...")

                // 3. Read the JSON message that was slid under the door
                val input = BufferedReader(InputStreamReader(socket.inputStream))
                val message = input.readLine()

                if (message != null) {
                    Log.d("BabyGuard_Server", "📩 Received Data: $message")

                    // 4. Send the JSON string back to the ParentActivity so you can see it
                    onAlertReceived(message)
                }

                // 5. Close the door for this specific message (we'll open a new one instantly for the next alert)
                input.close()
                socket.close()
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e("BabyGuard_Server", "❌ Server Error: ${e.message}")
            } else {
                Log.d("BabyGuard_Server", "🛑 Server shut down safely.")
            }
        }
    }

    fun close() {
        isRunning = false
        serverSocket?.close()
    }
}