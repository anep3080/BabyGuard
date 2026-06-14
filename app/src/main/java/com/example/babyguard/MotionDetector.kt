package com.example.babyguard

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video

class MotionDetector {

    private val mog2: BackgroundSubtractorMOG2 = Video.createBackgroundSubtractorMOG2().apply {
        nMixtures = 3          // fewer Gaussians = faster, enough for indoor static scenes
        varThreshold = 24.0    // tighter pixel-variance gate = fewer shadow false positives
    }

    private val matFrame   = Mat()
    private val fgMask     = Mat()
    private val blurredFrame = Mat()

    // Adaptive MOG learning rate:
    //  - DORMANT (baby still): 0.02 — background model updates quickly to absorb slow
    //    lighting drift without saturating the foreground mask.
    //  - ACTIVE  (baby moving): 0.003 — near-frozen so the baby stays "foreground" and
    //    doesn't bleed into the background model between detections.
    private var currentLearningRate = 0.02

    fun setActiveLearningRate(isActive: Boolean) {
        currentLearningRate = if (isActive) 0.003 else 0.02
    }

    fun hasMotion(bitmap: Bitmap): Boolean = getMotionPixelCount(bitmap) > 8000

    /**
     * @param sensitivity 1=Low, 2=Normal, 3=High  (from AppPreferences)
     */
    fun getMotionPixelCount(bitmap: Bitmap, sensitivity: Int = 2): Int {
        try {
            Utils.bitmapToMat(bitmap, matFrame)

            // 7×7 blur: less aggressive than the old 9×9, preserves sharper motion edges
            // while still killing high-frequency sensor noise on the Note 9.
            Imgproc.GaussianBlur(matFrame, blurredFrame, Size(7.0, 7.0), 0.0)

            // Centre-weighted ROI — ignore 15 % border on each side
            val roi = org.opencv.core.Rect(
                (blurredFrame.cols() * 0.15).toInt(),
                (blurredFrame.rows() * 0.15).toInt(),
                (blurredFrame.cols() * 0.70).toInt(),
                (blurredFrame.rows() * 0.70).toInt()
            )
            val matRoi = blurredFrame.submat(roi)

            // Use the red channel only (index 0 of RGBA Mat from CameraX RGBA_8888 output).
            val channels = mutableListOf<Mat>()
            Core.split(matRoi, channels)
            if (channels.isNotEmpty()) {
                mog2.apply(channels[0], fgMask, currentLearningRate)
                for (m in channels) m.release()
            } else {
                mog2.apply(matRoi, fgMask, currentLearningRate)
            }
            matRoi.release()

            val rawCount = Core.countNonZero(fgMask)

            // Sensitivity-scaled noise floor — low sensitivity needs more pixels before
            // reporting any motion at all, reducing spurious triggers.
            val noiseFloor = when (sensitivity) { 1 -> 2500; 3 -> 600; else -> 1500 }
            if (rawCount < noiseFloor) return 0

            // Normalise to a virtual 1920×1080 frame so the metric is device-independent
            val totalPixels = matFrame.cols() * matFrame.rows()
            return (rawCount.toDouble() * 2_073_600.0 / totalPixels.toDouble()).toInt()

        } catch (e: Exception) {
            Log.e("BabyGuard_MOG2", "Motion detection failed: ${e.message}")
            return 0
        }
    }

    fun close() {
        matFrame.release()
        blurredFrame.release()
        fgMask.release()
        mog2.clear()
    }
}
