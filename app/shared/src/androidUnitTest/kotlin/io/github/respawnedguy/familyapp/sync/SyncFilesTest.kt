package io.github.respawnedguy.familyapp.sync

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals

/** Threat model HG-1: on the house device other accounts must not read the family key. */
class SyncFilesTest {

    @Test
    fun theFileWithTheFamilyKeyIsReadableByItsOwnerOnly() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return // e.g. Windows
        val dir = Files.createTempDirectory("familyapp-store").toFile()
        try {
            val file = dir.resolve("sync.json")
            val store = FileSyncStore(file)
            store.write("""{"familyKey":"geheim"}""")
            store.write("""{"familyKey":"neu"}""")
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
            assertEquals("""{"familyKey":"neu"}""", store.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
