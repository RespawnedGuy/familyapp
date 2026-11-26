// House device ("Dauer-Peer", ADR 0003): the app's own sync code as a command line program for an
// always-on machine in the tailnet (Surface, Synology, Raspi). Also the second device in the CI
// emulator test (app/scripts/emulator-pairing-e2e.sh). Compiles the Compose-free app layers from
// app/shared directly, so it is always the same code as in the app. Needs only Maven Central.
//   gradle installDist  →  build/install/family-peer/bin/family-peer
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

repositories { mavenCentral() }

val shared = file("../../app/shared/src")
val pkg = "kotlin/io/github/respawnedguy/familyapp"

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        listOf("domain", "data", "presentation", "sync").forEach { kotlin.srcDir("$shared/commonMain/$pkg/$it") }
        listOf("data", "sync").forEach { kotlin.srcDir("$shared/androidMain/$pkg/$it") }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
}

kotlin { jvmToolchain(21) }

application {
    mainClass = "io.github.respawnedguy.familyapp.peer.MainKt"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstdin.encoding=UTF-8")
}
