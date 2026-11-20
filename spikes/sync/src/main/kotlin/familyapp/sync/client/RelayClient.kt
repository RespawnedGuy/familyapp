package familyapp.sync.client

import familyapp.sync.protocol.AckRequest
import familyapp.sync.protocol.AddDeviceRequest
import familyapp.sync.protocol.CancelTimerRequest
import familyapp.sync.protocol.CreateFamilyRequest
import familyapp.sync.protocol.DeviceIdentity
import familyapp.sync.protocol.EnvelopeUpload
import familyapp.sync.protocol.PullResponse
import familyapp.sync.protocol.RequestSigning
import familyapp.sync.protocol.TimerRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import java.util.UUID

class RelayException(val status: Int, message: String) : RuntimeException(message)

/** Signed HTTP access to the relay for one device. */
class RelayClient(
    private val http: HttpClient,
    private val familyId: String,
    private val identity: DeviceIdentity,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = "/v1/families/$familyId"

    suspend fun createFamily() =
        send(HttpMethod.Post, "/v1/families", json.encodeToString(CreateFamilyRequest(familyId, identity.publicKeyEncoded)), signed = false)

    suspend fun addDevice(newDevicePublicKey: String) {
        val endorsement = identity.sign(RequestSigning.endorsement(familyId, newDevicePublicKey))
        val body = AddDeviceRequest(newDevicePublicKey, identity.deviceId, endorsement)
        send(HttpMethod.Post, "$base/devices", json.encodeToString(body), signed = false)
    }

    suspend fun upload(envelope: EnvelopeUpload) =
        send(HttpMethod.Post, "$base/envelopes", json.encodeToString(envelope))

    suspend fun pull(afterSeq: Long): PullResponse =
        json.decodeFromString(send(HttpMethod.Get, "$base/envelopes?after=$afterSeq", "").bodyAsText())

    suspend fun ack(seq: Long) = send(HttpMethod.Post, "$base/ack", json.encodeToString(AckRequest(seq)))

    suspend fun scheduleTimer(timer: TimerRequest) =
        send(HttpMethod.Post, "$base/timers", json.encodeToString(timer))

    suspend fun cancelTimer(timerId: String, cancelToken: String) =
        send(HttpMethod.Post, "$base/timers/$timerId/cancel", json.encodeToString(CancelTimerRequest(cancelToken)))

    /** Builds the auth headers; exposed so tests can forge or replay them. */
    fun signatureHeaders(method: HttpMethod, path: String, body: ByteArray, nonce: String = UUID.randomUUID().toString()): Map<String, String> {
        val timestamp = clock()
        val signature = identity.sign(RequestSigning.canonical(method.value, path, timestamp, nonce, body))
        return mapOf(
            RequestSigning.HEADER_DEVICE to identity.deviceId,
            RequestSigning.HEADER_TIMESTAMP to timestamp.toString(),
            RequestSigning.HEADER_NONCE to nonce,
            RequestSigning.HEADER_SIGNATURE to signature,
        )
    }

    private suspend fun send(method: HttpMethod, path: String, body: String, signed: Boolean = true): HttpResponse {
        val bytes = body.toByteArray()
        val response = http.request(path) {
            this.method = method
            if (signed) signatureHeaders(method, path, bytes).forEach { (k, v) -> header(k, v) }
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(bytes)
            }
        }
        if (!response.status.isSuccess()) throw RelayException(response.status.value, response.bodyAsText())
        return response
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
