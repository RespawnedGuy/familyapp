// Quality gate and measurements for docs/qualitaet/iso-25010-bewertung.md (not part of the app build).
// Compiles the Compose-free layers of app/shared (domain, data, presentation, update and the
// JVM backup code) as plain Kotlin/JVM, so tests, coverage and detekt run without the Android SDK
// or Google Maven, in cloud sessions and in CI (.github/workflows/quality.yml).
// Run from this folder:  gradle check
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    jacoco
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

repositories { mavenCentral() }

val shared = file("../../app/shared/src")
val pkg = "kotlin/io/github/respawnedguy/familyapp"

sourceSets {
    main {
        // androidMain/data holds the backup encryption: plain JDK code, so it runs here too.
        kotlin.setSrcDirs(
            listOf("domain", "data", "presentation", "update", "sync").map { "$shared/commonMain/$pkg/$it" } +
                listOf("data", "sync").map { "$shared/androidMain/$pkg/$it" },
        )
        kotlin.exclude("**/AndroidTextStore.kt")
        kotlin.exclude("**/UpdateSection.kt") // Compose UI
    }
    test {
        kotlin.setSrcDirs(
            listOf("domain", "data", "presentation", "update", "sync").map { "$shared/commonTest/$pkg/$it" } +
                listOf("data", "sync").map { "$shared/androidUnitTest/$pkg/$it" } + "probes",
        )
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

// Quality plan P1-3: coverage may not fall below the level of 26.11.2026 (rounded down).
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit { counter = "LINE"; minimum = "0.88".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.55".toBigDecimal() }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// Quality plan P1-2: detekt over all app code; existing findings are in the baseline, new ones fail.
detekt {
    buildUponDefaultConfig = true
    config.setFrom("config/detekt.yml")
    baseline = file("config/baseline.xml")
    source.setFrom(
        "$shared/commonMain/kotlin",
        "$shared/androidMain/kotlin",
        "$shared/iosMain/kotlin",
        file("../../app/androidApp/src/main/kotlin"),
    )
}
