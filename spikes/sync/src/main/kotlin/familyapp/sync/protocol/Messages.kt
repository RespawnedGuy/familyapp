package familyapp.sync.protocol

import kotlinx.serialization.Serializable

@Serializable
data class CreateFamilyRequest(val familyId: String, val publicKey: String)

@Serializable
data class AddDeviceRequest(val publicKey: String, val endorsedBy: String, val endorsement: String)

/** Opaque, end-to-end encrypted batch of operations. [id] is chosen by the client for idempotent retries. */
@Serializable
data class EnvelopeUpload(val id: String, val ciphertext: String)

@Serializable
data class StoredEnvelope(val seq: Long, val id: String, val senderDevice: String, val ciphertext: String)

@Serializable
data class PullResponse(val envelopes: List<StoredEnvelope>, val latestSeq: Long)

@Serializable
data class AckRequest(val seq: Long)

/**
 * Blind timer: at [fireAtMs] the relay pushes [ciphertext] to [recipients],
 * unless someone presents the preimage of [cancelHash] first.
 */
@Serializable
data class TimerRequest(
    val id: String,
    val fireAtMs: Long,
    val recipients: List<String>,
    val ciphertext: String,
    val cancelHash: String,
)

@Serializable
data class CancelTimerRequest(val cancelToken: String)

/** Live notification over WebSocket while an app is open. */
@Serializable
data class LiveEvent(val latestSeq: Long)
