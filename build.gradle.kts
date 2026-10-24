plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.koin.compiler) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

extra.set("minSdkVersion", 24)
extra.set("targetSdkVersion", 37)
extra.set("compileSdkVersion", 37)
extra.set("buildToolsVersion", "37.0.0")
extra.set("ndkVersion", "29.0.14206865")

// One reproducible CIO writer replacement across every runtime consumer; never two network jars.
subprojects {
    if (name != "ktor-network-patched") {
        configurations.configureEach {
            resolutionStrategy.dependencySubstitution {
                substitute(module("io.ktor:ktor-network-jvm")).using(project(":ktor-network-patched"))
            }
        }
    }
}

// Keep the raw CIO binary available to its patch while every consumer uses the complete merged jar.
subprojects {
    val isCioPatchProject = name == "ktor-server-cio-patched"
    configurations.configureEach {
        if (!isCioPatchProject || name != "upstreamCio") {
            resolutionStrategy.dependencySubstitution {
                substitute(module("io.ktor:ktor-server-cio-jvm")).using(project(":ktor-server-cio-patched"))
            }
        }
    }
}
