// The untrusted store-and-forward relay. Hosts a Tor v3 onion service (default)
// or a plain-TCP loopback listener (--local, for tests and live demos).
plugins {
    kotlin("jvm")
    application
}

version = "1.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("io.matthewnelson.kmp-tor:runtime:2.6.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.22.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.chiko.onionrelay.RelayKt")
}
