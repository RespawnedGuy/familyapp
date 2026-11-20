package familyapp.sync

import familyapp.sync.protocol.LiveEvent
import familyapp.sync.relay.RelayStore
import familyapp.sync.relay.relayModule
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelaySyncTest {

    /** Acceptance criterion 1. */
    @Test
    fun `three devices never online together converge after 1000 random operations`() = testApplication {
        val clock = TestClock()
        val store = RelayStore(tempDb().toString())
        application { relayModule(store, clock::nowMs) }
        val family = TestFamily(createClient { }, clock, size = 3)
        family.register()

        val random = Random(2026)
        repeat(1000) {
            clock.advance(random.nextLong(0, 60_000))
            val device = family.devices[random.nextInt(3)]
            val record = "rec${random.nextInt(40)}"
            when (random.nextInt(10)) {
                0 -> device.delete(record)
                else -> device.set(record, listOf("title", "due", "owner", "status")[random.nextInt(4)], random.nextInt(1000).toString())
            }
            // Devices come online one at a time, at random moments.
            if (random.nextInt(8) == 0) family.sync(family.devices[random.nextInt(3)])
        }
        repeat(2) { family.devices.forEach { family.sync(it) } }

        val expected = family.devices.first().state.snapshot()
        assertTrue(expected.size > 100, "state should not be trivial, was ${expected.size}")
        family.devices.forEach { assertEquals(expected, it.state.snapshot()) }
        assertEquals(0, store.envelopeCount(family.familyId), "envelopes received by all devices must be pruned")
        store.close()
    }

    @Test
    fun `open app gets live event when another device uploads`() = testApplication {
        val clock = TestClock()
        val store = RelayStore(tempDb().toString())
        application { relayModule(store, clock::nowMs) }
        val http = createClient { install(WebSockets) }
        val family = TestFamily(http, clock, size = 2)
        family.register()
        val (listener, writer) = family.devices
        val path = "/v1/families/${family.familyId}/live"

        http.webSocket(path, request = {
            family.client(listener).signatureHeaders(HttpMethod.Get, path, ByteArray(0)).forEach { (k, v) -> header(k, v) }
        }) {
            val received = async { (incoming.receive() as Frame.Text).readText() }
            delay(200) // let the subscription start before the upload
            writer.set("task1", "title", "Elternabend")
            family.sync(writer)
            val event = Json.decodeFromString<LiveEvent>(withTimeout(5_000) { received.await() })
            assertEquals(1, event.latestSeq)
        }
        store.close()
    }
}
