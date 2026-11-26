package io.github.respawnedguy.familyapp.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Content of the QR code the inviting device shows (ADR 0003, Pairing). Everything the new device
 * needs to find the inviter, plus a one-time secret. Nothing of it goes to any server.
 */
@Serializable
data class Invitation(
    @SerialName("v") val version: Int = 1,
    /** Name shown on the new device: "Familie von Nils". */
    @SerialName("n") val inviterName: String,
    @SerialName("d") val inviterDeviceId: String,
    /** 32 random bytes, base64. Proves that the new device saw the QR code. */
    @SerialName("s") val secret: String,
    /** "host:port" entries where the inviter listens (LAN, Tailscale). */
    @SerialName("a") val addresses: List<String>,
    @SerialName("e") val expiresAtMs: Long,
) {
    fun encode(): String = PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        .encode(syncJson.encodeToString(serializer(), this).encodeToByteArray())

    fun secretBytes(): ByteArray = Base64.decode(secret)

    companion object {
        const val PREFIX = "FAMILYAPP1:"
        const val VALIDITY_MS = 10 * 60 * 1000L

        /** Reads a scanned or pasted code; null if it is not a FamilyApp invitation. */
        fun decode(code: String): Invitation? = runCatching {
            val text = code.trim()
            require(text.startsWith(PREFIX))
            val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            syncJson.decodeFromString(serializer(), base64.decode(text.removePrefix(PREFIX)).decodeToString())
        }.getOrNull()?.takeIf { it.addresses.isNotEmpty() && it.secret.isNotEmpty() }
    }
}

/** First encrypted message of both sides during pairing: who is there. */
@Serializable
data class PairHello(
    val deviceId: String,
    val deviceName: String,
    val personName: String,
    /** A device without a person (house device); the inviter gets that as suggestion. */
    val houseDevice: Boolean = false,
)

/** Decision of one side after both people compared the code. */
@Serializable
data class PairDecision(val accept: Boolean)

/** Everything the new device needs to become a full family device. */
@Serializable
data class Welcome(
    val familyId: String,
    val familyKey: String,
    /** The person this device belongs to (null for a house device). */
    val memberId: String?,
    val records: SyncRecords,
    val devices: List<KnownDevice>,
)

/** Shown on both screens; both people check that the digits match and the right device joins. */
data class PairingPrompt(val code: String, val other: PairHello)

class PairingException(message: String) : Exception(message)

/** Short authentication string: 6 digits over the whole handshake (both ephemeral keys). */
fun verificationCode(crypto: SyncCrypto, transcript: ByteArray): String {
    val digest = crypto.sha256("familyapp-sas-v1".encodeToByteArray() + transcript)
    var number = 0L
    for (i in 0 until Int.SIZE_BYTES) number = (number shl Byte.SIZE_BITS) or (digest[i].toLong() and BYTE_MASK)
    return (number % SAS_RANGE).toString().padStart(SAS_DIGITS, '0')
}

private const val BYTE_MASK = 0xffL
private const val SAS_RANGE = 1_000_000L
private const val SAS_DIGITS = 6

/**
 * Pairing protocol, run over a [SecureChannel] whose secret is the QR secret:
 * 1. Both send [PairHello]. Wrong QR secret → the first frame does not open.
 * 2. The new device checks that it reached the device named in the QR code.
 * 3. Both screens show the same 6 digits and who is on the other side; both people confirm. The
 *    inviter's confirmation stops a photographed QR code (the inviter sees who joins); comparing the
 *    digits stops someone in the middle, because the digits cover both sides' session keys.
 * 4. Only after both said yes, the inviter sends the [Welcome] with the family key.
 */
object PairingProtocol {

    /**
     * Inviter side. [decide] asks the person (with the prompt) and returns how to build the
     * [Welcome] once both said yes, or null to decline.
     */
    suspend fun invite(
        channel: SecureChannel,
        crypto: SyncCrypto,
        mine: PairHello,
        decide: suspend (PairingPrompt) -> (suspend () -> Welcome)?,
    ): PairHello? {
        val other = exchange(channel, mine)
        val accept = decide(PairingPrompt(verificationCode(crypto, channel.transcript), other))
        val theirs = decisions(channel, accept != null)
        if (accept == null || !theirs) return null
        channel.send(syncJson.encodeToString(Welcome.serializer(), accept()).encodeToByteArray())
        return other
    }

    /** New device side. Returns the [Welcome], or null if one of the two people declined. */
    suspend fun join(
        channel: SecureChannel,
        crypto: SyncCrypto,
        invitation: Invitation,
        mine: PairHello,
        confirm: suspend (PairingPrompt) -> Boolean,
    ): Welcome? {
        val other = exchange(channel, mine)
        if (other.deviceId != invitation.inviterDeviceId) {
            throw PairingException("Das ist nicht das Gerät aus dem QR-Code.")
        }
        val accept = confirm(PairingPrompt(verificationCode(crypto, channel.transcript), other))
        val theirs = decisions(channel, accept)
        if (!accept || !theirs) return null
        return syncJson.decodeFromString(Welcome.serializer(), channel.receive().decodeToString())
    }

    private suspend fun exchange(channel: SecureChannel, mine: PairHello): PairHello {
        channel.send(syncJson.encodeToString(PairHello.serializer(), mine).encodeToByteArray())
        return syncJson.decodeFromString(PairHello.serializer(), channel.receive().decodeToString())
    }

    private suspend fun decisions(channel: SecureChannel, mine: Boolean): Boolean {
        channel.send(syncJson.encodeToString(PairDecision.serializer(), PairDecision(mine)).encodeToByteArray())
        return syncJson.decodeFromString(PairDecision.serializer(), channel.receive().decodeToString()).accept
    }
}
