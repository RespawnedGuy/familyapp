package familyapp.p2p.crypto

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Spike note: everything here uses JDK 21 primitives only. The apps will use libsodium
// (XChaCha20-Poly1305) and a vetted handshake (Noise XX), see ADR 0003.

internal val secureRandom = SecureRandom()

fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)

object Hashing {
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(ByteBuffer.allocate(4).putInt(it.size).array()); update(it) }
        digest()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Ed25519 key pair of one device. The device id is derived from the public key (copied from spikes/sync). */
class DeviceIdentity private constructor(private val keyPair: KeyPair) {
    val publicKeyEncoded: String = b64(keyPair.public.encoded)
    val deviceId: String = deviceIdOf(publicKeyEncoded)

    fun sign(message: ByteArray): String = Signature.getInstance(ALGORITHM).run {
        initSign(keyPair.private)
        update(message)
        b64(sign())
    }

    /** Private key as PKCS#8, only for the spike's JSON state file. Apps keep it in Keystore/Keychain. */
    fun exportPrivateKey(): String = b64(keyPair.private.encoded)

    companion object {
        private const val ALGORITHM = "Ed25519"

        fun generate(): DeviceIdentity = DeviceIdentity(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair())

        fun restore(privateKey: String, publicKey: String): DeviceIdentity {
            val factory = KeyFactory.getInstance(ALGORITHM)
            return DeviceIdentity(
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(unb64(publicKey))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(unb64(privateKey))),
                ),
            )
        }

        fun verify(publicKeyEncoded: String, message: ByteArray, signature: String): Boolean = runCatching {
            val key = KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(unb64(publicKeyEncoded)))
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(message)
                verify(unb64(signature))
            }
        }.getOrDefault(false)

        fun deviceIdOf(publicKeyEncoded: String): String = Hashing.sha256Hex(publicKeyEncoded.toByteArray()).take(32)
    }
}

/**
 * Symmetric family key (copied from spikes/sync). Encrypts every operation end-to-end with
 * ChaCha20-Poly1305 and pads to size buckets, so neither a network observer nor a cloud
 * folder learns more than a coarse size class.
 */
class FamilyCipher(private val key: ByteArray) {
    init {
        require(key.size == KEY_BYTES) { "Family key must be $KEY_BYTES bytes" }
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = chacha(Cipher.ENCRYPT_MODE, key, nonce)
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(pad(plaintext))
    }

    fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        require(sealed.size > NONCE_BYTES) { "Ciphertext too short" }
        val cipher = chacha(Cipher.DECRYPT_MODE, key, sealed.copyOfRange(0, NONCE_BYTES))
        cipher.updateAAD(associatedData)
        return unpad(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES))
    }

    companion object {
        const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private val BUCKETS = intArrayOf(256, 1024, 4096, 16384, 65536)

        fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also { secureRandom.nextBytes(it) }

        private fun pad(plaintext: ByteArray): ByteArray {
            val needed = plaintext.size + 4
            val size = BUCKETS.firstOrNull { it >= needed } ?: (((needed + 65535) / 65536) * 65536)
            return ByteBuffer.allocate(size).putInt(plaintext.size).put(plaintext).array()
        }

        private fun unpad(padded: ByteArray): ByteArray {
            val length = ByteBuffer.wrap(padded).int
            require(length in 0..(padded.size - 4)) { "Invalid padding" }
            return padded.copyOfRange(4, 4 + length)
        }
    }
}

internal fun chacha(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
    Cipher.getInstance("ChaCha20-Poly1305").apply { init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce)) }

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info.toByteArray() + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, n)
            offset += n
            counter++
        }
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
}

/** Ephemeral X25519 key exchange for forward secrecy of each session. */
class EphemeralKey {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
    val publicEncoded: ByteArray = keyPair.public.encoded

    fun agree(peerPublicEncoded: ByteArray): ByteArray {
        val peer: PublicKey = KeyFactory.getInstance("X25519").generatePublic(X509EncodedKeySpec(peerPublicEncoded))
        return KeyAgreement.getInstance("X25519").run {
            init(keyPair.private)
            doPhase(peer, true)
            generateSecret()
        }
    }
}

/**
 * Transport encryption for one direction of one session. The nonce is a frame counter, so a
 * dropped, replayed or reordered frame fails authentication.
 */
class FrameCipher(private val key: ByteArray) {
    private var counter = 0L

    fun seal(plaintext: ByteArray): ByteArray = chacha(Cipher.ENCRYPT_MODE, key, nextNonce()).doFinal(plaintext)

    fun open(ciphertext: ByteArray): ByteArray = chacha(Cipher.DECRYPT_MODE, key, nextNonce()).doFinal(ciphertext)

    private fun nextNonce(): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()
}
