package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MemoryFiles : BackupFiles {
    val files = mutableMapOf<String, ByteArray>()
    private fun key(sub: String?, name: String) = if (sub == null) name else "$sub/$name"
    override fun list(sub: String?): List<String> =
        files.keys.filter { if (sub == null) '/' !in it else it.startsWith("$sub/") }.map { it.substringAfter('/') }
    override fun read(sub: String?, name: String) = files[key(sub, name)]
    override fun write(sub: String?, name: String, data: ByteArray) { files[key(sub, name)] = data }
    override fun delete(sub: String?, name: String) { files.remove(key(sub, name)) }
}

/** P0-3 and P0-4: encrypted backup with photos (docs/qualitaet/iso-25010-bewertung.md). */
class BackupFolderTest {
    private val code = BackupCrypto.newCode()
    private val json = """{"schema":2,"items":[{"title":"Impftermin Mia"}]}"""
    private val tmp: File = Files.createTempDirectory("backup").toFile()

    private fun photo(name: String, content: String) = File(tmp, name).apply { writeText(content) }

    @Test
    fun codeHas100BitsAndSurvivesHandwriting() {
        val c = BackupCrypto.newCode()
        assertEquals(24, c.length) // 20 characters in 5 groups
        assertEquals(BackupCrypto.normalize(c), BackupCrypto.normalize(c.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')))
    }

    @Test
    fun nothingReadableEndsUpInTheFolder() {
        val files = MemoryFiles()
        BackupFolder(files).write(BackupContent(json, listOf(photo("a.jpg", "PHOTO-BYTES").path), code))
        assertEquals(setOf(BackupFolder.DATA_FILE, "${BackupFolder.PHOTO_DIR}/a.jpg.${BackupCrypto.codeTag(code)}.fab"), files.files.keys)
        files.files.values.forEach { bytes ->
            val text = bytes.decodeToString()
            assertFalse("Impftermin" in text || "PHOTO-BYTES" in text || "schema" in text)
            assertTrue(BackupCrypto.isEncrypted(bytes))
        }
    }

    @Test
    fun roundTripRestoresDataAndPhotos() {
        val files = MemoryFiles()
        BackupFolder(files).write(BackupContent(json, listOf(photo("a.jpg", "PHOTO-BYTES").path), code))
        val target = Files.createTempDirectory("restore").toFile()
        val read = assertIs<RestoreRead.Ok>(BackupFolder(files).read(code.lowercase(), target))
        assertEquals(json, read.json)
        assertEquals("PHOTO-BYTES", File(read.photoPaths.getValue("a.jpg")).readText())
    }

    @Test
    fun wrongOrMissingCodeIsReportedNotGuessed() {
        val files = MemoryFiles()
        BackupFolder(files).write(BackupContent(json, emptyList(), code))
        assertIs<RestoreRead.NeedsCode>(BackupFolder(files).read(null, tmp))
        assertIs<RestoreRead.WrongCode>(BackupFolder(files).read(BackupCrypto.newCode(), tmp))
    }

    @Test
    fun changedFileIsRejected() {
        val data = BackupCrypto.encrypt("x".encodeToByteArray(), code, "n")
        data[data.size - 1] = (data[data.size - 1] + 1).toByte()
        assertFailsWith<WrongCodeException> { BackupCrypto.decrypt(data, code, "n") }
        assertFailsWith<WrongCodeException> { BackupCrypto.decrypt(BackupCrypto.encrypt("x".encodeToByteArray(), code, "a"), code, "b") }
    }

    @Test
    fun unusedPhotosAndThePlainOldBackupAreRemoved() {
        val files = MemoryFiles()
        files.write(null, BackupFolder.LEGACY_FILE, json.encodeToByteArray())
        val a = photo("a.jpg", "A")
        BackupFolder(files).write(BackupContent(json, listOf(a.path, photo("b.jpg", "B").path), code))
        BackupFolder(files).write(BackupContent(json, listOf(a.path), code))
        assertEquals(listOf("a.jpg.${BackupCrypto.codeTag(code)}.fab"), files.list(BackupFolder.PHOTO_DIR))
        assertFalse(BackupFolder.LEGACY_FILE in files.list(null))
    }

    @Test
    fun photosAreWrittenAgainWhenTheCodeChanges() {
        val files = MemoryFiles()
        val a = photo("a.jpg", "A")
        BackupFolder(files).write(BackupContent(json, listOf(a.path), code))
        val newCode = BackupCrypto.newCode()
        BackupFolder(files).write(BackupContent(json, listOf(a.path), newCode))
        val read = assertIs<RestoreRead.Ok>(BackupFolder(files).read(newCode, Files.createTempDirectory("r").toFile()))
        assertEquals("A", File(read.photoPaths.getValue("a.jpg")).readText())
        assertEquals(1, files.list(BackupFolder.PHOTO_DIR).size)
    }

    @Test
    fun oldPlainBackupCanStillBeRestored() {
        val files = MemoryFiles()
        files.write(null, BackupFolder.LEGACY_FILE, json.encodeToByteArray())
        assertEquals(json, assertIs<RestoreRead.Ok>(BackupFolder(files).read(null, tmp)).json)
    }

    @Test
    fun photoNamesFromABackupCannotEscapeThePhotoFolder() {
        assertFalse(BackupFolder.safeName("../familyapp.json"))
        assertFalse(BackupFolder.safeName(".hidden"))
        assertTrue(BackupFolder.safeName("2f6c1e1a-0b7e-4d3c-9a51-3c1b9e7f0a12.jpg"))
        assertContentEquals("x".encodeToByteArray(), BackupCrypto.decrypt(BackupCrypto.encrypt("x".encodeToByteArray(), code, "n"), code, "n"))
    }
}
