plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

extra.set("minSdkVersion", 26) // Netty 4.2 (HTTPS server) needs API 26+
extra.set("targetSdkVersion", 37)
extra.set("compileSdkVersion", 37)
extra.set("buildToolsVersion", "37.0.0")
extra.set("ndkVersion", "29.0.14206865")
