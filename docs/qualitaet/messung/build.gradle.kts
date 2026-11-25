// Quality measurement for docs/qualitaet/iso-25010-bewertung.md (not part of the app build).
// Compiles the Compose-free layers of app/shared (domain, data, presentation, update) as plain
// Kotlin/JVM, so tests, coverage and detekt run without the Android SDK or Google Maven.
// Run from this folder:  gradle test detekt
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    jacoco
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

repositories { mavenCentral() }

val shared = file("../../../app/shared/src")
val pkg = "kotlin/io/github/respawnedguy/familyapp"

sourceSets {
    main {
        kotlin.setSrcDirs(listOf("domain", "data", "presentation", "update").map { "$shared/commonMain/$pkg/$it" })
        kotlin.exclude("**/UpdateSection.kt") // Compose UI
    }
    test {
        kotlin.setSrcDirs(listOf("domain", "data", "presentation", "update").map { "$shared/commonTest/$pkg/$it" } + "probes")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    finalizedBy(tasks.jacocoTestReport)
}
tasks.jacocoTestReport { reports { xml.required.set(true); csv.required.set(true) } }

detekt {
    buildUponDefaultConfig = true
    ignoreFailures = true
    source.setFrom(
        "$shared/commonMain/kotlin",
        "$shared/androidMain/kotlin",
        file("../../../app/androidApp/src/main/kotlin"),
    )
}
