plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "info.dvkr.screenstream.webrtc"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        languageVersion = "1.9"
    }
}

dependencies {
    implementation(project(":common"))

    //noinspection KtxExtensionAvailable
    implementation("androidx.core:core:1.10.1")

    implementation("io.github.webrtc-sdk:android:114.5735.02")
    implementation("io.socket:socket.io-client:2.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.squareup.okio:okio:3.5.0")

    implementation("com.google.android.gms:play-services-basement:18.2.0")
    implementation("com.google.android.play:integrity:1.2.0")
}

configurations.implementation {
    exclude("androidx.collection", "collection")
    exclude("androidx.fragment", "fragment")
    exclude("org.json", "json")
}