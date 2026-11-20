package io.github.respawnedguy.familyapp.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UpdateManifestParserTest {
    private val prefix = "https://github.com/RespawnedGuy/familyapp-releases/releases/download/"
    private val sha = "a".repeat(64)

    private fun json(
        versionCode: Int = 9,
        apkUrl: String = "${prefix}v0.1.9/familyapp-0.1.9.apk",
        sha256: String = sha,
        sizeBytes: Long = 12_000_000,
        minSdk: Int = 26,
        extra: String = "",
    ) = """{"versionCode":$versionCode,"versionName":"0.1.9","apkUrl":"$apkUrl","sha256":"$sha256","sizeBytes":$sizeBytes,"minSdk":$minSdk$extra}"""

    @Test
    fun acceptsValidManifestAndIgnoresUnknownFields() {
        val result = UpdateManifestParser.parse(json(extra = ""","channel":"stable""""), prefix)
        val manifest = assertIs<ManifestResult.Valid>(result).manifest
        assertEquals(9, manifest.versionCode)
        assertEquals(sha, manifest.sha256)
    }

    @Test
    fun normalizesChecksumToLowerCase() {
        val result = UpdateManifestParser.parse(json(sha256 = "AB".repeat(32)), prefix)
        assertEquals("ab".repeat(32), assertIs<ManifestResult.Valid>(result).manifest.sha256)
    }

    @Test
    fun rejectsForeignDownloadHost() {
        val result = UpdateManifestParser.parse(json(apkUrl = "https://evil.example/familyapp.apk"), prefix)
        assertIs<ManifestResult.Invalid>(result)
    }

    @Test
    fun rejectsLookalikePrefix() {
        val result = UpdateManifestParser.parse(
            json(apkUrl = "https://github.com/RespawnedGuy/familyapp-releases-fake/releases/download/x.apk"), prefix,
        )
        assertIs<ManifestResult.Invalid>(result)
    }

    @Test
    fun rejectsEmptyAllowedPrefix() {
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse(json(), ""))
    }

    @Test
    fun rejectsMalformedChecksum() {
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse(json(sha256 = "1234"), prefix))
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse(json(sha256 = "g".repeat(64)), prefix))
    }

    @Test
    fun rejectsImplausibleSize() {
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse(json(sizeBytes = 0), prefix))
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse(json(sizeBytes = 500L * 1024 * 1024), prefix))
    }

    @Test
    fun rejectsNonApkDownload() {
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse(json(apkUrl = "${prefix}v1/familyapp.zip"), prefix))
    }

    @Test
    fun rejectsGarbage() {
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse("<html>Not Found</html>", prefix))
        assertIs<ManifestResult.Invalid>(UpdateManifestParser.parse("""{"versionCode":"x"}""", prefix))
    }
}
