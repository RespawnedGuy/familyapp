package io.github.respawnedguy.familyapp.sync

/**
 * Crypto building blocks the sync needs from the platform (ADR 0006). Android and the JVM use
 * javax.crypto (all available from Android 8 on), iOS will use CryptoKit with the same algorithms.
 */
interface SyncCrypto {
    fun randomBytes(size: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** A fresh P-256 key pair for one session only (forward secrecy). */
    fun newEphemeralKey(): EphemeralKey

    /** AES-256-GCM with a 96-bit nonce and a 128-bit tag. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Throws [SyncAuthException] if the key is wrong or the data was changed. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray
}

interface EphemeralKey {
    /** Public key, X.509 SubjectPublicKeyInfo (DER), so every platform can read it. */
    val publicKey: ByteArray

    /** ECDH shared secret with the other side's public key; throws [SyncAuthException] on a bad key. */
    fun agree(otherPublicKey: ByteArray): ByteArray
}

/** The other device is not part of the family, used a wrong pairing code, or the data was tampered with. */
class SyncAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A message stream between two devices (TCP on the LAN or the tailnet, in memory in tests). */
interface FrameChannel {
    suspend fun send(frame: ByteArray)

    /** Next frame; throws when the connection ended. */
    suspend fun receive(): ByteArray
    fun close()
}

/** What a connection is for. Decides which secret the handshake mixes in. */
enum class Purpose(val code: Byte) {
    /** Normal sync between family devices: secret derived from the family key. */
    SYNC('S'.code.toByte()),

    /** Pairing a new device: secret from the QR code. */
    PAIR('P'.code.toByte()),
}

/** HKDF-SHA256 (RFC 5869) on top of the platform HMAC. */
fun SyncCrypto.hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(salt, ikm)
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
        val n = minOf(previous.size, length - offset)
        previous.copyInto(out, offset, 0, n)
        offset += n
        counter++
    }
    return out
}

/**
 * Encrypted frames after the handshake. Each direction has its own key and a frame counter as
 * nonce, so a dropped, replayed or reordered frame fails authentication and ends the session.
 */
class SecureChannel internal constructor(
    private val raw: FrameChannel,
    private val crypto: SyncCrypto,
    private val sendKey: ByteArray,
    private val receiveKey: ByteArray,
    /** Hash over both handshake messages; the pairing code (SAS) is derived from it. */
    val transcript: ByteArray,
) : FrameChannel {
    private var sent = 0L
    private var received = 0L

    override suspend fun send(frame: ByteArray) {
        raw.send(crypto.aesGcmSeal(sendKey, nonce(sent++), frame, AAD))
    }

    override suspend fun receive(): ByteArray {
        val sealed = raw.receive()
        return crypto.aesGcmOpen(receiveKey, nonce(received++), sealed, AAD)
    }

    override fun close() = raw.close()

    private fun nonce(counter: Long): ByteArray = ByteArray(NONCE_BYTES).also { n ->
        for (i in 0 until Long.SIZE_BYTES) n[NONCE_BYTES - 1 - i] = (counter ushr (Byte.SIZE_BITS * i)).toByte()
    }

    private companion object {
        const val NONCE_BYTES = 12
        val AAD = "familyapp-frame-v1".encodeToByteArray()
    }
}

/**
 * Handshake in the pattern of Noise NNpsk0 (ADR 0006): both sides send a fresh P-256 key and a
 * random value; the session keys come from the ECDH result AND a pre-shared secret (family key for
 * sync, QR secret for pairing). Without that secret the first encrypted frame cannot be opened, so
 * a stranger in the same WLAN learns nothing, not even which family a device belongs to.
 *
 * Message (plain): magic "FAH1" | purpose (1 byte) | random (32) | public key (DER).
 */
object Handshake {
    private val MAGIC = "FAH1".encodeToByteArray()
    private const val RANDOM_BYTES = 32
    private const val KEY_BYTES = 32
    private const val MAX_HELLO_BYTES = 512
    private val LABEL = "familyapp-handshake-v1".encodeToByteArray()

    /** Opens the session as the connecting side. */
    suspend fun initiate(raw: FrameChannel, purpose: Purpose, psk: ByteArray, crypto: SyncCrypto): SecureChannel {
        val ephemeral = crypto.newEphemeralKey()
        val mine = hello(purpose, crypto.randomBytes(RANDOM_BYTES), ephemeral.publicKey)
        raw.send(mine)
        val theirs = raw.receive()
        val parsed = parse(theirs)
        if (parsed.purpose != purpose) throw SyncAuthException("Gegenstelle erwartet etwas anderes")
        return derive(raw, crypto, ephemeral, parsed.publicKey, psk, mine to theirs, initiator = true)
    }

    /** First message of the connecting side, read by the server to decide which secret applies. */
    suspend fun readPurpose(raw: FrameChannel): Pending {
        val first = raw.receive()
        return Pending(raw, first, parse(first))
    }

    /** Answers a [Pending] connection with the secret that belongs to its purpose. */
    suspend fun respond(pending: Pending, psk: ByteArray, crypto: SyncCrypto): SecureChannel {
        val ephemeral = crypto.newEphemeralKey()
        val mine = hello(pending.parsed.purpose, crypto.randomBytes(RANDOM_BYTES), ephemeral.publicKey)
        pending.raw.send(mine)
        val other = pending.parsed.publicKey
        return derive(pending.raw, crypto, ephemeral, other, psk, pending.first to mine, initiator = false)
    }

    class Pending internal constructor(
        internal val raw: FrameChannel,
        internal val first: ByteArray,
        internal val parsed: Parsed,
    ) {
        val purpose: Purpose get() = parsed.purpose
    }

    internal class Parsed(val purpose: Purpose, val publicKey: ByteArray)

    private fun hello(purpose: Purpose, random: ByteArray, publicKey: ByteArray): ByteArray =
        MAGIC + byteArrayOf(purpose.code) + random + publicKey

    private fun parse(message: ByteArray): Parsed {
        val header = MAGIC.size + 1 + RANDOM_BYTES
        val magicOk = message.size > header && message.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        if (!magicOk || message.size > MAX_HELLO_BYTES) {
            throw SyncAuthException("Keine FamilyApp-Gegenstelle")
        }
        val purpose = Purpose.entries.firstOrNull { it.code == message[MAGIC.size] }
            ?: throw SyncAuthException("Unbekannter Verbindungszweck")
        return Parsed(purpose, message.copyOfRange(header, message.size))
    }

    @Suppress("LongParameterList") // all inputs of one key derivation
    private fun derive(
        raw: FrameChannel,
        crypto: SyncCrypto,
        ephemeral: EphemeralKey,
        otherPublic: ByteArray,
        psk: ByteArray,
        hellos: Pair<ByteArray, ByteArray>,
        initiator: Boolean,
    ): SecureChannel {
        val transcript = crypto.sha256(LABEL + hellos.first + hellos.second)
        val shared = ephemeral.agree(otherPublic)
        val salt = crypto.sha256(LABEL + psk)
        val okm = crypto.hkdf(ikm = shared, salt = salt, info = LABEL + transcript, length = 2 * KEY_BYTES)
        val toResponder = okm.copyOfRange(0, KEY_BYTES)
        val toInitiator = okm.copyOfRange(KEY_BYTES, 2 * KEY_BYTES)
        return if (initiator) {
            SecureChannel(raw, crypto, toResponder, toInitiator, transcript)
        } else {
            SecureChannel(raw, crypto, toInitiator, toResponder, transcript)
        }
    }
}
