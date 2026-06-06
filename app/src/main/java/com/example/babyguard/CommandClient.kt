package com.example.babyguard

import android.util.Log
import java.io.PrintWriter
import java.net.Socket

class CommandClient(
    private val targetIp: String,
    private val command: String
) : Thread() {
    override fun run() {
        try {
            val socket = Socket(targetIp, 8890)
            val output = PrintWriter(socket.outputStream, true)
            output.println(command)
            output.close()
            socket.close()
            Log.d("BabyGuard_CmdClient", "🎮 Command sent: $command")
        } catch (e: Exception) {
            Log.e("BabyGuard_CmdClient", "❌ Failed to send command: ${e.message}")
        }
    }
}