package familyapp.sync.protocol

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric family key. Encrypts every payload end-to-end with ChaCha20-Poly1305 (JDK built-in).
 * The relay never sees this key. Plaintext is padded to fixed size buckets so the relay
 * learns only a coarse size class.
 *
 * Spike note: the apps will use XChaCha20-Poly1305 (libsodium) with 192-bit nonces.
 * A random 96-bit nonce is acceptable here because a spike encrypts few messages.
 */
class FamilyCipher(private val key: ByteArray) {
    init {
        require(key.size == KEY_BYTES) { "Family key must be $KEY_BYTES bytes" }
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
        cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(pad(plaintext))
    }

    fun decrypt(sealed: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        require(sealed.size > NONCE_BYTES) { "Ciphertext too short" }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val cipher = cipher(Cipher.DECRYPT_MODE, nonce)
        cipher.updateAAD(associatedData)
        return unpad(cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES))
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance("ChaCha20-Poly1305").apply {
            init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        }

    companion object {
        const val KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val LENGTH_PREFIX_BYTES = 4
        private val BUCKETS = intArrayOf(256, 1024, 4096, 16384, 65536)
        private val random = SecureRandom()

        fun generateKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

        internal fun pad(plaintext: ByteArray): ByteArray {
            val needed = plaintext.size + LENGTH_PREFIX_BYTES
            val size = BUCKETS.firstOrNull { it >= needed } ?: roundUp(needed, BUCKETS.last())
            return ByteBuffer.allocate(size).putInt(plaintext.size).put(plaintext).array()
        }

        internal fun unpad(padded: ByteArray): ByteArray {
            val length = ByteBuffer.wrap(padded).int
            require(length in 0..(padded.size - LENGTH_PREFIX_BYTES)) { "Invalid padding" }
            return padded.copyOfRange(LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + length)
        }

        private fun roundUp(value: Int, step: Int) = ((value + step - 1) / step) * step
    }
}
