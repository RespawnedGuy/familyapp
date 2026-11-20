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
        }
    }

    // One universal APK: no ABI or density splits.
    splits {
        abi { isEnable = false }
        density { isEnable = false }
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
}
