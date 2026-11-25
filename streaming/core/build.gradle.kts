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
    namespace = "io.screenstream.core"
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int) {
            minorApiLevel = rootProject.extra["compileSdkMinorVersion"] as Int
        }
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            it.javaLauncher.set(javaToolchains.launcherFor {
                languageVersion = JavaLanguageVersion.of(21)
            })
        }
    }
}

dependencies {
    implementation(projects.foundation)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.annotations)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    // Espresso 3.7 fixes InputManager access on API 37 and is used directly for pressBack.
    // Five JVM test files also use ActivityScenario from transitive AndroidX Test Core.
    testImplementation(libs.androidx.test.espresso.core)

    testImplementation(libs.androidx.compose.ui.test.junit4)
}
