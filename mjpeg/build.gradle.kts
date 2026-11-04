plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "info.dvkr.screenstream.mjpeg"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        ignoreAssetsPattern = "!dev"
    }
}

dependencies {
    implementation(projects.common)

    implementation(libs.ktor.server.netty) {
        // Desktop/server native transports and QUIC: not usable on Android, and they bloat/conflict in the APK
        exclude(group = "io.netty", module = "netty-codec-native-quic")
        exclude(group = "io.netty", module = "netty-transport-native-epoll")
        exclude(group = "io.netty", module = "netty-transport-native-kqueue")
    }
    // Ktor probes KQueue/Epoll.isAvailable(); keep the pure-Java class jars (no native code), which report unavailable
    implementation("io.netty:netty-transport-classes-kqueue:4.2.17.Final")
    implementation("io.netty:netty-transport-classes-epoll:4.2.17.Final")
    implementation(libs.ktor.network.tls.certificates)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.caching.headers)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
}

configurations.all {
    exclude("org.fusesource.jansi", "jansi")
}
