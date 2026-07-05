plugins {
    alias(libs.plugins.androidLibrary)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "dev.dmkr.screencaptureengine"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.window:window:1.5.1")
}
