import java.util.Properties

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
    namespace = "info.dvkr.screenstream.webrtc"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    val localProps = Properties()
    File(rootProject.rootDir, "local.properties").apply { if (exists() && isFile) inputStream().use { localProps.load(it) } }

    buildTypes {
        debug {
            buildConfigField("String", "SIGNALING_SERVER", localProps.getProperty("SIGNALING_SERVER_DEV", "\"\""))
            buildConfigField("String", "CLOUD_PROJECT_NUMBER", localProps.getProperty("CLOUD_PROJECT_NUMBER_DEV", "\"\""))
        }

        release {
            buildConfigField("String", "SIGNALING_SERVER", localProps.getProperty("SIGNALING_SERVER_RELEASE", "\"\""))
            buildConfigField("String", "CLOUD_PROJECT_NUMBER", localProps.getProperty("CLOUD_PROJECT_NUMBER_RELEASE", "\"\""))
        }
    }
}

dependencies {
    implementation(projects.analytics)
    implementation(projects.common)
    implementation(projects.foundation)
    implementation(projects.streaming.core)
    implementation(projects.streaming.webrtcRuntime)

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

    implementation(libs.play.services.base)
    implementation(libs.play.integrity)
    implementation(libs.socket)
    implementation(libs.okhttp)

    // OkHttp brings Okio 3.18.1; 3.18.2 fixes Base64 padding compatibility.
    // Remove this override when the OkHttp dependency includes that fix.
    implementation(libs.okio)
}

configurations.all {
    exclude("org.json", "json")
}
