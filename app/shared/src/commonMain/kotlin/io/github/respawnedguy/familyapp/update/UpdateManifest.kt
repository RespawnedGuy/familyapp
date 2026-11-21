package io.github.respawnedguy.familyapp.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Content of `update.json`, published next to every release APK.
 * The app trusts nothing in here blindly: the URL must match the configured prefix,
 * and the downloaded file is checked against [sha256] and [sizeBytes] and its signature.
 */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    val releaseNotes: String = "",
)

sealed interface ManifestResult {
    data class Valid(val manifest: UpdateManifest) : ManifestResult
    data class Invalid(val reason: String) : ManifestResult
}

object UpdateManifestParser {
    /** Upper bound for an APK we are willing to download. */
    const val MAX_APK_BYTES: Long = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Regex = Regex("^[0-9a-fA-F]{64}$")

    fun parse(text: String, allowedApkUrlPrefix: String): ManifestResult {
        val manifest = try {
            json.decodeFromString(UpdateManifest.serializer(), text)
        } catch (e: SerializationException) {
            return ManifestResult.Invalid("update.json ist nicht lesbar")
        } catch (e: IllegalArgumentException) {
            return ManifestResult.Invalid("update.json ist nicht lesbar")
        }
        return when {
            manifest.versionCode <= 0 -> ManifestResult.Invalid("ungültiger versionCode")
            manifest.versionName.isBlank() -> ManifestResult.Invalid("Versionsname fehlt")
            allowedApkUrlPrefix.isBlank() || !manifest.apkUrl.startsWith(allowedApkUrlPrefix) ->
                ManifestResult.Invalid("Download-Adresse nicht erlaubt")
            !manifest.apkUrl.endsWith(".apk") -> ManifestResult.Invalid("Download ist keine APK")
            !sha256Regex.matches(manifest.sha256) -> ManifestResult.Invalid("ungültige Prüfsumme")
            manifest.sizeBytes <= 0 || manifest.sizeBytes > MAX_APK_BYTES -> ManifestResult.Invalid("ungültige Dateigröße")
            manifest.minSdk <= 0 -> ManifestResult.Invalid("ungültige Mindestversion")
            else -> ManifestResult.Valid(manifest.copy(sha256 = manifest.sha256.lowercase()))
        }
    }
}
