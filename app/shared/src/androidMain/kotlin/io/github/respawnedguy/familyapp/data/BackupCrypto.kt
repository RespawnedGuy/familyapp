package io.github.respawnedguy.familyapp.data

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The recovery code does not open this file. */
class WrongCodeException : Exception("Code passt nicht")

/**
 * Encrypts backup files with the recovery code (P0-3 of docs/qualitaet/iso-25010-bewertung.md).
 * Plain JDK (javax.crypto), so it runs on every Android version and is testable on the JVM.
 *
 * File format "FAB1": magic "FAB1" (4 bytes) | salt (16) | nonce (12) | AES-256-GCM ciphertext + tag (16).
 * Key: HKDF-SHA256 (RFC 5869) from the normalised code, the salt and the info "familyapp-backup-v1".
 * The logical file name is bound as associated data, so photos cannot be swapped with the data file.
 *
 * No slow password hash (PBKDF2/Argon2) on purpose: the code is not a password a person picks but
 * 20 random characters from a 32-letter alphabet (100 bits), out of reach for guessing; a cheap
 * key derivation keeps a backup with hundreds of photos fast. iOS must implement the same format.
 */
object BackupCrypto {
    private val MAGIC = "FAB1".encodeToByteArray()
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CODE_LENGTH = 20
    private const val GROUP = 4

    /** Crockford base32: no I, L, O, U, so the code survives handwriting. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun newCode(random: SecureRandom = SecureRandom()): String =
        (1..CODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(GROUP).joinToString("-") { it.joinToString("") }

    /** Accepts lower case, spaces, dashes and the usual misreadings (O → 0, I/L → 1). */
    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }.map {
        when (it) {
            'O' -> '0'
            'I', 'L' -> '1'
            else -> it
        }
    }.joinToString("")

    fun isEncrypted(data: ByteArray): Boolean = data.size > MAGIC.size && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(plain: ByteArray, code: String, name: String, random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(code, salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC + name.encodeToByteArray())
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(MAGIC.size + SALT_BYTES + NONCE_BYTES + body.size).put(MAGIC).put(salt).put(nonce).put(body).array()
    }

    /** Throws [WrongCodeException] if the code (or the file name) does not match or the file was changed. */
    fun decrypt(data: ByteArray, code: String, name: String): ByteArray {
        require(isEncrypted(data)) { "Keine verschlüsselte FamilyApp-Datei" }
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val nonce = data.copyOfRange(MAGIC.size + SALT_BYTES, MAGIC.size + SALT_BYTES + NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(code, salt), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC + name.encodeToByteArray())
        return try {
            cipher.doFinal(data, MAGIC.size + SALT_BYTES + NONCE_BYTES, data.size - MAGIC.size - SALT_BYTES - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw WrongCodeException().apply { initCause(e) }
        }
    }

    /**
     * Short tag of the code (8 hex digits) put into photo file names, so photos encrypted with an
     * older code are noticed and written again. Reveals nothing usable about a 100-bit code.
     */
    fun codeTag(code: String): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(normalize(code).encodeToByteArray(), "HmacSHA256")) }
            .doFinal("familyapp-photo-tag".encodeToByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** HKDF-SHA256 with one output block (32 bytes = AES-256 key). */
    private fun key(code: String, salt: ByteArray): SecretKeySpec {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
            .doFinal(normalize(code).encodeToByteArray())
        val okm = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
            .doFinal("familyapp-backup-v1".encodeToByteArray() + byteArrayOf(1))
        return SecretKeySpec(okm, "AES")
    }
}
