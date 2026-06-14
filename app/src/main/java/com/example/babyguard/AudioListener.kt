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

/**
 * Single-AudioRecord audio engine.
 *
 * PROBLEM with the old design: two AudioRecord instances (mainAudioRecord for YAMNet,
 * meterAudioRecord for amplitude) plus a THIRD in CameraActivity.startAudioStreaming()
 * all tried to capture from MIC simultaneously. Android only allows one active capture
 * at a time — the others fail silently, so the meter always showed 0 and the classifier
 * never ran.
 *
 * FIX: one AudioRecord, one background thread, three consumers:
 *  1. RMS amplitude   → [lastAmplitude]  read on any thread
 *  2. YAMNet window   → [isCryingDetected] updated every 1 s
 *  3. Streaming       → [onAudioChunk] callback (CameraActivity sets this instead of
 *                        creating its own AudioRecord)
 */
class AudioListener(private val context: Context) {

    companion object {
        const val SAMPLE_RATE  = 16_000
        const val CLASSIFY_WIN = 16_000   // 1-second window for YAMNet
        private const val TAG  = "BabyGuard_Audio"
    }

    // ── Shared state (volatile — written by processing thread, read on UI/main) ──
    @Volatile var lastAmplitude: Int = 0
        private set
    @Volatile var isCryingDetected: Boolean = false
        private set

    /** Called with (chunk, samplesRead) for every audio chunk.
     *  CameraActivity sets this instead of owning its own AudioRecord. */
    @Volatile var onAudioChunk: ((ShortArray, Int) -> Unit)? = null

    // ── Sensitivity gate (0–100 scale, audio alert fires when amplitude > gate) ──
    @Volatile var alertSensitivity: Int = 40   // default: alert when amplitude > 40 %

    private var audioClassifier: AudioClassifier? = null
    @Volatile private var isRunning = false
    private var processingThread: Thread? = null

    // ── Init classifier (no AudioRecord created here) ─────────────────────────
    init {
        try {
            val opts = AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
                .setMaxResults(3).build()
            audioClassifier = AudioClassifier.createFromOptions(context, opts)
            Log.i(TAG, "YAMNet classifier initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Classifier init failed: ${e.message}")
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRunning) return
        isRunning = true

        processingThread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Keep at least 200 ms in the hardware buffer to avoid overruns on slow devices
            val bufSize = maxOf(minBuf, 3_200)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize * 2
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord create failed: ${e.message}")
                isRunning = false
                return@Thread
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (no MIC permission?)")
                record.release(); isRunning = false; return@Thread
            }

            record.startRecording()
            Log.i(TAG, "AudioRecord started — buffer $bufSize samples")

            // Chunk size: ~100 ms of audio
            val chunkSize = SAMPLE_RATE / 10
            val chunk     = ShortArray(chunkSize)

            // 1-second accumulator for YAMNet
            val classifyBuf = ShortArray(CLASSIFY_WIN)
            var classifyPos = 0

            while (isRunning) {
                val read = record.read(chunk, 0, chunkSize)
                if (read <= 0) continue

                // ── 1. RMS amplitude (log-scale 0-100) ───────────────────────
                var sumSq = 0.0
                for (i in 0 until read) sumSq += chunk[i] * chunk[i].toDouble()
                val rms = Math.sqrt(sumSq / read)
                // Log scale: quiet room (~50 RMS) → 0%
                //            normal voice (~500 RMS) → 36%
                //            baby cry (~5 000 RMS) → 78%
                //            peak (32 768 RMS) → 100%
                // The old linear scale (rms/327.68) made quiet sounds invisible —
                // a normal room at RMS 300 would only show 0.9% → rounds to 0.
                lastAmplitude = if (rms < 50.0) 0
                    else ((Math.log10(rms) - 1.70) / 2.82 * 100).toInt().coerceIn(0, 100)

                // ── 2. Forward to streaming callback ────────────────────────
                onAudioChunk?.invoke(chunk, read)

                // ── 3. Accumulate for YAMNet (1-second window) ──────────────
                var src = 0
                while (src < read && classifyPos < CLASSIFY_WIN) {
                    classifyBuf[classifyPos++] = chunk[src++]
                }
                if (classifyPos >= CLASSIFY_WIN) {
                    runClassifier(classifyBuf.copyOf())
                    classifyPos = 0
                }
            }

            record.stop()
            record.release()
            Log.i(TAG, "AudioRecord stopped")
        }
        processingThread?.isDaemon = true
        processingThread?.start()
    }

    fun stopListening() {
        isRunning = false
        onAudioChunk = null
        try { processingThread?.join(2000) } catch (_: InterruptedException) {}
        audioClassifier?.close()
        audioClassifier = null
    }

    /** Pause without tearing down the thread (used when streaming takes over). */
    fun pauseListening()  { /* no-op: CameraActivity controls streaming via callback */ }
    fun resumeListening() { if (!isRunning) startListening() }

    /** Caller convenience (matches old API) */
    fun isBabyCrying()       = isCryingDetected
    fun getLatestAmplitude() = lastAmplitude

    // ── Private ────────────────────────────────────────────────────────────────

    private fun runClassifier(samples: ShortArray) {
        val classifier = audioClassifier ?: return
        try {
            val format = AudioData.AudioDataFormat.builder()
                .setNumOfChannels(1)
                .setSampleRate(SAMPLE_RATE.toFloat())
                .build()
            val ad = AudioData.create(format, samples.size)

            // AudioData.load() has ShortArray and FloatArray overloads — use ShortArray directly.
            ad.load(samples, 0, samples.size)

            val result = classifier.classify(ad)
            isCryingDetected = result.classificationResults()
                ?.firstOrNull()
                ?.classifications()?.firstOrNull()?.categories()
                ?.any { it.categoryName().contains("cry", ignoreCase = true) && it.score() > 0.40f }
                ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Classifier error: ${e.message}")
        }
    }
}
