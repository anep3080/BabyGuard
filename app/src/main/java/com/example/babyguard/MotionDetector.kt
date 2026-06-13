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
        return getMotionPixelCount(bitmap) > 2500
    }

    fun getMotionPixelCount(bitmap: Bitmap): Int {
        try {
            Utils.bitmapToMat(bitmap, matFrame)
            
            // GRID-ROI: Focus on center 70% to ignore background movement
            val roi = org.opencv.core.Rect(
                (matFrame.cols() * 0.15).toInt(),
                (matFrame.rows() * 0.15).toInt(),
                (matFrame.cols() * 0.70).toInt(),
                (matFrame.rows() * 0.70).toInt()
            )
            val matRoi = matFrame.submat(roi)

            // 2.1 RED-CHANNEL OPTIMIZATION: Extract red channel (Index 0 in RGB/RGBA Mat)
            val channels = mutableListOf<Mat>()
            Core.split(matRoi, channels)
            
            if (channels.isNotEmpty()) {
                mog2.apply(channels[0], fgMask)
                for (m in channels) m.release() // Free memory
            } else {
                mog2.apply(matRoi, fgMask)
            }
            matRoi.release()

            return Core.countNonZero(fgMask)
        } catch (e: Exception) {
            Log.e("BabyGuard_MOG2", "Motion detection failed: ${e.message}")
            return 0
        }
    }

    fun close() {
        matFrame.release()
        redChannel.release()
        fgMask.release()
        mog2.clear()
    }
}