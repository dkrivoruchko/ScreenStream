import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinParcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
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
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "info.dvkr.screenstream"
        minSdk = 23
        targetSdk = 34
        versionCode = 40031
        versionName = "4.0.31"

        // https://medium.com/@crafty/no-if-you-do-that-then-you-cant-use-newer-features-on-older-platforms-e-g-fa595333c0a4
        vectorDrawables.useSupportLibrary = true

        ndk.abiFilters.addAll(listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64"))
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    val localProps = Properties()
    val localProperties = File(rootProject.rootDir, "local.properties")
    if (localProperties.exists() && localProperties.isFile) {
        localProperties.inputStream().use { localProps.load(it) }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".dev"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    flavorDimensions += listOf("Default")
    productFlavors {
        create("firebase") {
            manifestPlaceholders += mapOf("adMobPubId" to localProps.getProperty("ad.pubId"))
            buildConfigField("String", "AD_UNIT_ID_A", localProps.getProperty("ad.unitIdA", ""))
            buildConfigField("String", "AD_UNIT_ID_B", localProps.getProperty("ad.unitIdB", ""))
            buildConfigField("String", "AD_UNIT_ID_C", localProps.getProperty("ad.unitIdC", ""))
        }
        create("firebasefree") {
            manifestPlaceholders += mapOf("adMobPubId" to "")
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }

    applicationVariants.all {
        val variantName = name
        sourceSets {
            getByName("main") {
                java.srcDir(File("build/generated/ksp/$variantName/kotlin"))
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.tools.desugar)

    ksp(libs.koin.ksp)

    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Temp fix for https://github.com/afollestad/material-dialogs/issues/1825
    implementation(fileTree("libs/bottomsheets-release.aar"))

    implementation(project(":common"))
    implementation(project(":mjpeg"))
    "firebaseImplementation"(project(":webrtc"))
    "firebaseImplementation"(libs.play.app.update)
    "firebaseImplementation"(libs.play.services.ads)
    "firebaseImplementation"(libs.firebase.analytics)
    "firebaseImplementation"(libs.firebase.crashlytics)

//    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}

project.tasks.configureEach {
    if (name.startsWith("injectCrashlyticsMappingFileIdFirebaseFree")) enabled = false
    if (name.startsWith("processFirebasefree") && name.endsWith("GoogleServices")) enabled = false
}