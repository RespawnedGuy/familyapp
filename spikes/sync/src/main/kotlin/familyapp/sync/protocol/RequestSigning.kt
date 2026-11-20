package familyapp.sync.protocol

/**
 * Every relay request carries these headers. The signature covers method, path, timestamp,
 * nonce and the body hash, so the relay can reject tampered or replayed requests.
 */
object RequestSigning {
    const val HEADER_DEVICE = "X-Device"
    const val HEADER_TIMESTAMP = "X-Timestamp"
    const val HEADER_NONCE = "X-Nonce"
    const val HEADER_SIGNATURE = "X-Signature"
    const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L

    fun canonical(method: String, path: String, timestampMs: Long, nonce: String, body: ByteArray): ByteArray =
        listOf(method.uppercase(), path, timestampMs.toString(), nonce, Hashing.sha256Hex(body))
            .joinToString("\n")
            .toByteArray()

    /** What an existing device signs to admit a new device into the family. */
    fun endorsement(familyId: String, newDevicePublicKey: String): ByteArray =
        "endorse\n$familyId\n$newDevicePublicKey".toByteArray()
}
