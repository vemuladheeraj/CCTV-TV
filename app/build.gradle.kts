plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.cctvtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cctvtv"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
}