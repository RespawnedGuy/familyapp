import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.hot.reload)
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        // Must match android.compileOptions (Java 17).
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Desktop window for UI work with Compose Hot Reload (docs/ui-bearbeiten.md). Dev tool only, never shipped.
    jvm()

    // iOS is prepared but only compiles on macOS (Xcode required).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.serialization.json)
            // Part of the shared API (AppModel, LocalDate in the domain), so the apps see them too.
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
        }
        androidMain {
            // Sample family shared by the Android Studio previews and the desktop dev window.
            kotlin.srcDir("src/sampleData/kotlin")
            dependencies {
                // @Preview annotation for the Android Studio previews in androidMain/.../preview.
                implementation(compose.preview)
            }
        }
        jvmMain {
            kotlin.srcDir("src/sampleData/kotlin")
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

dependencies {
    // Renderer for the Android Studio preview pane; debug only, never in the release APK.
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "io.github.respawnedguy.familyapp.DevWindowKt"
    }
}

android {
    namespace = "io.github.respawnedguy.familyapp.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Failed assertions show their message in the CI log (the HTML report is not uploaded).
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
