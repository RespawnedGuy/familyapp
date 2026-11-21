package io.github.respawnedguy.familyapp.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    private val prefix = "https://github.com/RespawnedGuy/familyapp-releases/releases/download/"
    private val manifestJson =
        """{"versionCode":9,"versionName":"0.1.9","apkUrl":"${prefix}v0.1.9/familyapp-0.1.9.apk","sha256":"${"a".repeat(64)}","sizeBytes":100,"minSdk":26}"""

    private class FakePlatform(
        var manifestText: String?,
        val prefix: String,
        var downloadError: String? = null,
        var packageProblem: String? = null,
        var canInstall: Boolean = true,
    ) : UpdatePlatform {
        override val installedVersionCode = 8
        override val deviceSdk = 36
        override val manifestUrl = "https://example/update.json"
        override val allowedApkUrlPrefix get() = prefix
        override var autoCheckEnabled = true
        override var lastCheckMs: Long? = null
        var now = 1_000_000L
        var fetches = 0
        var installs = 0
        override fun nowMs() = now
        override suspend fun fetchManifest(): String? { fetches++; return manifestText }
        override suspend fun download(manifest: UpdateManifest, onProgress: (Float) -> Unit): VerifiedDownload {
            downloadError?.let { throw UpdateException(it) }
            onProgress(1f)
            return VerifiedDownload("/tmp/x.apk", manifest)
        }
        override fun verifyPackage(download: VerifiedDownload) = packageProblem
        override fun canInstallPackages() = canInstall
        override fun openInstallPermissionSettings() {}
        override suspend fun install(download: VerifiedDownload): InstallOutcome { installs++; return InstallOutcome.Started }
    }

    private fun TestScope.controller(p: FakePlatform) = UpdateController(p, this)

    @Test
    fun startCheckFindsUpdateAndRemembersTime() = runTest {
        val p = FakePlatform(manifestJson, prefix)
        val c = controller(p)
        c.onAppStart(); advanceUntilIdle()
        assertIs<UpdateState.Available>(c.state.value)
        assertEquals(p.now, p.lastCheckMs)
    }

    @Test
    fun noAutoCheckWhenDisabled() = runTest {
        val p = FakePlatform(manifestJson, prefix).apply { autoCheckEnabled = false }
        controller(p).onAppStart(); advanceUntilIdle()
        assertEquals(0, p.fetches)
    }

    @Test
    fun noAutoCheckTwiceADay() = runTest {
        val p = FakePlatform(manifestJson, prefix).apply { lastCheckMs = now - 1_000 }
        controller(p).onAppStart(); advanceUntilIdle()
        assertEquals(0, p.fetches)
    }

    @Test
    fun manualCheckIgnoresSchedule() = runTest {
        val p = FakePlatform(manifestJson, prefix).apply { lastCheckMs = now - 1_000 }
        val c = controller(p)
        c.check(); advanceUntilIdle()
        assertEquals(1, p.fetches)
        assertIs<UpdateState.Available>(c.state.value)
    }

    @Test
    fun checksumMismatchNeverInstalls() = runTest {
        val p = FakePlatform(manifestJson, prefix, downloadError = "Prüfsumme stimmt nicht")
        val c = controller(p)
        c.check(); advanceUntilIdle()
        c.install((c.state.value as UpdateState.Available).manifest); advanceUntilIdle()
        assertEquals(UpdateState.Failed("Prüfsumme stimmt nicht"), c.state.value)
        assertEquals(0, p.installs)
    }

    @Test
    fun foreignSignatureNeverInstalls() = runTest {
        val p = FakePlatform(manifestJson, prefix, packageProblem = "Signatur passt nicht")
        val c = controller(p)
        c.check(); advanceUntilIdle()
        c.install((c.state.value as UpdateState.Available).manifest); advanceUntilIdle()
        assertEquals(UpdateState.Failed("Signatur passt nicht"), c.state.value)
        assertEquals(0, p.installs)
    }

    @Test
    fun missingPermissionAsksFirst() = runTest {
        val p = FakePlatform(manifestJson, prefix, canInstall = false)
        val c = controller(p)
        c.check(); advanceUntilIdle()
        c.install((c.state.value as UpdateState.Available).manifest); advanceUntilIdle()
        assertIs<UpdateState.NeedsPermission>(c.state.value)
        assertEquals(0, p.installs)
    }

    @Test
    fun happyPathStartsInstaller() = runTest {
        val p = FakePlatform(manifestJson, prefix)
        val c = controller(p)
        c.check(); advanceUntilIdle()
        c.install((c.state.value as UpdateState.Available).manifest); advanceUntilIdle()
        assertIs<UpdateState.Installing>(c.state.value)
        assertEquals(1, p.installs)
    }

    @Test
    fun invalidManifestIsReported() = runTest {
        val p = FakePlatform("""{"versionCode":9}""", prefix)
        val c = controller(p)
        c.check(); advanceUntilIdle()
        assertTrue(c.state.value is UpdateState.Failed)
    }

    @Test
    fun emptyChannelIsNotAnError() = runTest {
        val p = FakePlatform(null, prefix)
        val c = controller(p)
        c.check(); advanceUntilIdle()
        assertEquals(UpdateState.NothingPublished, c.state.value)
        assertEquals(p.now, p.lastCheckMs)
    }

    @Test
    fun networkErrorIsReported() = runTest {
        val p = object : UpdatePlatform by FakePlatform(manifestJson, prefix) {
            override suspend fun fetchManifest(): String? = throw UpdateException("Keine Verbindung")
        }
        val c = UpdateController(p, this)
        c.check(); advanceUntilIdle()
        assertEquals(UpdateState.Failed("Keine Verbindung"), c.state.value)
    }
}
