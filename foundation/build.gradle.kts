plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.koin.compiler)
}
kotlin {
    explicitApi()
    jvmToolchain(17)
}
android {
    namespace = "io.screenstream.foundation"
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int)
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.koin.annotations)
}
