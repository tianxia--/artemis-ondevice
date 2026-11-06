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
}
