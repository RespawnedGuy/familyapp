// Test-only sender app for the share end-to-end test (app/scripts/emulator-share-e2e.sh,
// threat model docs/qualitaet/bedrohungsmodell.md). Plays "another app" that shares files the way
// real apps do (own FileProvider-like provider, URI grant), including hostile ones: too large,
// not an image, image type on non-image bytes. Never published, never installed on a phone.
// Plain Java, so it needs nothing beyond the Android Gradle plugin.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.respawnedguy.e2eshare"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.respawnedguy.e2eshare"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "e2e"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
