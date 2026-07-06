// Terminal chat client. Speaks the exact same protocol as the Android app, so a
// CLI user and a phone user interoperate through the same relay (terminal<->terminal
// and phone<->terminal). Connects to the relay onion via Tor's SOCKS proxy, or to a
// local relay over plain TCP for offline testing/demos.
plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.chiko.onionchat.cli.MainKt")
    applicationName = "onionchat"
}
