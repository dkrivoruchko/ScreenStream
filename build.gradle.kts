plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

val minSdkVersion by extra(24)
val targetSdkVersion by extra(37)
val compileSdkVersion by extra(37)
val buildToolsVersion by extra("37.0.0")
val ndkVersion by extra("29.0.14206865")
