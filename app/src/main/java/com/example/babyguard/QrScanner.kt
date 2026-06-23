package com.example.babyguard

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class QrScanner {
    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    /**
     * Scans a bitmap for a BabyGuard pairing QR code.
     * Returns a [ScanResult] on match, null otherwise.
     *
     * Recognised QR formats:
     *  • "LAN:x.x.x.x"                              → regular WiFi LAN pairing (legacy)
     *  • "babyguard://connect?ip=x.x.x.x&port=8888"  → actual format emitted by
     *    ParentActivity.generateLanQrCode() — this is the one real QR codes use.
     */
    fun scan(bitmap: Bitmap): ScanResult? {
        try {
            val image    = InputImage.fromBitmap(bitmap, 0)
            val barcodes = Tasks.await(scanner.process(image))
            for (barcode in barcodes) {
                val raw = barcode.rawValue ?: continue
                when {
                    raw.startsWith("LAN:") ->
                        return ScanResult(raw.removePrefix("LAN:"))
                    raw.startsWith("babyguard://") -> {
                        val uri = Uri.parse(raw)
                        val ip = uri.getQueryParameter("ip")
                        if (!ip.isNullOrBlank()) {
                            return ScanResult(ip)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BabyGuard_QR", "QR scan error: ${e.message}")
        }
        return null
    }

    /** Backwards-compatible: returns IP string for LAN QR only. */
    fun scanForLanIp(bitmap: Bitmap): String? = scan(bitmap)?.ip

    data class ScanResult(val ip: String)

    fun close() {
        scanner.close()
    }
}