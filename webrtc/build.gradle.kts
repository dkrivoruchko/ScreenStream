import java.util.Properties

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinParcelize)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "info.dvkr.screenstream.webrtc"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        buildConfig = true
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        freeCompilerArgs += "-Xexplicit-api=strict"
    }

    composeCompiler {
        enableStrongSkippingMode = true
    }
}

dependencies {
    implementation(project(":common"))

    ksp(libs.koin.ksp)

    implementation(libs.play.services.tasks)
    implementation(libs.play.integrity)

    implementation(libs.webrtc)
    implementation(libs.socket)
    implementation(libs.okhttp)
}

configurations.all {
    exclude("androidx.fragment", "fragment")
    exclude("org.json", "json")
}