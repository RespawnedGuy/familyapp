package familyapp.p2p

import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.b64
import familyapp.p2p.net.InMemoryPipe
import familyapp.p2p.net.PeerClient
import familyapp.p2p.net.PeerServer
import familyapp.p2p.pairing.Confirmation
import familyapp.p2p.pairing.Invitation
import familyapp.p2p.pairing.Inviter
import familyapp.p2p.pairing.Joiner
import familyapp.p2p.pairing.PairingPrompt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Acceptance criterion 3: QR pairing with confirmation on both devices, no account. */
class PairingTest {

    private class Recorder(private val answer: Boolean) : Confirmation {
        var prompt: PairingPrompt? = null
        override suspend fun confirm(prompt: PairingPrompt): Boolean { this.prompt = prompt; return answer }
    }

    @Test
    fun `QR pairing over TCP shows the same code on both screens, then the new phone syncs`() = runBlocking {
        val (a, _, c) = TestFamily(listOf("Nils", "B", "C")).peers
        a.set("aufgabe1", "title", "Turnbeutel packen")
        val inviterSide = Recorder(true)
        val inviter = Inviter(a, listOf("placeholder"))
        val server = PeerServer(a, inviter, inviterSide)
        val job = server.start(this)
        try {
            withTimeout(20_000) {
                val address = "127.0.0.1:${server.port}"
                val invitation = Invitation.decode(inviter.createInvitation().copy(addresses = listOf(address)).encode())
                val joinerSide = Recorder(true)
                val raw = PeerClient.connect(address).also { it.send(PeerServer.MODE_PAIR.toByteArray()) }
                val joined = assertNotNull(Joiner.join(raw, invitation, DeviceIdentity.generate(), "Pixel Partnerin", joinerSide))

                assertEquals(inviterSide.prompt!!.code, joinerSide.prompt!!.code)
                assertTrue(inviterSide.prompt!!.code.matches(Regex("\\d{6}")))
                assertEquals("Pixel Partnerin", inviterSide.prompt!!.otherDeviceName)
                assertEquals("Nils", joinerSide.prompt!!.otherDeviceName)

                val d = joined.peer
                PeerClient.sync(address, d)
                assertEquals("Turnbeutel packen", d.state.get("aufgabe1", "title"))
                // C has never seen D's admission: D's handshake carries the chain.
                d.set("aufgabe2", "title", "Brotdose")
                val (l, r) = encounter(d, c)
                assertTrue(l.isSuccess && r.isSuccess, "${l.exceptionOrNull()} / ${r.exceptionOrNull()}")
                assertEquals("Brotdose", c.state.get("aufgabe2", "title"))
                assertTrue(c.membership.contains(d.deviceId))
            }
        } finally {
            server.stop(); job.cancel()
        }
    }

    private suspend fun pair(
        inviter: Inviter,
        invitation: Invitation,
        inviterAnswer: Boolean = true,
        joinerAnswer: Boolean = true,
    ): Pair<Result<*>, Result<familyapp.p2p.pairing.Joined?>> = coroutineScope {
        val (left, right) = InMemoryPipe.pair()
        val i = async { runCatching { inviter.accept(right, Recorder(inviterAnswer)) } }
        val j = async { runCatching { Joiner.join(left, invitation, DeviceIdentity.generate(), "Neu", Recorder(joinerAnswer)) } }
        i.await() to j.await()
    }

    @Test
    fun `wrong secret gets nothing`() = runTest {
        val a = TestFamily(listOf("A")).peers.single()
        val inviter = Inviter(a, emptyList())
        val invitation = inviter.createInvitation().copy(secret = b64(ByteArray(32)))
        val (i, j) = pair(inviter, invitation)
        assertTrue(i.isFailure && j.isFailure)
        assertEquals(1, a.membership.all().size)
    }

    @Test
    fun `impersonated inviter is detected via the device id in the QR code`() = runTest {
        val a = TestFamily(listOf("A")).peers.single()
        val inviter = Inviter(a, emptyList())
        val invitation = inviter.createInvitation().copy(inviterDeviceId = DeviceIdentity.generate().deviceId)
        val (_, j) = pair(inviter, invitation)
        assertTrue(j.isFailure)
    }

    @Test
    fun `either person declining stops the pairing and no key is handed out`() = runTest {
        val a = TestFamily(listOf("A")).peers.single()
        for ((inv, join) in listOf(false to true, true to false)) {
            val inviter = Inviter(a, emptyList())
            val (i, j) = pair(inviter, inviter.createInvitation(), inv, join)
            assertNull(i.getOrThrow())
            assertNull(j.getOrThrow())
        }
        assertEquals(1, a.membership.all().size)
    }

    @Test
    fun `invitation works once and expires`() = runTest {
        val a = TestFamily(listOf("A")).peers.single()
        var now = 0L
        val inviter = Inviter(a, emptyList(), now = { now })
        val invitation = inviter.createInvitation()
        assertNotNull(pair(inviter, invitation).second.getOrThrow())
        assertTrue(pair(inviter, invitation).second.isFailure, "second use fails")

        val late = inviter.createInvitation()
        now += Invitation.VALIDITY_MS + 1
        val (i, j) = pair(inviter, late)
        assertTrue(i.isFailure && j.isFailure)
        assertFalse(a.membership.all().size > 2)
    }
}
