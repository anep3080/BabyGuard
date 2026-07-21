plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.babyguard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }

        androidResources {
            noCompress += "tflite"
        }
    }

    defaultConfig {
        applicationId = "com.example.babyguard"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── ABI filter: arm64-v8a only ────────────────────────────────────
        // Strips armeabi-v7a / x86 / x86_64 native libs from OpenCV,
        // TFLite, TFLite-GPU, MediaPipe, USB camera — saves ~60–80 MB.
        // arm64-v8a covers all modern Android phones (S25, Note 9, etc.).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // isShrinkResources = true     // re-enable once keep rules are verified
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // CameraX core library
    val camerax_version = "1.3.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
    implementation(project(":opencv"))
    // TensorFlow Lite for YOLOv8
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-support-api")
    }
    // MediaPipe for Facial/Pose Landmarks
    implementation("com.google.mediapipe:tasks-audio:0.10.32")
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("org.tensorflow:tensorflow-lite-api:2.17.0")
    // QR Code Generation & Scanning
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    implementation("com.github.fornewid:neumorphism:0.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    // Real UVC (USB Video Class) camera decode/preview — replaces the old detection-only
    // USB camera stub. Pinned to 3.2.7: the latest tag that both builds successfully on
    // JitPack and whose MultiCameraClient/IDeviceConnectCallBack API was verified directly
    // against this exact tag's source (newer tags 3.2.8+ fail to build on JitPack).
    //
    // libausbc declares `api 'com.gyf.immersionbar:immersionbar:3.0.0'` and
    // `implementation 'com.zlc.glide:webpdecoder:1.6.4.9.0'` as transitive deps. Both were
    // only ever published to jcenter, which has been shut down, so they 404 on every
    // configured repo (google/mavenCentral/jitpack) and fail the build. Neither is used by
    // MultiCameraClient/Camera (the only classes this app calls into) — immersionbar is a
    // status-bar-tinting helper used by AUSBC's own demo Activities, and webpdecoder is a
    // Glide module for decoding .webp images, used by AUSBC's UI widgets. Excluding both
    // transitive deps removes the unresolvable artifacts without touching the UVC code path.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7") {
        exclude(group = "com.gyf.immersionbar")
        exclude(group = "com.zlc.glide", module = "webpdecoder")
    }
    // libausbc declares its dependency on :libuvc (the module that actually contains
    // com.serenegiant.usb.USBMonitor/UVCCamera) as `implementation`, not `api`. That scope
    // doesn't propagate to compileClasspath of consumers, which is why
    // `import com.serenegiant.usb.USBMonitor` fails with "Unresolved reference" even though
    // libausbc itself resolves fine. Declaring it directly here puts it on our own compile
    // classpath. Same version tag as libausbc so the published artifact is guaranteed to exist.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7")
}