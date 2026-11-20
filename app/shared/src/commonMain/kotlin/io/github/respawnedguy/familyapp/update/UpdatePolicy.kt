package io.github.respawnedguy.familyapp.update

sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision
    data class Available(val manifest: UpdateManifest) : UpdateDecision
    data class DeviceTooOld(val manifest: UpdateManifest) : UpdateDecision
}

object UpdatePolicy {
    const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000

    fun decide(installedVersionCode: Int, deviceSdk: Int, manifest: UpdateManifest): UpdateDecision = when {
        manifest.versionCode <= installedVersionCode -> UpdateDecision.UpToDate
        deviceSdk < manifest.minSdk -> UpdateDecision.DeviceTooOld(manifest)
        else -> UpdateDecision.Available(manifest)
    }

    /** Automatic check at most once per interval; a clock that went backwards counts as due. */
    fun isAutoCheckDue(lastCheckMs: Long?, nowMs: Long): Boolean =
        lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    fun checksumMatches(expectedSha256: String, actualSha256: String): Boolean =
        expectedSha256.isNotBlank() && expectedSha256.equals(actualSha256, ignoreCase = true)
}
