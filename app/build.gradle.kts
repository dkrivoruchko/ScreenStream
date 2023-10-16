import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.devtools.ksp")
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
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "info.dvkr.screenstream"
        minSdk = 23
        targetSdk = 34
        versionCode = 40019
        versionName = "4.0.19"

        // https://medium.com/@crafty/no-if-you-do-that-then-you-cant-use-newer-features-on-older-platforms-e-g-fa595333c0a4
        vectorDrawables.useSupportLibrary = true

        ndk.abiFilters.addAll(listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64"))
    }

    androidResources {
        generateLocaleConfig = true
    }

    val localProps = Properties().apply { file("../local.properties").inputStream().use { load(it) } }

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
        create("firebaseFree") {
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
        languageVersion = "1.9"
        freeCompilerArgs += "-Xexplicit-api=strict"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    ksp("io.insert-koin:koin-ksp-compiler:1.3.0")

    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.4")

    // Temp fix for https://github.com/afollestad/material-dialogs/issues/1825
    implementation(fileTree("libs/bottomsheets-release.aar"))

    implementation(project(":common"))
    implementation(project(":mjpeg"))
    "firebaseImplementation"(project(":webrtc"))
    "firebaseImplementation"("com.google.android.play:app-update-ktx:2.1.0")
    "firebaseImplementation"("com.google.firebase:firebase-analytics:21.3.0")
    "firebaseImplementation"("com.google.firebase:firebase-crashlytics:18.4.3")
    "firebaseImplementation"("com.google.android.gms:play-services-ads:22.4.0")

//    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
}

project.tasks.configureEach {
    if (name.startsWith("injectCrashlyticsMappingFileIdFirebaseFree")) enabled = false
    if (name.startsWith("processFirebaseFree") && name.endsWith("GoogleServices")) enabled = false
}