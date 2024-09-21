import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.gms.googleservices.GoogleServicesPlugin
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug-key.jks")
            storePassword = "debug-key"
            keyAlias = "debug-key"
            keyPassword = "debug-key"
        }
        //SHA1: 89:5F:34:AB:7B:EB:6B:A0:65:4E:56:CB:E4:8D:E3:22:25:29:22:FD
        //SHA256: 67:80:30:DE:17:FD:A4:B8:B2:1D:9F:D3:57:0D:5C:FB:2D:57:86:7C:46:51:70:06:22:3D:7D:1F:B0:7F:39:AC
    }

    namespace = "info.dvkr.screenstream"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "info.dvkr.screenstream"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 41012
        versionName = "4.1.12"

        // https://medium.com/@crafty/no-if-you-do-that-then-you-cant-use-newer-features-on-older-platforms-e-g-fa595333c0a4
        vectorDrawables.useSupportLibrary = true

        ndk.abiFilters.addAll(listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64"))
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            configure<CrashlyticsExtension> {
                nativeSymbolUploadEnabled = true
            }
        }
    }

    flavorDimensions += listOf("Default")
    productFlavors {
        create("FDroid") {
            dimension = "Default"
            manifestPlaceholders += mapOf("adMobPubId" to "")
        }
        create("PlayStore") {
            dimension = "Default"
            val localProps = Properties()
            File(rootProject.rootDir, "local.properties").apply { if (exists() && isFile) inputStream().use { localProps.load(it) } }
            manifestPlaceholders += mapOf("adMobPubId" to localProps.getProperty("ad.pubId", "\"\""))
            buildConfigField("String", "AD_UNIT_IDS", localProps.getProperty("ad.unitIds", "\"[]\""))
        }
    }

    googleServices {
        missingGoogleServicesStrategy = GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "custom.config.*"
            excludes += "DebugProbesKt.bin"
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.tools.desugar)

    ksp(libs.koin.ksp)

    implementation(projects.common)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.processPhoenix)

    implementation(projects.mjpeg)

    "PlayStoreImplementation"(projects.webrtc)
    "PlayStoreImplementation"(libs.play.services.tasks)
    "PlayStoreImplementation"(libs.play.app.update)
    "PlayStoreImplementation"(libs.play.app.review)
    "PlayStoreImplementation"(libs.play.services.ads)
    "PlayStoreImplementation"(libs.webkit)
    "PlayStoreImplementation"(libs.firebase.analytics)
    "PlayStoreImplementation"(libs.firebase.crashlytics)
    "PlayStoreImplementation"(libs.firebase.crashlytics.ndk)
}