package com.example.babyguard

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class CommandServer(private val onCommandReceived: (String) -> Unit) : Thread() {
    private var serverSocket: ServerSocket? = null
    @Volatile var isRunning = true

    override fun run() {
        try {
            serverSocket = ServerSocket(8890) // Command port
            Log.i("BabyGuard_CmdServer", "📡 Command Server listening on Port 8890...")

            while (isRunning) {
                val socket: Socket = serverSocket!!.accept()
                val input = BufferedReader(InputStreamReader(socket.inputStream))
                val command = input.readLine()

                if (command != null) {
                    Log.d("BabyGuard_CmdServer", "🎮 Received Command: $command")
                    onCommandReceived(command)
                }

                input.close()
                socket.close()
            }
        } catch (e: Exception) {
            if (isRunning) Log.e("BabyGuard_CmdServer", "❌ Command Server Error: ${e.message}")
        }
    }

    fun close() {
        isRunning = false
        serverSocket?.close()
    }
}