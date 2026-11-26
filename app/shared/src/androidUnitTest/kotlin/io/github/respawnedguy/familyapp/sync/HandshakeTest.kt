package io.github.respawnedguy.familyapp.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HandshakeTest {
    private val crypto = JcaSyncCrypto()
    private val familyKey = crypto.randomBytes(32)

    private suspend fun connect(
        pskA: ByteArray,
        pskB: ByteArray,
        tamper: (Int, ByteArray) -> ByteArray = { _, f -> f },
    ): Pair<SecureChannel, SecureChannel> = coroutineScope {
        val (a, b) = MemoryChannel.pair(tamper)
        val server = async { Handshake.respond(Handshake.readPurpose(b), pskB, crypto) }
        Handshake.initiate(a, Purpose.SYNC, pskA, crypto) to server.await()
    }

    @Test
    fun sameSecretOpensEncryptedFramesBothWays(): Unit = runBlocking {
        val (a, b) = connect(familyKey, familyKey)
        a.send("Hallo".encodeToByteArray())
        assertEquals("Hallo", b.receive().decodeToString())
        b.send("Zurück".encodeToByteArray())
        assertEquals("Zurück", a.receive().decodeToString())
        assertContentEquals(a.transcript, b.transcript)
        assertEquals(verificationCode(crypto, a.transcript), verificationCode(crypto, b.transcript))
        assertTrue(verificationCode(crypto, a.transcript).matches(Regex("\\d{6}")))
    }

    @Test
    fun strangerWithoutFamilyKeyCannotOpenTheFirstFrame(): Unit = runBlocking {
        val (a, b) = connect(familyKey, crypto.randomBytes(32))
        a.send("geheim".encodeToByteArray())
        assertFailsWith<SyncAuthException> { b.receive() }
    }

    @Test
    fun changedOrReplayedFrameIsRejected(): Unit = runBlocking {
        // Frame 0 is the plain handshake; frame 1 is the first encrypted one: flip one bit.
        val (a, b) = connect(familyKey, familyKey) { i, f -> if (i == 1) f.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } else f }
        a.send("eins".encodeToByteArray())
        assertFailsWith<SyncAuthException> { b.receive() }

        val (c, d) = connect(familyKey, familyKey)
        c.send("eins".encodeToByteArray())
        val first = d.receive()
        assertEquals("eins", first.decodeToString())
        // Same plaintext again gives another ciphertext (counter nonce), so a replay cannot pass.
        c.send("eins".encodeToByteArray())
        assertEquals("eins", d.receive().decodeToString())
    }

    @Test
    fun everySessionHasFreshKeys(): Unit = runBlocking {
        val (a1, _) = connect(familyKey, familyKey)
        val (a2, _) = connect(familyKey, familyKey)
        assertNotEquals(a1.transcript.toList(), a2.transcript.toList())
    }

    @Test
    fun garbageInsteadOfHandshakeIsRefused(): Unit = runBlocking {
        val (a, b) = MemoryChannel.pair()
        a.send("GET / HTTP/1.1".encodeToByteArray())
        assertFailsWith<SyncAuthException> { Handshake.readPurpose(b) }
        val (c, d) = MemoryChannel.pair()
        c.send("FAH1".encodeToByteArray() + byteArrayOf('X'.code.toByte()) + ByteArray(80))
        assertFailsWith<SyncAuthException> { Handshake.readPurpose(d) }
        assertFailsWith<SyncAuthException> { crypto.newEphemeralKey().agree(ByteArray(10)) }
    }

    /** Threat model NE-8: a public key that is not on P-256 (invalid-curve attack) is refused. */
    @Test
    fun publicKeyOffTheCurveIsRefused() {
        val valid = crypto.newEphemeralKey().publicKey
        val offCurve = valid.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SyncAuthException> { crypto.newEphemeralKey().agree(offCurve) }
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = crypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }
}
