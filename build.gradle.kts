buildscript {
    dependencies {
        classpath(libs.firebase.crashlytics.gradle)
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

val minSdkVersion by extra(23)
val targetSdkVersion by extra(36)
val compileSdkVersion by extra(36)
val buildToolsVersion by extra("36.1.0")
val ndkVersion by extra("29.0.14206865")