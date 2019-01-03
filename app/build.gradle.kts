import com.crashlytics.tools.gradle.CrashlyticsExtension
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.internal.AndroidExtensionsExtension

plugins {
    id("com.android.application")
    id("io.fabric")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
}

android {
    compileSdkVersion(Versions.compileSdk)
    buildToolsVersion(Versions.buildTools)

    defaultConfig {
        applicationId = "info.dvkr.screenstream"
        minSdkVersion(Versions.minSdk)
        targetSdkVersion(Versions.targetSdk)
        versionCode = Versions.versionMajor * 10000 + Versions.versionMinor * 100 + Versions.versionPatch
        versionName = "${Versions.versionMajor}.${Versions.versionMinor}.${Versions.versionPatch}"
        resConfigs("en", "ru", "pt-rBR", "zh-rTW", "fr-rFR", "fa", "it")

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        create("fabricfree") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        setSourceCompatibility(JavaVersion.VERSION_1_8)
        setTargetCompatibility(JavaVersion.VERSION_1_8)
    }

    sourceSets["main"].java.srcDirs(file("src/main/kotlin"))
    sourceSets["debug"].java.srcDirs(file("src/debug/kotlin"))
    sourceSets["release"].java.srcDirs(file("src/release/kotlin"))
    sourceSets["fabricfree"].java.srcDirs(file("src/fabricfree/kotlin"))

    packagingOptions.exclude("META-INF/INDEX.LIST")
    packagingOptions.exclude("META-INF/io.netty.versions.properties")
}

//androidExtensions { experimental = true }
androidExtensions {
    configure(delegateClosureOf<AndroidExtensionsExtension> {
        isExperimental = true
    })
}
kapt { useBuildCache = true }

configurations {
    "implementation" {
        resolutionStrategy.failOnVersionConflict()
    }
}

dependencies {
    implementation(project(":data"))

    implementation("org.jetbrains:annotations:${Versions.jetbrainsAnnotations}")
    implementation(kotlin("stdlib-jdk7", Versions.kotlin))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutine}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.coroutine}")

    implementation("androidx.annotation:annotation:${Versions.annotation}")
    implementation("com.google.android.material:material:${Versions.material}")
    implementation("androidx.appcompat:appcompat:${Versions.appcompat}")
    implementation("androidx.legacy:legacy-support-v4:${Versions.legacySupport}")
    implementation("androidx.constraintlayout:constraintlayout:${Versions.constraint}")

    implementation("com.tapadoo.android:alerter:${Versions.alerter}")
    implementation("com.github.florent37:expansionpanel:1.2.2")
    implementation("com.afollestad.material-dialogs:core:${Versions.materialDialogs}")
    implementation("com.afollestad.material-dialogs:color:${Versions.materialDialogs}")
    implementation("com.afollestad.material-dialogs:input:${Versions.materialDialogs}")

    implementation("org.koin:koin-android:${Versions.koin}")
    implementation("com.github.iamironz:binaryprefs:${Versions.binaryprefs}")
    implementation("com.elvishew:xlog:${Versions.xlog}")

    releaseImplementation("com.google.firebase:firebase-core:${Versions.firebaseCore}")
    releaseImplementation("com.crashlytics.sdk.android:crashlytics:${Versions.crashlytics}")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:${Versions.leakcanary}")
    debugImplementation("com.squareup.leakcanary:leakcanary-support-fragment:${Versions.leakcanary}")
    releaseImplementation("com.squareup.leakcanary:leakcanary-android-no-op:${Versions.leakcanary}")
}

apply(from = "fabric.gradle")