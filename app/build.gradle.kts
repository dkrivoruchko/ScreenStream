import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.gms.googleservices.GoogleServicesPlugin
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.koin.compiler)
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
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int) {
            minorApiLevel = rootProject.extra["compileSdkMinorVersion"] as Int
        }
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        applicationId = "info.dvkr.screenstream"
        minSdk = rootProject.extra["minSdkVersion"] as Int
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        versionCode = 45002
        versionName = "4.5.2"

        ndk.abiFilters.addAll(listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64"))
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
//            baselineProfile.automaticGenerationDuringBuild = true
        }
    }

    flavorDimensions += listOf("Default")
    productFlavors {
        create("FDroid") {
            dimension = "Default"
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
        }
        create("PlayStore") {
            dimension = "Default"
            val localProps = Properties()
            File(rootProject.rootDir, "local.properties").apply { if (exists() && isFile) inputStream().use { localProps.load(it) } }
            manifestPlaceholders += mapOf("adMobPubId" to localProps.getProperty("ad.pubId", "\"\""))
            buildConfigField("String", "AD_UNIT_IDS", localProps.getProperty("ad.unitIds", "\"[]\""))
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = true
                nativeSymbolUploadEnabled = true
            }
        }
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

googleServices {
    // Play Store variants provide google-services.json; FDroid variants do not.
    missingGoogleServicesStrategy = GoogleServicesPlugin.MissingGoogleServicesStrategy.IGNORE
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}

tasks.configureEach {
    if (name.startsWith("processFDroid") && name.endsWith("GoogleServices")) {
        enabled = false
    }
    if (name.contains("FDroid") && name.startsWith("injectCrashlytics")) {
        enabled = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.tools.desugar)

    implementation(projects.analytics)
    implementation(projects.foundation)
    implementation(projects.streaming.core)
    implementation(projects.streaming.mjpeg)
    implementation(projects.streaming.rtsp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window.core)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.adaptive.navigation3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.koin.compose)
    implementation(libs.koin.annotations)

    implementation(libs.kermit)

    "PlayStoreImplementation"(projects.streaming.webrtc)
    "PlayStoreImplementation"(libs.play.app.update)
    "PlayStoreImplementation"(libs.play.app.review)
    "PlayStoreImplementation"(libs.ads.mobile.sdk)
    "PlayStoreImplementation"(libs.user.messaging.platform)
    // The Ads SDK still requests WorkManager 2.7.0 for offline pings with a CONNECTED constraint.
    // WorkManager 2.11.2+ fixes blocked background-network tracking on Android 15+.
    // Remove this override when the ads SDK requires WorkManager 2.11.2 or newer.
    "PlayStoreImplementation"(libs.androidx.work.runtime)
    "PlayStoreImplementation"(platform(libs.firebase.bom))
    "PlayStoreImplementation"(libs.firebase.analytics)
    "PlayStoreImplementation"(libs.firebase.crashlytics.ndk)
    "PlayStoreImplementation"(libs.kermit.crashlytics)

    constraints {
        // Navigation 3 UI 1.2.2 requests NavigationEvent Compose 1.1.1. Keep this override
        // until Navigation 3 pulls in 1.2.0 or newer with the child dispatcher fallback fix.
        implementation(libs.androidx.navigationevent.compose) {
            because("NavigationEvent 1.2.0 propagates unhandled Back events to the parent dispatcher fallback (b/531988076)")
        }
    }
}
