package com.example.babyguard // Make sure this matches!

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.components.containers.AudioData

class AudioListener(context: Context) {

    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var audioData: AudioData? = null

    init {
        try {
            // 1. Point to the YAMNet file
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("yamnet.tflite")
                .build()

            // 2. Configure the settings (We want the top 3 most likely sounds)
            val options = AudioClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(3)
                .build()

            // 3. Start the AI Engine
            audioClassifier = AudioClassifier.createFromOptions(context, options)

            // 4. Let MediaPipe automatically configure the phone's microphone!
            audioRecord = audioClassifier?.createAudioRecord()

            // 5. Build the AudioFormat specifically for MediaPipe (1 channel, 16kHz)
            val audioFormat = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(16000.0f) // Must be a float to satisfy the compiler!
                .build()

            audioData = AudioData.create(audioFormat, 16000)

            Log.i("BabyGuard_Audio", "YAMNet Audio AI Loaded Successfully!")

        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Error loading Audio AI: ${e.message}")
        }
    }

    // Call this once when the baby goes to sleep
    fun startListening() {
        try {
            audioRecord?.startRecording()
            Log.i("BabyGuard_Audio", "Microphone is now listening...")
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Failed to start microphone. Did you request permissions in AndroidManifest.xml?")
        }
    }

    // Call this inside a loop or timer to check the current sound
    fun isBabyCrying(): Boolean {
        if (audioClassifier == null || audioRecord == null || audioData == null) return false

        try {
            // 1. Pull the latest sound from the microphone into our data buffer
            audioData?.load(audioRecord!!)

            // 2. Ask YAMNet what it hears
            val result = audioClassifier?.classify(audioData!!)

            // 3. Dig safely through MediaPipe's nested results to find the categories list
            val categories = result?.classificationResults()?.firstOrNull()?.classifications()?.firstOrNull()?.categories() ?: emptyList()

            for (category in categories) {
                // YAMNet has a specific category called "Baby cry, infant cry"
                // If it hears a cry and is more than 30% sure, trigger the alarm!
                if (category.categoryName().contains("cry", ignoreCase = true) && category.score() > 0.3f) {
                    Log.d("BabyGuard_Audio", "🚨 SOUND DETECTED: ${category.categoryName()} (Confidence: ${category.score()})")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Audio classification error: ${e.message}")
        }
        return false
    }

    // Call this to save battery if the baby monitor is turned off
    fun stopListening() {
        audioRecord?.stop()
        audioClassifier?.close()
    }

    fun pauseListening() {
        try {
            audioRecord?.stop()
            Log.i("BabyGuard_Audio", "Audio AI Paused.")
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Error pausing audio: ${e.message}")
        }
    }

    fun resumeListening() {
        try {
            audioRecord?.startRecording()
            Log.i("BabyGuard_Audio", "Audio AI Resumed.")
        } catch (e: Exception) {
            Log.e("BabyGuard_Audio", "Error resuming audio: ${e.message}")
        }
    }
}