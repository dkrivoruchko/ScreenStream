plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("android.extensions")
}

android {
    compileSdkVersion(29)
    buildToolsVersion("29.0.3")

    defaultConfig {
        minSdkVersion(21)
        targetSdkVersion(29)
        versionCode = 30406
        versionName = "3.4.6"
    }

    buildTypes {
        getByName("debug") {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("release") {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = kotlinOptions.freeCompilerArgs
        .plusElement("-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi")
        .plusElement("-Xuse-experimental=kotlinx.coroutines.FlowPreview")
        .plusElement("-Xuse-experimental=kotlinx.coroutines.ObsoleteCoroutinesApi")

    sourceSets {
        maybeCreate("main").java.srcDirs(File("src/main/kotlin"))
    }
}

dependencies {
    implementation(kotlin("stdlib-jdk8", "1.3.71"))
    implementation(kotlin("android-extensions-runtime", "1.3.71"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.5")

    implementation("androidx.core:core:1.2.0")
    implementation("androidx.arch.core:core-common:2.1.0")
    implementation("androidx.collection:collection:1.1.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.2.0")

    implementation("io.ktor:ktor-server-netty:1.3.2")
//    implementation("io.ktor:ktor-server-cio:1.3.2")

    implementation("com.google.zxing:core:3.3.3")
    implementation("com.github.iamironz:binaryprefs:1.0.1")
    implementation("com.elvishew:xlog:1.6.1")
}