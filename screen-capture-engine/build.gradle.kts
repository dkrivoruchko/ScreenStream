plugins {
    alias(libs.plugins.androidLibrary)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "io.screenstream.capture"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_WEAK_API_DEFS=ON"
                targets += "screen_capture_engine"
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    testOptions {
        unitTests.all {
            it.javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(21)
                },
            )
        }
    }
}

tasks.register<Exec>("testHostNative") {
    val cmakeVersion = requireNotNull(android.externalNativeBuild.cmake.version)
    val cmakeBinDirectory = androidComponents.sdkComponents.sdkDirectory.map {
        it.dir("cmake/$cmakeVersion/bin")
    }
    val cmakeExecutable = cmakeBinDirectory.map { it.file("cmake") }
    val ninjaExecutable = cmakeBinDirectory.map { it.file("ninja") }

    group = "verification"
    description = "Builds and runs the host-native C++ tests with ASan and UBSan."
    inputs.files(cmakeExecutable, ninjaExecutable)
    workingDir(layout.projectDirectory.dir("src/test/cpp"))
    args("--workflow", "--preset", "host-native")
    doFirst {
        executable(cmakeExecutable.get())
        environment("SCE_NINJA", ninjaExecutable.get().asFile.absolutePath)
    }
}

tasks.named("check") {
    dependsOn("testHostNative")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.annotation:annotation:1.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
