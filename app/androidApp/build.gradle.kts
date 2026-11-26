import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// CI passes -PversionCode=<run number> so every build is newer than the previous one
// (Android refuses downgrades). Local builds use 1.
val appVersionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
val appVersionName = (findProperty("versionName") as String?) ?: "0.1.0-local"

// Release signing comes from environment variables only. The keystore never lives in the repo.
val releaseKeystore = System.getenv("FAMILYAPP_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
val releaseKeystorePassword = System.getenv("FAMILYAPP_KEYSTORE_PASSWORD")

// Update channel: public repo that only holds release APKs (ADR 0005). The code repo stays private.
val updateReleasesBase = "https://github.com/RespawnedGuy/familyapp-releases/releases"
// Only for the CI end-to-end test build type "e2e": a local HTTP server on the emulator host.
val e2eUpdateBaseUrl = (findProperty("e2eUpdateBaseUrl") as String?) ?: "http://10.0.2.2:8000/"

fun String.quoted() = "\"" + this + "\""

android {
    namespace = "io.github.respawnedguy.familyapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Permanent: changing it makes Android treat the app as a different app.
        applicationId = "io.github.respawnedguy.familyapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("boolean", "UPDATES_ENABLED", "true")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "$updateReleasesBase/latest/download/update.json".quoted())
        buildConfigField("String", "UPDATE_APK_URL_PREFIX", "$updateReleasesBase/download/".quoted())
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("FAMILYAPP_KEY_ALIAS") ?: "familyapp"
                // PKCS12 keystores use the store password for the key as well.
                keyPassword = releaseKeystorePassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off until there is real code worth shrinking; fewer surprises on device.
            isMinifyEnabled = false
            // Without the release keystore (e.g. local builds) fall back to the debug key,
            // so the build still produces an installable APK.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            // Debug and release builds can be installed side by side.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // A debug build has another package name, so release APKs can never update it.
            buildConfigField("boolean", "UPDATES_ENABLED", "false")
        }
        // Release build that fetches updates from a local test server (CI end-to-end test only).
        // Never published. Its manifest overlay allows cleartext HTTP to the emulator host only.
        create("e2e") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("String", "UPDATE_MANIFEST_URL", "${e2eUpdateBaseUrl}update.json".quoted())
            buildConfigField("String", "UPDATE_APK_URL_PREFIX", e2eUpdateBaseUrl.quoted())
        }
    }

    // One universal APK: no ABI splits. (Density splits are off by default and their DSL is gone in AGP 9.)
    splits {
        abi { isEnable = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
    // Bytecode target must match compileOptions above (Java 17), independent of the JDK.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // FileProvider: hands the camera app one file to write the photo into.
    implementation(libs.androidx.core)
    // Pairing: QR scanner and QR drawing, offline and without Google Play services (ADR 0006).
    implementation(libs.zxing.android.embedded)
    // Sync in the background every 15 minutes (shared folder, house device).
    implementation(libs.androidx.work.runtime)
}
