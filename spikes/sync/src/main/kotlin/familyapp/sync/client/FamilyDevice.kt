package familyapp.sync.client

import familyapp.sync.protocol.DeviceIdentity
import familyapp.sync.protocol.EnvelopeUpload
import familyapp.sync.protocol.FamilyCipher
import familyapp.sync.protocol.Hashing
import familyapp.sync.protocol.TimerRequest
import java.util.Base64
import java.util.UUID

/**
 * A simulated phone: local replica, outbox of unsent operations, sync cursor.
 * Works fully offline; [sync] exchanges encrypted batches with the relay.
 */
class FamilyDevice(
    val identity: DeviceIdentity,
    private val familyId: String,
    familyKey: ByteArray,
    wallClock: () -> Long,
) {
    val state = ReplicaState()
    private val cipher = FamilyCipher(familyKey)
    private val clock = HybridLogicalClock(identity.deviceId, wallClock)
    private val outbox = mutableListOf<Operation>()
    private var cursor = 0L

    val deviceId: String get() = identity.deviceId

    fun set(recordId: String, field: String, value: String?) {
        val op = Operation(recordId, field, value, clock.now())
        state.apply(op)
        outbox += op
    }

    fun delete(recordId: String) = set(recordId, Operation.DELETED_FIELD, "true")

    suspend fun sync(relay: RelayClient) {
        if (outbox.isNotEmpty()) {
            val batch = OperationBatch(outbox.toList())
            val plaintext = RelayClient.json.encodeToString(batch).toByteArray()
            val sealed = cipher.encrypt(plaintext, associatedData = familyId.toByteArray())
            relay.upload(EnvelopeUpload(UUID.randomUUID().toString(), b64(sealed)))
            outbox.clear()
        }
        val response = relay.pull(cursor)
        for (envelope in response.envelopes) {
            if (envelope.senderDevice != deviceId) {
                val plaintext = cipher.decrypt(Base64.getDecoder().decode(envelope.ciphertext), familyId.toByteArray())
                val batch = RelayClient.json.decodeFromString<OperationBatch>(plaintext.decodeToString())
                batch.operations.forEach { clock.observe(it.hlc); state.apply(it) }
            }
            cursor = envelope.seq
        }
        if (response.envelopes.isNotEmpty()) relay.ack(cursor)
    }

    /**
     * Prepares a blind timer for an escalation. Returns the request for the relay and the
     * cancel token, which travels to the other devices inside the encrypted record.
     */
    fun escalationTimer(fireAtMs: Long, recipients: List<String>, message: String): Pair<TimerRequest, String> {
        val cancelToken = UUID.randomUUID().toString()
        val sealed = cipher.encrypt(message.toByteArray(), associatedData = familyId.toByteArray())
        val request = TimerRequest(
            id = UUID.randomUUID().toString(),
            fireAtMs = fireAtMs,
            recipients = recipients,
            ciphertext = b64(sealed),
            cancelHash = Hashing.sha256Hex(cancelToken.toByteArray()),
        )
        return request to cancelToken
    }

    fun decryptPush(ciphertext: String): String =
        cipher.decrypt(Base64.getDecoder().decode(ciphertext), familyId.toByteArray()).decodeToString()

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
