package io.github.respawnedguy.familyapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model TE-1 to TE-4 (docs/qualitaet/bedrohungsmodell.md); the emulator test covers the Android side. */
class SharedInputTest {

    @Test
    fun onlyContentFromAnotherAppIsOpened() {
        assertEquals(ShareRejection.NOT_FROM_OTHER_APP, SharedInput.precheck("file", false, "image/jpeg"))
        assertEquals(ShareRejection.NOT_FROM_OTHER_APP, SharedInput.precheck(null, false, "image/jpeg"))
        assertEquals(ShareRejection.NOT_FROM_OTHER_APP, SharedInput.precheck("content", true, "image/jpeg"))
        assertNull(SharedInput.precheck("content", false, "image/jpeg"))
        assertNull(SharedInput.precheck("CONTENT", false, "Image/PNG"))
    }

    @Test
    fun onlyImagesAreTakenAsPhoto() {
        assertEquals(ShareRejection.NOT_AN_IMAGE, SharedInput.precheck("content", false, null))
        assertEquals(ShareRejection.NOT_AN_IMAGE, SharedInput.precheck("content", false, "text/html"))
        assertEquals(ShareRejection.NOT_AN_IMAGE, SharedInput.precheck("content", false, "application/vnd.android.package-archive"))
    }

    @Test
    fun decodedSizeDecidesNotTheClaimedType() {
        assertEquals(ShareRejection.NOT_AN_IMAGE, SharedInput.postcheck(-1, -1))
        assertEquals(ShareRejection.NOT_AN_IMAGE, SharedInput.postcheck(4000, 0))
        assertEquals(ShareRejection.TOO_MANY_PIXELS, SharedInput.postcheck(50_000, 50_000))
        assertNull(SharedInput.postcheck(7680, 4320)) // 8K photo
    }

    @Test
    fun copyStopsRightAfterTheLimitWhateverTheSenderClaims() {
        var served = 0L
        val endless: (ByteArray) -> Int = { buf -> served += buf.size; buf.size }
        var written = 0L
        assertNull(SharedInput.copyLimited(endless, { _, n -> written += n }, max = 1_000_000))
        assertTrue(written <= 1_000_000, "nothing beyond the limit is written")
        assertTrue(served < 1_000_000 + 2 * 64 * 1024, "reading stops at the limit, not at the end of the stream")
    }

    @Test
    fun copyOfASmallImageKeepsEveryByte() {
        val source = ByteArray(150_000) { (it % 251).toByte() }
        var pos = 0
        val out = ArrayList<Byte>()
        val read: (ByteArray) -> Int = { buf ->
            if (pos >= source.size) -1 else minOf(buf.size, source.size - pos).also { n -> source.copyInto(buf, 0, pos, pos + n); pos += n }
        }
        assertEquals(150_000L, SharedInput.copyLimited(read, { b, n -> for (i in 0 until n) out += b[i] }))
        assertTrue(source.contentEquals(out.toByteArray()))
    }

    @Test
    fun exactlyTheLimitIsStillAccepted() {
        var left = SharedInput.MAX_IMAGE_BYTES
        val read: (ByteArray) -> Int = { buf -> if (left == 0L) -1 else minOf(buf.size.toLong(), left).toInt().also { left -= it } }
        assertEquals(SharedInput.MAX_IMAGE_BYTES, SharedInput.copyLimited(read, { _, _ -> }))
    }

    @Test
    fun sharedTextIsJoinedAndCut() {
        assertEquals("Elternbrief – Ausflug am 4.12.", SharedInput.text("Elternbrief", "Ausflug am 4.12."))
        assertEquals("nur Text", SharedInput.text(" ", "nur Text"))
        val long = SharedInput.text(null, "x".repeat(100_000))
        assertEquals(SharedInput.MAX_TEXT_CHARS, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun vcardIsRecognisedByItsTypes() {
        assertTrue(SharedInput.isVCard("text/x-vcard"))
        assertTrue(SharedInput.isVCard(" Text/VCard; charset=utf-8"))
        assertTrue(SharedInput.isVCard("text/directory"))
        assertFalse(SharedInput.isVCard("text/plain"))
        assertFalse(SharedInput.isVCard(null))
    }

    @Test
    fun kt1VCardFileOnlyFromAnotherAppsProvider() {
        assertTrue(SharedInput.vcardAllowed("content", ownProvider = false))
        assertFalse(SharedInput.vcardAllowed("content", ownProvider = true), "own files cannot be re-shared in")
        assertFalse(SharedInput.vcardAllowed("file", ownProvider = false), "no file:// paths into private storage")
        assertFalse(SharedInput.vcardAllowed(null, ownProvider = false))
    }
}
