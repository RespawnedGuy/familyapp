package io.github.respawnedguy.familyapp.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reading files that someone else can put there: a backup the person picks, the shared sync folder
 * (threat model OR-3, docs/qualitaet/bedrohungsmodell.md). Nothing the app writes comes near the
 * limit, so a larger file is refused instead of filling the memory.
 */
object BoundedRead {
    /** A sync frame holds at most 32 MB (TcpTransport.MAX_FRAME_BYTES); a state file is never larger. */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** Reads the whole stream, or throws [IOException] as soon as it is longer than [max]. */
    fun readAtMost(input: InputStream, max: Long = MAX_FILE_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > max) throw IOException("Datei ist größer als ${max / MB} MB")
            out.write(buffer, 0, n)
        }
    }

    fun readAtMost(file: File, max: Long = MAX_FILE_BYTES): ByteArray {
        if (file.length() > max) throw IOException("Datei ist größer als ${max / MB} MB")
        return file.inputStream().use { readAtMost(it, max) }
    }

    private const val CHUNK_BYTES = 64 * 1024
    private const val MB = 1024 * 1024
}
