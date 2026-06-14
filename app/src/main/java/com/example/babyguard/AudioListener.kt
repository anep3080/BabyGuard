package com.example.babyguard

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.components.containers.AudioData

class AudioListener(context: Context) {

    private var audioClassifier: AudioClassifier? = null
    private var mainAudioRecord: AudioRecord? = null
    private var meterAudioRecord: AudioRecord? = null
    private var audioData: AudioData? = null
    private var lastAmplitude = 0

    init {
        try {
            val baseOptions = BaseOptions.builder().setModelAssetPath("yamnet.tflite").build()
            val options = AudioClassifierOptions.builder().setBaseOptions(baseOptions).setMaxResults(3).build()
            audioClassifier = AudioClassifier.createFromOptions(context, options)
            
            // MediaPipe Record (for AI)
            mainAudioRecord = audioClassifier?.createAudioRecord()

            // Dedicated Meter Record (to prevent data race)
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            meterAudioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

            val audioFormat = AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(16000.0f).build()
            audioData = AudioData.create(audioFormat, 16000)
            
            Log.i("BabyGuard_Audio", "Audio Engine Init Successful")
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Error init Audio: ${e.message}")
        }
    }

    fun startListening() {
        try {
            mainAudioRecord?.startRecording()
            meterAudioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Failed to start mic: ${e.message}")
        }
    }

    fun isBabyCrying(): Boolean {
        if (audioClassifier == null || mainAudioRecord == null || audioData == null) return false
        try {
            // Update Volume Meter (Using dedicated record)
            val buffer = ShortArray(1024)
            val read = meterAudioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                var max = 0
                for (i in 0 until read) {
                    val abs = Math.abs(buffer[i].toInt())
                    if (abs > max) max = abs
                }
                lastAmplitude = (max * 100 / 16384).coerceAtMost(100)
            }

            // Run AI (Using main record)
            audioData?.load(mainAudioRecord!!)
            val result = audioClassifier?.classify(audioData!!)
            val categories = result?.classificationResults()?.firstOrNull()?.classifications()?.firstOrNull()?.categories() ?: emptyList()
            for (category in categories) {
                if (category.categoryName().contains("cry", ignoreCase = true) && category.score() > 0.40f) return true
            }
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Audio processing error: ${e.message}")
        }
        return false
    }

    fun getLatestAmplitude(): Int = lastAmplitude

    fun stopListening() {
        try { mainAudioRecord?.stop(); meterAudioRecord?.stop() } catch (_: Exception) {}
        audioClassifier?.close()
    }

    fun pauseListening() {
        try { mainAudioRecord?.stop(); meterAudioRecord?.stop() } catch (_: Exception) {}
    }

    fun resumeListening() {
        try { mainAudioRecord?.startRecording(); meterAudioRecord?.startRecording() } catch (_: Exception) {}
    }
}