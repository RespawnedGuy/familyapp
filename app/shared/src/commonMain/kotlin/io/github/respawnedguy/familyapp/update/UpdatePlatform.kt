package io.github.respawnedguy.familyapp.update

/** A downloaded update file whose size and checksum have already been verified. */
data class VerifiedDownload(val path: String, val manifest: UpdateManifest)

sealed interface InstallOutcome {
    /** The system installer takes over; the app is replaced and restarted by the user. */
    data object Started : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}

class UpdateException(message: String) : Exception(message)

/**
 * Platform side of the updater (data layer). Android implements it, iOS has none
 * (updates come via TestFlight/App Store there).
 */
interface UpdatePlatform {
    val installedVersionCode: Int
    val deviceSdk: Int
    val manifestUrl: String
    val allowedApkUrlPrefix: String

    var autoCheckEnabled: Boolean
    var lastCheckMs: Long?
    fun nowMs(): Long

    /** Returns the raw text of update.json. Throws [UpdateException] on network errors. */
    suspend fun fetchManifest(): String

    /** Downloads the APK, checks size and SHA-256. Throws [UpdateException] on any mismatch. */
    suspend fun download(manifest: UpdateManifest, onProgress: (Float) -> Unit): VerifiedDownload

    /** Checks package name, version and that the signing certificate equals the installed app's. */
    fun verifyPackage(download: VerifiedDownload): String?

    fun canInstallPackages(): Boolean
    fun openInstallPermissionSettings()
    suspend fun install(download: VerifiedDownload): InstallOutcome
}
