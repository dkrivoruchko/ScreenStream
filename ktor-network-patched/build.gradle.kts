import java.security.MessageDigest

plugins {
    kotlin("jvm")
    `java-library`
}

kotlin { jvmToolchain(17) }

// Directory variants contain only the recompiled writer; consumers must use the complete merged jar.
configurations.named("apiElements") { outgoing.variants.clear() }
configurations.named("runtimeElements") { outgoing.variants.clear() }

// Resolve the pinned upstream binary without substituting it with this project.
val upstreamNetwork = configurations.create("upstreamNetwork") {
    isTransitive = false
}

dependencies {
    add(upstreamNetwork.name, "io.ktor:ktor-network-jvm:3.6.0")
    compileOnly(files(upstreamNetwork))
    api("io.ktor:ktor-utils-jvm:3.6.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    api("org.slf4j:slf4j-api:2.0.19")
}

val upstreamJarFile = upstreamNetwork.singleFile
val configuredKtorVersion = libs.versions.ktor.get()

tasks.compileKotlin {
    val binary = upstreamJarFile
    val version = configuredKtorVersion
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${binary.absolutePath}")
    doFirst {
        check(version == "3.6.0") { "Update the pinned network patch for the configured Ktor version" }
        val checksum = MessageDigest.getInstance("SHA-256").digest(binary.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(checksum == "746a751146d61865f51d6dd56b18e3e0fa68b36587561ee123ff7ce730d8c590") {
            "Unexpected ktor-network-jvm 3.6.0 binary"
        }
    }
}

tasks.jar {
    archiveBaseName.set("ktor-network-jvm-patched")
    duplicatesStrategy = DuplicatesStrategy.FAIL
    // Retain upstream Kotlin metadata and every other class, replacing the entire writer family.
    from(zipTree(upstreamJarFile)) {
        exclude("io/ktor/network/sockets/CIOWriterKt*.class", "META-INF/MANIFEST.MF")
    }
    exclude("META-INF/*ktor-network-patched.kotlin_module")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
