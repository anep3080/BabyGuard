package com.example.babyguard

import android.graphics.Bitmap
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

    fun scanForLanIp(bitmap: Bitmap): String? {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = Tasks.await(scanner.process(image))

            for (barcode in barcodes) {
                val rawValue = barcode.rawValue
                // Look for the LAN tag the S25 just generated!
                if (rawValue != null && rawValue.startsWith("LAN:")) {
                    return rawValue.removePrefix("LAN:") // Strip the tag to get the raw IP
                }
            }
        } catch (e: Exception) {
            Log.e("BabyGuard_QR", "❌ QR Scan error: ${e.message}")
        }
        return null
    }

    fun close() {
        scanner.close()
    }
}