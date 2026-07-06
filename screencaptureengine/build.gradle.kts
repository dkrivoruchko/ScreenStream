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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
