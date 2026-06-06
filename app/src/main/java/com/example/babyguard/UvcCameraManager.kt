package com.example.babyguard

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.util.Log
import com.jiangdg.ausbc.USBMonitor
import com.jiangdg.ausbc.UVCCamera
import com.jiangdg.ausbc.callback.IFrameCallback
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

class UvcCameraManager(
    private val context: Context,
    private val onFrame: (Bitmap) -> Unit
) {
    private var mUSBMonitor: USBMonitor? = null
    private var mUVCCamera: UVCCamera? = null

    private val mOnDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            mUSBMonitor?.requestPermission(device)
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
            releaseCamera()
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            
            // Typical night vision specs: 640x480 MJPEG
            camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG)
            
            camera.setFrameCallback(object : IFrameCallback {
                override fun onFrame(frame: ByteBuffer) {
                    processUvcFrame(frame)
                }
            }, UVCCamera.PIXEL_FORMAT_NV21)
            
            camera.startPreview()
            mUVCCamera = camera
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            releaseCamera()
        }

        override fun onDetach(device: UsbDevice) {
            releaseCamera()
        }

        override fun onCancel(device: UsbDevice) {}
    }

    init {
        mUSBMonitor = USBMonitor(context, mOnDeviceConnectListener)
    }

    fun start() {
        mUSBMonitor?.register()
    }

    fun stop() {
        releaseCamera()
        mUSBMonitor?.unregister()
    }

    private fun releaseCamera() {
        mUVCCamera?.stopPreview()
        mUVCCamera?.destroy()
        mUVCCamera = null
    }

    private fun processUvcFrame(frame: ByteBuffer) {
        val width = 640
        val height = 480
        val yuvData = ByteArray(frame.remaining())
        frame.get(yuvData)

        try {
            // Using OpenCV for high-speed YUV -> Bitmap conversion
            val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            yuvMat.put(0, 0, yuvData)
            
            val rgbaMat = Mat()
            Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbaMat, bitmap)
            
            // Feed directly into the BabyMonitorEngine
            onFrame(bitmap)
            
            yuvMat.release()
            rgbaMat.release()
        } catch (e: Exception) {
            Log.e("UvcCamera", "AI Frame conversion failed: ${e.message}")
        }
    }
}
