plugins {
    alias(libs.plugins.androidLibrary)
}
kotlin {
    explicitApi()
    jvmToolchain(17)
}
android {
    namespace = "io.screenstream.foundation"
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int) {
            minorApiLevel = rootProject.extra["compileSdkMinorVersion"] as Int
        }
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }
}

dependencies {
    api(libs.androidx.datastore.preferences.core)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.core)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
