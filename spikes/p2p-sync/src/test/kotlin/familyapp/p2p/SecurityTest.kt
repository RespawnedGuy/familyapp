package familyapp.p2p

import familyapp.p2p.core.Change
import familyapp.p2p.core.FamilyPeer
import familyapp.p2p.core.Membership
import familyapp.p2p.core.UntrustedChangeException
import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.b64
import familyapp.p2p.crypto.unb64
import familyapp.p2p.net.HandshakeException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Acceptance criterion 2 (in-memory part): strangers, tampering and forging middle peers are caught. */
class SecurityTest {

    @Test
    fun `device of another family is rejected before any data flows`() = runTest {
        val (a, _) = TestFamily(listOf("A", "B")).peers
        val stranger = TestFamily(listOf("X")).peers.single()
        a.set("task1", "title", "Elternabend")
        val (left, right) = encounter(stranger, a)
        assertIs<HandshakeException>(right.exceptionOrNull())
        assertTrue(left.isFailure)
        assertEquals(0, stranger.log.size())
        assertFalse(a.membership.contains(stranger.deviceId))
    }

    @Test
    fun `self-endorsed device presenting a forged chain is rejected`() = runTest {
        val family = TestFamily(listOf("A", "B"))
        val a = family.peers[0]
        val intruder = DeviceIdentity.generate()
        val forged = Membership(family.familyId).apply {
            merge(a.membership.all())
            // The intruder signs its own admission; the chain does not lead back to the founder.
        }
        val fake = Membership.endorse(family.familyId, intruder, intruder.publicKeyEncoded, "Fake")
        assertTrue(forged.merge(listOf(fake)).isEmpty(), "membership refuses an endorsement by a non-member")
    }

    @Test
    fun `tampered frame breaks the session without corrupting state`() = runTest {
        val (a, b) = TestFamily(listOf("A", "B")).peers
        repeat(50) { a.set("r$it", "title", "x$it") }
        // Frame 0 = ephemeral key, 1 = auth, 2 = hello, 3 = first chunk.
        val (_, right) = encounter(a, b) { TamperingChannel(it, 3) }
        assertIs<HandshakeException>(right.exceptionOrNull())
        assertEquals(0, b.log.size())
        val (l, r) = encounter(a, b)
        assertTrue(l.isSuccess && r.isSuccess)
        assertEquals(a.state.snapshot(), b.state.snapshot())
    }

    @Test
    fun `middle peer cannot alter or forge changes it forwards`() = runTest {
        val (a, b, c) = TestFamily(listOf("A", "B", "C")).peers
        val original = a.set("task1", "assignee", "Mama")
        encounter(a, b); encounter(b, c) // everyone knows everyone now
        val c2 = FamilyPeer(c.identity, b.exportFamilyKey(), c.membership.copy())

        // B swaps the ciphertext for one of its own, keeping A's signature.
        val bOwn = b.set("task1", "assignee", "Papa")
        val swapped = original.copy(ciphertext = bOwn.ciphertext)
        assertFailsWith<UntrustedChangeException> { c2.receive(swapped) }

        // B flips a bit in the ciphertext.
        val bytes = unb64(original.ciphertext).also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<UntrustedChangeException> { c2.receive(original.copy(ciphertext = b64(bytes))) }

        // B claims A as origin but signs itself.
        val forged = Change(a.deviceId, 1, bOwn.ciphertext, "")
            .let { it.copy(signature = b.identity.sign(it.signedPayload(a.familyId))) }
        assertFailsWith<UntrustedChangeException> { c2.receive(forged) }

        // The genuine change still goes through.
        assertTrue(c2.receive(original))
        assertEquals("Mama", c2.state.get("task1", "assignee"))
    }
}
