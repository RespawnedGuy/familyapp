package io.github.respawnedguy.familyapp.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdatePolicyTest {
    private fun manifest(versionCode: Int, minSdk: Int = 26) =
        UpdateManifest(versionCode, "0.1.$versionCode", "https://x/a.apk", "a".repeat(64), 1, minSdk)

    @Test
    fun newerVersionIsAvailable() {
        assertIs<UpdateDecision.Available>(UpdatePolicy.decide(8, 36, manifest(9)))
    }

    @Test
    fun sameOrOlderVersionIsUpToDate() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(9, 36, manifest(9)))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(10, 36, manifest(9)))
    }

    @Test
    fun deviceBelowMinSdkIsReported() {
        assertIs<UpdateDecision.DeviceTooOld>(UpdatePolicy.decide(8, 26, manifest(9, minSdk = 28)))
    }

    @Test
    fun autoCheckAtMostOncePerDay() {
        val day = UpdatePolicy.CHECK_INTERVAL_MS
        assertTrue(UpdatePolicy.isAutoCheckDue(null, 1_000))
        assertFalse(UpdatePolicy.isAutoCheckDue(1_000, 1_000 + day - 1))
        assertTrue(UpdatePolicy.isAutoCheckDue(1_000, 1_000 + day))
        assertTrue(UpdatePolicy.isAutoCheckDue(5_000, 1_000)) // clock went backwards
    }

    @Test
    fun checksumComparisonIgnoresCase() {
        assertTrue(UpdatePolicy.checksumMatches("ab".repeat(32), "AB".repeat(32)))
        assertFalse(UpdatePolicy.checksumMatches("ab".repeat(32), "ac".repeat(32)))
        assertFalse(UpdatePolicy.checksumMatches("", ""))
    }
}
