plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "io.screenstream.webrtc"
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int) {
            minorApiLevel = rootProject.extra["compileSdkMinorVersion"] as Int
        }
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }
}

dependencies {
    // Vendored patched WebRTC Android runtime: 150.0.7871.63, m150 branch-heads/7871.
    compileOnly(libs.androidx.annotation)
}
