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
    private val fgMask = Mat()
    
    @Volatile var motionThreshold = 3000 // Default sensitivity

    fun hasMotion(bitmap: Bitmap): Boolean {
        try {
            Utils.bitmapToMat(bitmap, matFrame)
            mog2.apply(matFrame, fgMask)

            val movingPixels = Core.countNonZero(fgMask)
            
            // Note: Sensitivity is usually inverse to threshold. 
            // If user sets "High Sensitivity", threshold should be lower.
            return movingPixels > motionThreshold
        } catch (e: Exception) {
            Log.e("BabyGuard_MOG2", "Motion detection failed: ${e.message}")
            return false
        }
    }

    fun close() {
        matFrame.release()
        fgMask.release()
        mog2.clear()
    }
}