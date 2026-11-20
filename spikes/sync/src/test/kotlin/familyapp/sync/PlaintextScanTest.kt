package familyapp.sync

import familyapp.sync.protocol.FamilyCipher
import familyapp.sync.relay.RelayStore
import familyapp.sync.relay.relayModule
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaintextScanTest {

    private val markers = listOf("Zahnarzt Mia", "Elternabend Klasse 1b", "Frist Klassenfahrt", "schulederkinder.de")

    /** Acceptance criterion 3: the relay database never contains plaintext. */
    @Test
    fun `relay database contains no plaintext`() {
        val db = tempDb()
        var familyId = ""
        testApplication {
            val clock = TestClock()
            val store = RelayStore(db.toString())
            application { relayModule(store, clock::nowMs) }
            val family = TestFamily(createClient { }, clock, size = 2)
            family.register()
            familyId = family.familyId
            val (writer, other) = family.devices
            writer.set("t1", "title", markers[0])
            writer.set("t2", "title", markers[1])
            writer.set("t2", "source", "info@" + markers[3])
            family.sync(writer) // other device stays offline, so envelopes remain stored
            val (timer, _) = writer.escalationTimer(clock.nowMs + 86_400_000, listOf(other.deviceId), markers[2])
            family.client(writer).scheduleTimer(timer)
            assertTrue(store.envelopeCount(family.familyId) > 0, "scan must cover stored envelopes")
            store.close()
        }
        val bytes = Files.readAllBytes(db)
        // Negative control: the scan does find plaintext the relay is allowed to store.
        assertTrue(contains(bytes, familyId.toByteArray()), "scan must detect known plaintext metadata")
        markers.forEach { marker ->
            assertFalse(contains(bytes, marker.toByteArray(Charsets.UTF_8)), "plaintext '$marker' found in relay DB")
        }
        assertFalse(Files.exists(Path.of("$db-wal")), "no WAL file may hold data outside the scan")
    }

    @Test
    fun `padding hides the exact length`() {
        val cipher = FamilyCipher(FamilyCipher.generateKey())
        assertEquals(cipher.encrypt(ByteArray(10)).size, cipher.encrypt(ByteArray(200)).size)
        assertEquals("Turnschuhe", cipher.decrypt(cipher.encrypt("Turnschuhe".toByteArray())).decodeToString())
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
