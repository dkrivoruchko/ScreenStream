plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "info.dvkr.screenstream.mjpeg"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        languageVersion = "1.9"
    }
}

dependencies {
    implementation(project(":common"))

    //noinspection KtxExtensionAvailable
    implementation("androidx.core:core:1.10.1")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.0")

    implementation("io.ktor:ktor-server-cio:2.3.3")
    implementation("io.ktor:ktor-server-default-headers:2.3.3")
    implementation("io.ktor:ktor-server-forwarded-header:2.3.3")
    implementation("io.ktor:ktor-server-status-pages:2.3.3")
    implementation("io.ktor:ktor-server-cors:2.3.3")
}