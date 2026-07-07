// Shared, platform-independent protocol core: identity, sealed-box E2E crypto,
// message envelope and relay wire protocol. Depended on by both :relay and :cli
// (and drop-in reusable by the Android app — same packages, no Android imports).
plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // exposed (api) so dependents can use BouncyCastle types (e.g. X25519 keys, Base64)
    // and org.json without re-declaring them.
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    api("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}

// Emit Java 17 bytecode so the shared core (and the CLI built on it) runs on
// any JRE 17+ — desktops, Termux (openjdk-17), FreeBSD (openjdk17). Compiles with
// whatever JDK (17+) runs Gradle; no toolchain provisioning required.
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
