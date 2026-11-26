package io.github.respawnedguy.familyapp.sync

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [SyncCrypto] with javax.crypto only: P-256 (ECDH), AES-256-GCM, SHA-256 and HMAC-SHA256 are in
 * every Android version since 8.0 and in every JDK, so the app, the house device program and the
 * tests run the same code (ADR 0006). Ed25519/X25519 would need Android 13 or a crypto library.
 */
class JcaSyncCrypto(private val random: SecureRandom = SecureRandom()) : SyncCrypto {

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(data)

    override fun newEphemeralKey(): EphemeralKey {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        val pair = generator.generateKeyPair()
        return object : EphemeralKey {
            override val publicKey: ByteArray = pair.public.encoded

            override fun agree(otherPublicKey: ByteArray): ByteArray = try {
                val other = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(otherPublicKey))
                KeyAgreement.getInstance("ECDH").run {
                    init(pair.private)
                    doPhase(other, true)
                    generateSecret()
                }
            } catch (e: GeneralSecurityException) {
                throw SyncAuthException("Ungültiger Schlüssel der Gegenstelle", e)
            } catch (e: IllegalArgumentException) {
                throw SyncAuthException("Ungültiger Schlüssel der Gegenstelle", e)
            }
        }
    }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, associatedData: ByteArray) =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, associatedData).doFinal(plaintext)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, associatedData: ByteArray) = try {
        cipher(Cipher.DECRYPT_MODE, key, nonce, associatedData).doFinal(ciphertext)
    } catch (e: AEADBadTagException) {
        throw SyncAuthException("Falscher Schlüssel oder veränderte Daten", e)
    } catch (e: GeneralSecurityException) {
        throw SyncAuthException("Entschlüsseln fehlgeschlagen", e)
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val TAG_BITS = 128
    }
}
