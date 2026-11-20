package familyapp.sync

import familyapp.sync.client.FamilyDevice
import familyapp.sync.client.RelayClient
import familyapp.sync.client.RelayException
import familyapp.sync.protocol.DeviceIdentity
import familyapp.sync.protocol.FamilyCipher
import familyapp.sync.relay.RelayStore
import familyapp.sync.relay.relayModule
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelaySecurityTest {

    private fun secured(block: suspend ApplicationTestBuilder.(TestFamily, HttpClient) -> Unit) = testApplication {
        val clock = TestClock()
        val store = RelayStore(tempDb().toString())
        application { relayModule(store, clock::nowMs) }
        val http = createClient { }
        val family = TestFamily(http, clock, size = 2)
        family.register()
        block(family, http)
        store.close()
    }

    @Test
    fun `tampered body is rejected`() = secured { family, http ->
        val path = "/v1/families/${family.familyId}/envelopes"
        val headers = family.client(family.devices[0]).signatureHeaders(HttpMethod.Post, path, """{"id":"1","ciphertext":"AAAA"}""".toByteArray())
        val response = http.post(path) {
            headers.forEach { (k, v) -> header(k, v) }
            setBody("""{"id":"1","ciphertext":"BBBB"}""")
        }
        assertEquals(401, response.status.value)
    }

    @Test
    fun `replayed request is rejected`() = secured { family, http ->
        val path = "/v1/families/${family.familyId}/envelopes"
        val body = """{"id":"1","ciphertext":"AAAA"}"""
        val headers = family.client(family.devices[0]).signatureHeaders(HttpMethod.Post, path, body.toByteArray())
        val send = suspend { http.post(path) { headers.forEach { (k, v) -> header(k, v) }; setBody(body) }.status.value }
        assertEquals(201, send())
        assertEquals(401, send())
    }

    @Test
    fun `stale timestamp is rejected`() = secured { family, http ->
        val device = family.devices[0]
        // Device clock 10 minutes behind the relay.
        val skewed = RelayClient(http, family.familyId, device.identity) { family.clock.nowMs - 10 * 60_000L }
        assertEquals(401, assertFailsWith<RelayException> { skewed.pull(0) }.status)
    }

    @Test
    fun `device outside the family is rejected`() = secured { family, http ->
        val stranger = DeviceIdentity.generate()
        val client = RelayClient(http, family.familyId, stranger, family.clock::nowMs)
        assertEquals(401, assertFailsWith<RelayException> { client.pull(0) }.status)
    }

    @Test
    fun `stranger cannot admit itself without a valid endorsement`() = secured { family, http ->
        val stranger = DeviceIdentity.generate()
        val forged = RelayClient(http, family.familyId, stranger, family.clock::nowMs)
        // Endorsement signed by the stranger itself, claiming to come from a family device.
        val status = assertFailsWith<RelayException> { forged.addDevice(stranger.publicKeyEncoded) }.status
        assertEquals(403, status)
    }

    @Test
    fun `device with wrong family key cannot read the data`() = secured { family, _ ->
        val (writer, _) = family.devices
        writer.set("task1", "title", "Arzttermin")
        family.sync(writer)
        val impostor = FamilyDevice(family.devices[1].identity, family.familyId, FamilyCipher.generateKey(), family.clock::nowMs)
        assertFailsWith<javax.crypto.AEADBadTagException> { impostor.sync(family.client(family.devices[1])) }
    }
}
