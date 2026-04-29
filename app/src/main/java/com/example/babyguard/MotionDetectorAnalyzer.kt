package com.example.babyguard

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.video.BackgroundSubtractorMOG2
import org.opencv.video.Video

class MotionDetectorAnalyzer(private val onMotionStatusChanged: (Boolean) -> Unit) : ImageAnalysis.Analyzer {

    // 1. Initialize the MOG2 Engine
    // It will learn what the "empty" crib looks like and flag anything that moves
    private val mog2: BackgroundSubtractorMOG2 = Video.createBackgroundSubtractorMOG2()

    // How much of the screen needs to change to trigger "Motion"? (e.g., 2% of pixels)
    private val motionThreshold = 2.0

    override fun analyze(image: ImageProxy) {
        // 2. Extract the Grayscale channel (Y-plane) directly from the camera
        // This is extremely battery-efficient!
        val yBuffer = image.planes[0].buffer
        val ySize = yBuffer.remaining()
        val yArray = ByteArray(ySize)
        yBuffer.get(yArray)

        // 3. Convert that raw data into an OpenCV "Mat" (Matrix)
        val grayMat = Mat(image.height, image.width, CvType.CV_8UC1)
        grayMat.put(0, 0, yArray)

        // 4. Feed the frame to MOG2
        val fgMask = Mat()
        mog2.apply(grayMat, fgMask)

        // 5. Calculate how much motion happened
        // MOG2 outputs a black image with white pixels where motion happened.
        // We count the white (non-zero) pixels.
        val nonZeroPixels = Core.countNonZero(fgMask)
        val totalPixels = image.width * image.height
        val motionPercentage = (nonZeroPixels.toDouble() / totalPixels) * 100.0

        val isMotionDetected = motionPercentage > motionThreshold

        // Print to Logcat so we can watch the math happen in real-time
        Log.d("BabyGuard_AI", "Motion Level: ${String.format("%.2f", motionPercentage)}% -> Detected: $isMotionDetected")

        // 6. Send the result back to the Activity UI
        onMotionStatusChanged(isMotionDetected)

        // 7. CRITICAL: Clean up memory so the phone doesn't crash!
        grayMat.release()
        fgMask.release()
        image.close()
    }
}