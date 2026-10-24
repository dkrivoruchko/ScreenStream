import java.security.MessageDigest

plugins {
    kotlin("jvm")
    `java-library`
}

kotlin { jvmToolchain(17) }

// Directory variants contain only the recompiled backend; consumers need the complete merged jar.
configurations.named("apiElements") { outgoing.variants.clear() }
configurations.named("runtimeElements") { outgoing.variants.clear() }

val upstreamCio = configurations.create("upstreamCio") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    add(upstreamCio.name, "io.ktor:ktor-server-cio-jvm:3.6.0")
    compileOnly(files(upstreamCio))
    api("io.ktor:ktor-server-core-jvm:3.6.0")
    api("io.ktor:ktor-http-cio-jvm:3.6.0")
    api("io.ktor:ktor-websockets-jvm:3.6.0")
    api("io.ktor:ktor-network-jvm:3.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    api("org.slf4j:slf4j-api:2.0.19")
    api("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

val upstreamJarFile = upstreamCio.singleFile
val configuredKtorVersion = libs.versions.ktor.get()

// No outputs: the pin must also be checked when compilation is already up to date.
val verifyUpstreamCio = tasks.register("verifyUpstreamCio") {
    val binary = upstreamJarFile
    val version = configuredKtorVersion
    inputs.file(binary)
    inputs.property("ktorVersion", version)
    doLast {
        check(version == "3.6.0") { "Update the pinned CIO patch for the configured Ktor version" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(binary.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(checksum == "319e4a03717e39ab9a5be8cb5a7b7d5327627ad3d55ff43270935372d7c13d16") {
            "Unexpected ktor-server-cio-jvm 3.6.0 binary"
        }
    }
}

tasks.compileKotlin {
    dependsOn(verifyUpstreamCio)
    compilerOptions.moduleName.set("ktor-server-cio-patched")
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${upstreamJarFile.absolutePath}")
}

tasks.jar {
    dependsOn(verifyUpstreamCio)
    archiveBaseName.set("ktor-server-cio-jvm-patched")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    // Snapshot all attributes and sections instead of caching a temporary expanded-archive path.
    manifest.from(java.manifest {
        from(zipTree(upstreamJarFile).matching { include("META-INF/MANIFEST.MF") }.singleFile)
    }.effectiveManifest)
    from(zipTree(upstreamJarFile)) {
        exclude("io/ktor/server/cio/backend/HttpServerKt*.class", "META-INF/MANIFEST.MF")
    }
    exclude("META-INF/ktor-server-cio-patched.kotlin_module")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
