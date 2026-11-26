package io.github.respawnedguy.familyapp.data

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Threat model OR-3: files from the shared folder or a picked backup cannot fill the memory. */
class BoundedReadTest {

    @Test
    fun smallFilesAreReadCompletely() {
        val data = ByteArray(200_000) { it.toByte() }
        assertContentEquals(data, BoundedRead.readAtMost(ByteArrayInputStream(data), max = 200_000))
    }

    @Test
    fun aLongerStreamFailsRightAfterTheLimit() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertFailsWith<IOException> { BoundedRead.readAtMost(endless, max = 1_000_000) }
        assertTrue(served < 1_200_000, "stops reading at the limit")
    }

    @Test
    fun aHugeFileIsRefusedWithoutReadingIt() {
        val f = Files.createTempFile("bounded", ".fas").toFile()
        try {
            RandomAccessFile(f, "rw").use { it.setLength(BoundedRead.MAX_FILE_BYTES + 1) }
            assertFailsWith<IOException> { BoundedRead.readAtMost(f) }
        } finally {
            f.delete()
        }
    }

    @Test
    fun aFileOfExactlyTheLimitSizeIsFine() {
        val f: File = Files.createTempFile("bounded", ".bin").toFile()
        try {
            f.writeBytes(ByteArray(1000))
            assertTrue(BoundedRead.readAtMost(f, max = 1000).size == 1000)
        } finally {
            f.delete()
        }
    }
}
