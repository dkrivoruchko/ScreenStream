plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.getkeepsafe.dexcount")
}

android {
    compileSdkVersion(29)
    buildToolsVersion("29.0.3")

    defaultConfig {
        applicationId = "info.dvkr.screenstream"
        minSdkVersion(21)
        targetSdkVersion(29)
        versionCode = 30405
        versionName = "3.4.5"
        resConfigs("en", "ru", "pt-rBR", "zh-rTW", "fr-rFR", "fa", "it", "pl", "hi", "de", "sk", "es", "ar")

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
    }

    flavorDimensions("default")
    productFlavors {
        create("firebase") {}
        create("firebasefree") {
            firebaseCrashlytics.mappingFileUploadEnabled = false
            firebaseCrashlytics.nativeSymbolUploadEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        coreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions.jvmTarget = "1.8"
    kotlinOptions.freeCompilerArgs = kotlinOptions.freeCompilerArgs
        .plusElement("-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi")
        .plusElement("-Xuse-experimental=kotlinx.coroutines.FlowPreview")

    sourceSets {
        maybeCreate("main").java.srcDirs(File("src/main/kotlin"))

        maybeCreate("firebase").java.srcDirs(File("src/firebase/kotlin"))
        maybeCreate("firebaseDebug").java.srcDirs(File("src/firebaseDebug/kotlin"))
        maybeCreate("firebaseRelease").java.srcDirs(File("src/firebaseRelease/kotlin"))

        maybeCreate("firebasefree").java.srcDirs(File("src/firebasefree/kotlin"))
        maybeCreate("firebasefreeDebug").java.srcDirs(File("src/firebasefreeDebug/kotlin"))
        maybeCreate("firebasefreeRelease").java.srcDirs(File("src/firebasefreeRelease/kotlin"))
    }

    packagingOptions {
        exclude("META-INF/INDEX.LIST")
        exclude("META-INF/io.netty.versions.properties")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.0.5")

    implementation(project(":data"))

    implementation(kotlin("stdlib-jdk8", "1.3.71"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.3.5")

    implementation("com.google.android.material:material:1.1.0")
    implementation("androidx.appcompat:appcompat:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.2.4")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:1.1.3")
    implementation("androidx.navigation:navigation-fragment-ktx:2.2.1")
    implementation("androidx.navigation:navigation-ui-ktx:2.2.1")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.2.0")

    implementation("com.afollestad.material-dialogs:core:3.3.0")
    implementation("com.afollestad.material-dialogs:color:3.3.0")
    implementation("com.afollestad.material-dialogs:input:3.3.0")
    implementation("com.afollestad.material-dialogs:lifecycle:3.3.0")
    // Temp fix for https://github.com/afollestad/material-dialogs/issues/1825
    implementation(fileTree("libs/bottomsheets-release.aar"))
//    implementation("com.afollestad.material-dialogs", "bottomsheets", "3.3.0")

    implementation("org.koin:koin-android:2.1.5")
    implementation("com.github.iamironz:binaryprefs:1.0.1")
    implementation("com.elvishew:xlog:1.6.1")

    "firebaseImplementation"("com.google.android.play:core:1.7.2")
    "firebaseImplementation"("com.google.android.play:core-ktx:1.7.0")
    "firebaseReleaseImplementation"("com.google.firebase:firebase-analytics:17.3.0")
    "firebaseReleaseImplementation"("com.google.firebase:firebase-crashlytics:17.0.0-beta04")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.2")
}

android.applicationVariants.all {
    if (flavorName.contains("firebasefree")) {
        project.tasks.getByName("injectCrashlyticsMappingFileId" + name.capitalize()).enabled = false
        project.tasks.getByName("process" + name.capitalize() + "GoogleServices").enabled = false
    }
}