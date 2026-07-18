plugins {
    alias(libs.plugins.androidLibrary)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "io.screenstream.engine"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_WEAK_API_DEFS=ON"
                targets += "screencaptureengine"
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.window:window:1.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
