// Terminal chat client. Speaks the exact same protocol as the Android app, so a
// CLI user and a phone user interoperate through the same relay (terminal<->terminal
// and phone<->terminal). Connects to the relay onion via Tor's SOCKS proxy, or to a
// local relay over plain TCP for offline testing/demos.
plugins {
    kotlin("jvm")
    application
}

version = "1.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.zxing:core:3.5.3")   // render the identity QR in the terminal
    testImplementation("junit:junit:4.13.2")
}

tasks.test { useJUnit() }

// Runs on any JRE 17+ (Linux, Windows, macOS, Termux, FreeBSD).
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.chiko.onionchat.cli.MainKt")
    applicationName = "onionchat"
}

// The distZip / distTar produced here (onionchat-1.0.0.zip) is the cross-platform
// download: unzip and run bin/onionchat (Unix) or bin\onionchat.bat (Windows).
distributions {
    main { distributionBaseName.set("onionchat") }
}
