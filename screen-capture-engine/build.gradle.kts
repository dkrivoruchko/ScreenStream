plugins {
    alias(libs.plugins.androidLibrary)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

android {
    namespace = "io.screenstream.capture"
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int) {
            minorApiLevel = rootProject.extra["compileSdkMinorVersion"] as Int
        }
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Keep platform API references weak so native code can probe optional compressor availability.
                arguments += "-DANDROID_WEAK_API_DEFS=ON"
                targets += "screen_capture_engine"
            }
        }

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    testOptions {
        unitTests.all {
            // Robolectric's API 37 FileDescriptor interceptor accesses JDK SharedSecrets during setup.
            it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
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
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
