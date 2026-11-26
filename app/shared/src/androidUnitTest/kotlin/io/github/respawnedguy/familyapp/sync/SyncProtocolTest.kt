package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.domain.CURRENT_SCHEMA
import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncProtocolTest {
    private val crypto = JcaSyncCrypto()

    /**
     * Someone who photographed the QR code puts himself between the two phones (two separate
     * handshakes). He can talk to both, but the six digits differ: the people notice.
     */
    @Test
    fun manInTheMiddleWithTheQrSecretGetsDifferentDigits(): Unit = runBlocking {
        val secret = crypto.randomBytes(32)
        val (inviterSide, attackerToInviter) = MemoryChannel.pair()
        val (attackerToJoiner, joinerSide) = MemoryChannel.pair()
        val inviter = async { Handshake.respond(Handshake.readPurpose(inviterSide), secret, crypto) }
        val attackerA = Handshake.initiate(attackerToInviter, Purpose.PAIR, secret, crypto)
        val attackerB = async { Handshake.respond(Handshake.readPurpose(attackerToJoiner), secret, crypto) }
        val joiner = Handshake.initiate(joinerSide, Purpose.PAIR, secret, crypto)
        val atInviter = verificationCode(crypto, inviter.await().transcript)
        val atJoiner = verificationCode(crypto, joiner.transcript)
        assertEquals(atInviter, verificationCode(crypto, attackerA.transcript))
        assertEquals(atJoiner, verificationCode(crypto, attackerB.await().transcript))
        assertNotEquals(atInviter, atJoiner)
    }

    @Test
    fun joinerRefusesADeviceThatIsNotTheOneFromTheQrCode(): Unit = runBlocking {
        val secret = crypto.randomBytes(32)
        val (a, b) = MemoryChannel.pair()
        val server = async { Handshake.respond(Handshake.readPurpose(b), secret, crypto) }
        val joinerChannel = Handshake.initiate(a, Purpose.PAIR, secret, crypto)
        val inviterChannel = server.await()
        val inv = Invitation(inviterName = "Nils", inviterDeviceId = "echtes-geraet", secret = "", addresses = listOf("10.0.0.2:1"), expiresAtMs = Long.MAX_VALUE)
        val fake = async { PairingProtocol.invite(inviterChannel, crypto, PairHello("anderes-geraet", "Fake", "Nils")) { null } }
        assertFailsWith<PairingException> {
            PairingProtocol.join(joinerChannel, crypto, inv, PairHello("lisa", "Pixel", "Lisa")) { true }
        }
        fake.cancel()
    }

    @Test
    fun invitationRoundTripAndRejectsOtherCodes() {
        val inv = Invitation(
            inviterName = "Nils", inviterDeviceId = "dev", secret = "c2VjcmV0",
            addresses = listOf("192.168.178.20:47420", "100.101.1.2:47420"), expiresAtMs = 123,
        )
        val code = inv.encode()
        assertTrue(code.startsWith(Invitation.PREFIX))
        assertTrue(code.length < 400, "QR stays small: ${code.length}")
        assertEquals(inv, Invitation.decode("  $code\n"))
        assertNull(Invitation.decode("FAMILYAPP1:kaputt"))
        assertNull(Invitation.decode("https://example.org"))
        assertNull(Invitation.decode(inv.copy(addresses = emptyList()).encode()))
    }

    @Test
    fun differentAppVersionsRefuseWithAReason(): Unit = runBlocking {
        val (a, b) = MemoryChannel.pair()
        val old = FakeParticipant("Alt", CURRENT_SCHEMA)
        val new = FakeParticipant("Neu", CURRENT_SCHEMA + 1)
        val responder = async { runCatching { SyncSession(b, new).respond() } }
        val e = assertFailsWith<SyncRefusedException> { SyncSession(a, old).initiate() }
        assertTrue(e.message!!.contains("andere App-Version"))
        assertTrue(responder.await().isFailure)
        assertEquals(0, old.applied)
    }

    @Test
    fun photoNamesCannotEscapeThePhotoFolder() {
        assertTrue(PhotoFiles.isSafeName("3f2a-11.jpg"))
        listOf("../familyapp.json", "a/b.jpg", "", ".hidden", "x.jpg/..").forEach { assertFalse(PhotoFiles.isSafeName(it), it) }
        val dir = Files.createTempDirectory("photos").toFile()
        val photos = DirPhotoFiles(dir)
        File(dir.parentFile, "secret.txt").writeText("x")
        assertNull(photos.read("../secret.txt"))
        assertFalse(photos.exists("../secret.txt"))
        assertFailsWith<IllegalArgumentException> { photos.write("../evil", byteArrayOf(1)) }
        dir.deleteRecursively()
    }

    @Test
    fun tailscaleAddressesAreRecognised() {
        assertTrue(TcpTransport.isTailscale("100.101.1.2"))
        assertFalse(TcpTransport.isTailscale("100.128.0.1"))
        assertFalse(TcpTransport.isTailscale("192.168.178.20"))
        assertFalse(TcpTransport.isTailscale("fe80::1"))
    }

    private class FakeParticipant(val name: String, val schema: Int) : SyncParticipant {
        var applied = 0
        override suspend fun hello() = Hello(SYNC_PROTOCOL, schema, KnownDevice(name, name))
        override suspend fun digest(): Map<String, Stamp> = emptyMap()
        override suspend fun recordsFor(theirs: Map<String, Stamp>) = SyncRecords()
        override suspend fun apply(records: SyncRecords) = records.size.also { applied += it }
        override suspend fun missingPhotos() = emptyList<String>()
        override suspend fun ownPhotoNames() = emptyList<String>()
        override suspend fun readPhoto(name: String): ByteArray? = null
        override suspend fun writePhoto(name: String, bytes: ByteArray) = Unit
        override suspend fun met(other: Hello) = Unit
    }
}
