package com.example.babyguard

import android.util.Log
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

class AlertClient(private val ip: String, private val port: Int) : Thread() {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val queue = LinkedBlockingQueue<String>()
    @Volatile var isRunning = true

    fun send(json: String) {
        queue.offer(json)
    }

    override fun run() {
        while (isRunning) {
            try {
                if (socket == null || socket!!.isClosed) {
                    socket = Socket(ip, port)
                    writer = PrintWriter(socket!!.outputStream, true)
                    Log.i("BabyGuard_Client", "🚀 Persistent connection established to $ip")
                }

                val payload = queue.take() // Blocks until data available
                writer?.println(payload)
                
            } catch (e: Exception) {
                Log.e("BabyGuard_Client", "⚠️ Connection error: ${e.message}. Retrying in 2s...")
                socket?.close()
                socket = null
                Thread.sleep(2000)
            }
        }
    }

    fun stopClient() {
        isRunning = false
        socket?.close()
        this.interrupt()
    }
}