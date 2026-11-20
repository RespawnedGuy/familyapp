package familyapp.sync.relay

import familyapp.sync.protocol.DeviceIdentity
import familyapp.sync.protocol.RequestSigning
import io.ktor.server.application.ApplicationCall

/** Checks device signature, clock skew and nonce replay. Returns the device id or null. */
class RequestVerifier(private val store: RelayStore, private val clock: () -> Long) {

    fun verify(call: ApplicationCall, familyId: String, body: ByteArray): String? {
        val headers = call.request.headers
        val deviceId = headers[RequestSigning.HEADER_DEVICE] ?: return null
        val timestamp = headers[RequestSigning.HEADER_TIMESTAMP]?.toLongOrNull() ?: return null
        val nonce = headers[RequestSigning.HEADER_NONCE] ?: return null
        val signature = headers[RequestSigning.HEADER_SIGNATURE] ?: return null

        val now = clock()
        if (kotlin.math.abs(now - timestamp) > RequestSigning.MAX_CLOCK_SKEW_MS) return null
        val publicKey = store.publicKey(familyId, deviceId) ?: return null
        val canonical = RequestSigning.canonical(call.request.local.method.value, call.request.local.uri, timestamp, nonce, body)
        if (!DeviceIdentity.verify(publicKey, canonical, signature)) return null
        if (!store.rememberNonce(deviceId, nonce, timestamp + 2 * RequestSigning.MAX_CLOCK_SKEW_MS)) return null
        return deviceId
    }
}
