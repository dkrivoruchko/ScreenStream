plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinParcelize)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "info.dvkr.screenstream.common"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
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
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)

    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.appcompat)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.window)
    api(libs.androidx.datastore.preferences)

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material3.window)
    api("androidx.compose.foundation:foundation:1.7.0-beta02")
    api("androidx.compose.material3:material3:1.3.0-beta02")

    api(libs.koin.android.compose)
    api(libs.koin.annotations)
    ksp(libs.koin.ksp)

    api(libs.xlog)

//    api(libs.androidx.compose.ui.tooling.preview)
//    debugApi(libs.androidx.compose.ui.tooling)
}