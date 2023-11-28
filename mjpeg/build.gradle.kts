plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.devtools.ksp")
}

android {
    namespace = "info.dvkr.screenstream.mjpeg"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 23
    }

    androidResources {
        ignoreAssetsPattern = "!dev"
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

    // Temp fix for https://github.com/afollestad/material-dialogs/issues/1825
    compileOnly(fileTree("libs/bottomsheets-release.aar"))

    implementation("io.ktor:ktor-server-cio:2.3.6")
    implementation("io.ktor:ktor-server-compression:2.3.6")
    implementation("io.ktor:ktor-server-caching-headers:2.3.6")
    implementation("io.ktor:ktor-server-default-headers:2.3.6")
    implementation("io.ktor:ktor-server-forwarded-header:2.3.6")
    implementation("io.ktor:ktor-server-websockets:2.3.6")
    implementation("io.ktor:ktor-server-status-pages:2.3.6")
}