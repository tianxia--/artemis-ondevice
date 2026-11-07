plugins {
    id("com.android.application")
}

android {
    namespace = "com.artemis.ondevice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.artemis.ondevice"
        // AccessibilityService.takeScreenshot needs Android 11 (API 30).
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // sherpa-onnx ships native libs for four ABIs; phones are arm64, the emulator x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // UI toolkit only. The agent core (accessibility, Keystore, HttpURLConnection, org.json)
    // stays on platform APIs. Material 3 components need AppCompat; RecyclerView comes with it.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Offline wake word, speech recognition and speech synthesis (VoiceService).
    // Fetched with scripts/fetch-voice.sh together with the models in assets/voice/.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
