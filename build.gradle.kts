buildscript {
    dependencies {
        classpath(libs.android.tools.r8)
        classpath(libs.firebase.crashlytics.gradle)
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinParcelize) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

val minSdkVersion by extra(23)
val targetSdkVersion by extra(34)
val compileSdkVersion by extra(34)
val buildToolsVersion by extra("34.0.0")
val composeCompilerVersion by extra("1.5.12")