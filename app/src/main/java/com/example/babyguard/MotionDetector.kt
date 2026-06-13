package com.example.babyguard

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video

class MotionDetector {
    private val mog2: BackgroundSubtractorMOG2 = Video.createBackgroundSubtractorMOG2()
    private val matFrame = Mat()
    private val redChannel = Mat()
    private val fgMask = Mat()

    fun hasMotion(bitmap: Bitmap): Boolean {
        try {
            Utils.bitmapToMat(bitmap, matFrame)
            
            // 2.1 RED-CHANNEL OPTIMIZATION: Extract red channel (Index 0 in RGB/RGBA Mat)
            // This reduces pixel data processing by 66% and works best with nightlights
            val channels = mutableListOf<Mat>()
            Core.split(matFrame, channels)
            if (channels.isNotEmpty()) {
                mog2.apply(channels[0], fgMask)
                for (m in channels) m.release() // Free memory
            } else {
                mog2.apply(matFrame, fgMask)
            }

            val movingPixels = Core.countNonZero(fgMask)
            val motionThreshold = 3000 // Tweak this if it's too sensitive!

            return movingPixels > motionThreshold
        } catch (e: Exception) {
            Log.e("BabyGuard_MOG2", "Motion detection failed: ${e.message}")
            return false
        }
    }

    fun close() {
        matFrame.release()
        redChannel.release()
        fgMask.release()
        mog2.clear()
    }
}