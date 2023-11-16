plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "info.dvkr.screenstream.common"
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
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
}

dependencies {
    api("io.insert-koin:koin-android:3.5.0")
    api("io.insert-koin:koin-annotations:1.3.0")
    ksp("io.insert-koin:koin-ksp-compiler:1.3.0")

    api("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    api("androidx.core:core-ktx:1.12.0")
    api("androidx.activity:activity-ktx:1.8.1")
    api("androidx.fragment:fragment-ktx:1.6.2")
    api("androidx.appcompat:appcompat:1.6.1")
    api("androidx.constraintlayout:constraintlayout:2.1.4")
    api("androidx.recyclerview:recyclerview:1.3.2")
    api("com.google.android.material:material:1.11.0-beta01")
    api("androidx.window:window:1.2.0")

    api("com.afollestad.material-dialogs:core:3.3.0")
    api("com.afollestad.material-dialogs:color:3.3.0")
    api("com.afollestad.material-dialogs:input:3.3.0")
    api("com.afollestad.material-dialogs:lifecycle:3.3.0")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    api("androidx.datastore:datastore-preferences:1.0.0")
    api("com.elvishew:xlog:1.11.0")
}