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
    implementation(libs.androidx.datastore.preferences.core)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
