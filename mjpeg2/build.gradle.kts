plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.koin.compiler)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "io.screenstream.mjpeg"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(projects.common)
    implementation(projects.streamingCore)
    implementation(projects.screenCaptureEngine)

    implementation(libs.koin.core)
    implementation(libs.koin.annotations)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
}
