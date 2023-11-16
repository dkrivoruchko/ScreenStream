import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    namespace = "info.dvkr.screenstream.webrtc"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 23
    }

    val localProps = Properties().apply { file("../local.properties").inputStream().use { load(it) } }

    buildTypes {
        debug {
            buildConfigField("String", "SIGNALING_SERVER", localProps.getProperty("SIGNALING_SERVER_DEV"))
            buildConfigField("String", "CLOUD_PROJECT_NUMBER", localProps.getProperty("CLOUD_PROJECT_NUMBER_DEV"))
        }

        release {
            buildConfigField("String", "SIGNALING_SERVER", localProps.getProperty("SIGNALING_SERVER_RELEASE"))
            buildConfigField("String", "CLOUD_PROJECT_NUMBER", localProps.getProperty("CLOUD_PROJECT_NUMBER_RELEASE"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        languageVersion = "1.9"
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
}

dependencies {
    implementation(project(":common"))

    ksp("io.insert-koin:koin-ksp-compiler:1.3.0")

    implementation("io.github.webrtc-sdk:android:114.5735.06")
    implementation("io.socket:socket.io-client:2.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.google.android.gms:play-services-basement:18.2.0")
    implementation("com.google.android.play:integrity:1.3.0")
}

configurations.implementation {
    exclude("org.json", "json")
}