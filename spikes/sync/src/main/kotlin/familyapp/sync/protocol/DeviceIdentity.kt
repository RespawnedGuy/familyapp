package familyapp.sync.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Ed25519 key pair of one device. The device id is derived from the public key. */
class DeviceIdentity private constructor(private val keyPair: KeyPair) {
    val publicKeyEncoded: String = encodePublicKey(keyPair.public)
    val deviceId: String = deviceIdOf(publicKeyEncoded)

    fun sign(message: ByteArray): String {
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(keyPair.private)
        signer.update(message)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    companion object {
        private const val ALGORITHM = "Ed25519"

        fun generate(): DeviceIdentity = DeviceIdentity(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair())

        fun verify(publicKeyEncoded: String, message: ByteArray, signature: String): Boolean = runCatching {
            val key = KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyEncoded)))
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(message)
                verify(Base64.getDecoder().decode(signature))
            }
        }.getOrDefault(false)

        fun deviceIdOf(publicKeyEncoded: String): String =
            Hashing.sha256Hex(publicKeyEncoded.toByteArray()).take(32)

        private fun encodePublicKey(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
    }
}
