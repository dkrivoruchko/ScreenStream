plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.koin.compiler)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "io.screenstream.mjpeg"
    compileSdk = rootProject.extra["compileSdkVersion"] as Int
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        targetSdk = rootProject.extra["targetSdkVersion"] as Int
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            it.javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(21)
                },
            )
        }
    }
}

dependencies {
    implementation(projects.common)
    implementation(projects.streaming.core)
    implementation(projects.screenCaptureEngine)

    implementation(libs.koin.core)
    implementation(libs.koin.annotations)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
