plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.koin.compiler)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "info.dvkr.screenstream.rtsp"
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
    implementation(projects.analytics)
    implementation(projects.common)
    implementation(projects.foundation)
    implementation(projects.streaming.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.core)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.compose)
    implementation(libs.koin.annotations)

    implementation(libs.ktor.network.tls)
}
