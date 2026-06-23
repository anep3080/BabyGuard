package com.example.babyguard

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

class AlertServer(private val onAlertReceived: (String) -> Unit) : Thread() {

    private var serverSocket: ServerSocket? = null
    @Volatile var isRunning = true

    // The Baby Unit connects to us as a client (its IP is never known to the Parent ahead of
    // time), so the only way to send it commands is to keep this accepted socket around and
    // write back into it — same connection, just used in both directions.
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var activeWriter: PrintWriter? = null

    override fun run() {
        try {
            serverSocket = ServerSocket(8888).apply { reuseAddress = true }
            Log.i("BabyGuard_Server", "🎧 Persistent Server active on Port 8888")

            while (isRunning) {
                val socket: Socket = serverSocket!!.accept()
                Log.d("BabyGuard_Server", "🚪 Connection established from Baby Unit")
                activeSocket = socket
                activeWriter = PrintWriter(socket.outputStream, true)

                // Keep the connection open and read line-by-line
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (isRunning && !socket.isClosed) {
                    try {
                        val message = reader.readLine() ?: break
                        onAlertReceived(message)
                    } catch (e: Exception) { break }
                }
                if (activeSocket === socket) {
                    activeSocket = null
                    activeWriter = null
                }
                socket.close()
                Log.d("BabyGuard_Server", "🚪 Connection closed")
            }
        } catch (e: Exception) {
            if (isRunning) Log.e("BabyGuard_Server", "❌ Server Error: ${e.message}")
        }
    }

    /**
     * Sends a one-line JSON command to the currently-connected Baby Unit, if any.
     * Returns false if there's no live connection to send on, or if the write failed.
     *
     * IMPORTANT: PrintWriter.println() does NOT throw on a broken/dead connection — it silently
     * swallows the IOException and only records it internally, so a plain try/catch around
     * println() can never detect a dropped socket. checkError() is the only way to surface that
     * failure. Without this, a stale connection (e.g. the Baby Unit's process was killed or the
     * network dropped without a clean TCP close) would make every remote command silently no-op
     * forever, since activeSocket/activeWriter never get cleared and accept() never runs again
     * for a fresh connection.
     */
    fun sendCommand(json: String): Boolean {
        val writer = activeWriter ?: return false
        return try {
            writer.println(json)
            if (writer.checkError()) {
                Log.e("BabyGuard_Server", "⚠️ sendCommand: write failed, dropping stale connection")
                try { activeSocket?.close() } catch (_: Exception) {}
                activeSocket = null
                activeWriter = null
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e("BabyGuard_Server", "⚠️ sendCommand failed: ${e.message}")
            false
        }
    }

    /**
     * Manual "Reconnect" action from the Parent UI. Closes the current accepted socket (if any)
     * without touching the listening serverSocket, so the run() loop's accept() call immediately
     * picks up a fresh incoming connection from the Baby Unit instead of waiting on whatever the
     * old one was doing. Combined with AlertClient's checkError() check, the Baby Unit notices
     * the drop on its next write and redials within ~2s.
     */
    fun forceReconnect() {
        Log.i("BabyGuard_Server", "🔄 Manual reconnect requested — dropping current connection")
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        activeWriter = null
    }

    fun close() {
        isRunning = false
        try { activeSocket?.close() } catch (_: Exception) {}
        serverSocket?.close()
    }
}
