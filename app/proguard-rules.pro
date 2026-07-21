# ── TensorFlow Lite ──────────────────────────────────────────────────────────
-keep class org.tensorflow.** { *; }
-keepclassmembers class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# ── TFLite GPU Delegate ───────────────────────────────────────────────────────
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }

# ── MediaPipe ─────────────────────────────────────────────────────────────────
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ── ML Kit (face detection + barcode) ────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-dontwarn com.google.mlkit.**

# ── OpenCV ────────────────────────────────────────────────────────────────────
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# ── ZXing (QR) ────────────────────────────────────────────────────────────────
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ── USB Camera (libausbc / libuvc) ────────────────────────────────────────────
-keep class com.jiangdg.** { *; }
-keep class com.serenegiant.** { *; }
-dontwarn com.jiangdg.**
-dontwarn com.serenegiant.**

# ── CameraX ───────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Keep line numbers for crash reports ───────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
