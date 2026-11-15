plugins {
    alias(libs.plugins.androidLibrary)
}
kotlin {
    explicitApi()
    jvmToolchain(17)
}
android {
    namespace = "io.screenstream.analytics"
    compileSdk {
        version = release(rootProject.extra["compileSdkVersion"] as Int)
    }
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String

    defaultConfig {
        minSdk = rootProject.extra["minSdkVersion"] as Int
    }
}

dependencies {

}