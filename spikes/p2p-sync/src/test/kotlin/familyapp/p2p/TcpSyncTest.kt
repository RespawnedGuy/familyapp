package familyapp.p2p

import familyapp.p2p.net.PeerClient
import familyapp.p2p.net.PeerServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance criterion 2 (network part): real TCP sockets on loopback. A Tailscale address
 * is just another IP for this code, so this covers LAN and tailnet alike.
 */
class TcpSyncTest {

    @Test
    fun `peers sync over TCP through an always-on peer and strangers are turned away`() = runBlocking {
        val (a, b, c) = TestFamily(listOf("A", "B", "C")).peers
        val stranger = TestFamily(listOf("X")).peers.single()
        val failures = mutableListOf<String>()
        val server = PeerServer(b) { result -> result.onFailure { failures += it.message ?: "?" } }
        val job = server.start(this)
        val address = "127.0.0.1:${server.port}"
        try {
            withTimeout(20_000) {
                a.set("termin1", "title", "Zahnarzt Emma")
                a.set("termin1", "due", "2026-12-03T15:00")
                c.set("einkauf", "milch", "2 l")

                PeerClient.sync(address, a)
                val cStats = PeerClient.sync(address, c)
                assertEquals(2, cStats.received, "C gets A's changes via B")
                PeerClient.sync(address, a)

                assertEquals(a.state.snapshot(), c.state.snapshot())
                assertEquals("2 l", a.state.get("einkauf", "milch"))

                val rejected = runCatching { PeerClient.sync(address, stranger) }
                assertTrue(rejected.isFailure)
                assertEquals(0, stranger.log.size())
            }
        } finally {
            server.stop()
            job.cancel()
        }
        assertTrue(failures.any { "not a member" in it }, "server reports the stranger: $failures")
    }
}
