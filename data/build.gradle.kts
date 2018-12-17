import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.internal.AndroidExtensionsExtension

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("android.extensions")
    kotlin("kapt")
}

android {
    compileSdkVersion(Versions.compileSdk)
    buildToolsVersion(Versions.buildTools)

    defaultConfig {
        minSdkVersion(Versions.minSdk)
        targetSdkVersion(Versions.targetSdk)
        versionCode = Versions.versionMajor * 10000 + Versions.versionMinor * 100 + Versions.versionPatch
        versionName = "${Versions.versionMajor}.${Versions.versionMinor}.${Versions.versionPatch}"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        create("fabricfree") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets["main"].java.srcDirs(file("src/main/kotlin"))
    sourceSets["debug"].java.srcDirs(file("src/debug/kotlin"))
    sourceSets["release"].java.srcDirs(file("src/release/kotlin"))
    sourceSets["fabricfree"].java.srcDirs(file("src/fabricfree/kotlin"))

//    sourceSets["fabricfree"].java.srcDirs(
//        file("src/fabricfree/kotlin"),
//        file("$buildDir/generated/source/kapt/fabricfree"),
//        file("$buildDir/generated/source/kaptKotlin/fabricfree")
//    )
}

androidExtensions {
    configure(delegateClosureOf<AndroidExtensionsExtension> {
        isExperimental = true
    })
}
kapt { useBuildCache = true }

dependencies {
    implementation(kotlin("stdlib-jdk7", Versions.kotlin))
    implementation(kotlin("android-extensions-runtime", Versions.kotlin))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutine}")

    implementation("androidx.annotation:annotation:${Versions.annotation}")
    implementation("androidx.core:core:${Versions.core}")

    implementation("io.reactivex:rxjava:${Versions.rxJava}")
    implementation("com.jakewharton.rxrelay:rxrelay:${Versions.rxRelay}")

    implementation("io.netty:netty-codec-http:${Versions.netty}")
    implementation("io.netty:netty-handler:${Versions.netty}")
    implementation("io.reactivex:rxnetty-http:${Versions.rxNetty}")

    implementation("com.github.iamironz:binaryprefs:${Versions.binaryprefs}")
    implementation("com.jakewharton.timber:timber:${Versions.timber}")
}