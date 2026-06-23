package com.example.babyguard

import android.util.Log
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * @param onCommand Invoked (on a background reader thread, NOT the caller's thread) for every
 *                   line the Parent writes back on this same socket — e.g. rotate / switch_lens
 *                   commands. Optional: pass null if this client only ever sends, never receives.
 * @param onConnected Invoked once the TCP socket to the Parent actually completes, not when this
 *                     object is merely constructed/started. Callers that flip pairing/"Connected"
 *                     UI state immediately on construction were lying to the user whenever the
 *                     connection then failed (wrong IP, AP isolation, Parent not reachable) — the
 *                     Baby Unit would say "Connected" forever while silently retrying underneath.
 * @param onDisconnected Invoked every time a connection attempt fails or a live connection drops,
 *                        with a short reason string. Lets the UI show "still trying" instead of a
 *                        stale "Connected" that no longer reflects reality.
 */
class AlertClient(
    private val ip: String,
    private val port: Int,
    private val onCommand: ((String) -> Unit)? = null,
    private val onConnected: (() -> Unit)? = null,
    private val onDisconnected: ((String) -> Unit)? = null
) : Thread() {
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val queue = LinkedBlockingQueue<String>()
    @Volatile var isRunning = true
    private var readerThread: Thread? = null

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
                    startCommandReader(socket!!)
                    onConnected?.invoke()
                }

                val payload = queue.take() // Blocks until data available
                writer?.println(payload)

                // PrintWriter.println() does NOT throw on a broken/dead connection — it silently
                // swallows the IOException and only records it internally (same caveat as
                // AlertServer.sendCommand). Without this check, a connection the Parent silently
                // dropped (its process restarted, or it explicitly forced a reconnect) would never
                // be noticed here: this loop would keep "successfully" writing telemetry into a
                // dead socket forever, while the Parent's AlertServer — which DOES detect the
                // drop on its read side — reports "Baby Unit not connected" for every remote
                // command even though this side still thinks it's connected. checkError() is the
                // only way to surface that and force a real reconnect.
                if (writer?.checkError() == true) {
                    throw java.io.IOException("Write failed — connection is stale")
                }

            } catch (e: Exception) {
                Log.e("BabyGuard_Client", "⚠️ Connection error: ${e.message}. Retrying in 2s...")
                onDisconnected?.invoke(e.message ?: "Connection failed")
                socket?.close()
                socket = null
                Thread.sleep(2000)
            }
        }
    }

    /** Reads Parent → Baby Unit commands off the same persistent socket, line by line. */
    private fun startCommandReader(sock: Socket) {
        readerThread = Thread {
            try {
                val reader = sock.inputStream.bufferedReader()
                while (isRunning && !sock.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onCommand?.invoke(line)
                }
            } catch (_: Exception) {
                // Connection dropped — the outer run() loop already handles reconnect/retry.
            }
        }.apply { isDaemon = true; start() }
    }

    fun stopClient() {
        isRunning = false
        socket?.close()
        this.interrupt()
    }
}
