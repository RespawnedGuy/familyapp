package familyapp.p2p.pairing

import familyapp.p2p.core.Endorsement
import familyapp.p2p.core.FamilyPeer
import familyapp.p2p.core.Membership
import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.Hashing
import familyapp.p2p.crypto.b64
import familyapp.p2p.crypto.secureRandomBytes
import familyapp.p2p.crypto.unb64
import familyapp.p2p.net.FrameChannel
import familyapp.p2p.net.HandshakeException
import familyapp.p2p.net.Role
import familyapp.p2p.net.SecureChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Content of the QR code the inviting device shows. Everything the new device needs to find
 * and authenticate the inviter, plus a one-time secret. Nothing here is sent to any server.
 */
@Serializable
data class Invitation(
    val familyId: String,
    val inviterDeviceId: String,
    val secret: String,
    val addresses: List<String>,
    val expiresAtMs: Long,
) {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(this).toByteArray())

    companion object {
        const val PREFIX = "familyapp-pair:"
        const val VALIDITY_MS = 10 * 60 * 1000L

        fun decode(code: String): Invitation {
            require(code.startsWith(PREFIX)) { "Not a FamilyApp invitation" }
            return json.decodeFromString(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)).decodeToString())
        }
    }
}

/** Shown on both screens; both people confirm that the codes match and that the right device is joining. */
data class PairingPrompt(val code: String, val otherDeviceName: String)

/** Asks the person in front of the device. Returns true to accept. */
fun interface Confirmation {
    suspend fun confirm(prompt: PairingPrompt): Boolean
}

class PairingException(message: String) : SecurityException(message)

@Serializable
private data class PairingHello(val publicKey: String, val name: String, val signature: String)

@Serializable
private data class Decision(val accept: Boolean)

@Serializable
private data class Welcome(val familyKey: String, val members: List<Endorsement>, val addresses: Map<String, List<String>>)

/** Result for the new device: everything it needs to become a full family peer. */
class Joined(val peer: FamilyPeer, val knownAddresses: Map<String, List<String>>)

private val json = Json { ignoreUnknownKeys = true }
private const val LABEL = "familyapp-p2p-pair-v1"

/**
 * Pairing protocol (runs over any [FrameChannel], usually TCP on the LAN or the tailnet):
 * 1. Ephemeral X25519 exchange; keys are derived from the shared secret AND the QR secret.
 *    Without the QR secret the first encrypted frame fails, so a stranger on the network gets nothing.
 * 2. Both sign the transcript with their device key. The new device checks that the inviter's
 *    key matches the device id from the QR code (no impersonated inviter).
 * 3. Both screens show the same 6-digit code and the other device's name; both people confirm.
 *    The inviter's confirmation is what stops a photographed QR code: the inviter sees which
 *    device is joining.
 * 4. Only after both confirmations does the inviter endorse the new key and send the family key.
 */
class Inviter(
    private val peer: FamilyPeer,
    private val addresses: List<String>,
    private val knownAddresses: () -> Map<String, List<String>> = { emptyMap() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The invitation currently shown as QR code. Only one at a time; a new one replaces it. */
    private var current: Invitation? = null

    fun createInvitation(): Invitation = Invitation(
        familyId = peer.familyId,
        inviterDeviceId = peer.deviceId,
        secret = b64(secureRandomBytes(32)),
        addresses = addresses,
        expiresAtMs = now() + Invitation.VALIDITY_MS,
    ).also { current = it }

    /** Handles one incoming pairing connection. Returns the new member's endorsement, or null if declined. */
    suspend fun accept(raw: FrameChannel, confirmation: Confirmation): Endorsement? {
        try {
            val invitation = current ?: throw PairingException("No open invitation (already used or never created)")
            if (now() > invitation.expiresAtMs) throw PairingException("Invitation expired")
            val keys = SecureChannel.keyExchange(raw, Role.RESPONDER, peer.familyId, LABEL, unb64(invitation.secret))
            val channel = SecureChannel.wrap(raw, keys, peerDeviceId = "")
            val newcomer = exchangeHellos(channel, Role.RESPONDER, peer.identity, peer.membership.nameOf(peer.deviceId) ?: "?", keys.transcript)
            current = null // one-time: burnt as soon as someone proved the secret

            val prompt = PairingPrompt(verificationCode(keys.transcript, newcomer.publicKey, peer.identity.publicKeyEncoded), newcomer.name)
            val mine = confirmation.confirm(prompt)
            channel.send(json.encodeToString(Decision(mine)).toByteArray())
            val theirs = json.decodeFromString<Decision>(channel.receive().decodeToString()).accept
            if (!mine || !theirs) return null

            val endorsement = Membership.endorse(peer.familyId, peer.identity, newcomer.publicKey, newcomer.name)
            peer.membership.merge(listOf(endorsement))
            val addressBook = knownAddresses() + (peer.deviceId to addresses)
            channel.send(json.encodeToString(Welcome(b64(peer.exportFamilyKey()), peer.membership.all(), addressBook)).toByteArray())
            return endorsement
        } catch (e: HandshakeException) {
            throw PairingException("Pairing failed: ${e.message}")
        } finally {
            raw.close()
        }
    }
}

object Joiner {
    suspend fun join(
        raw: FrameChannel,
        invitation: Invitation,
        identity: DeviceIdentity,
        name: String,
        confirmation: Confirmation,
        wallClock: () -> Long = System::currentTimeMillis,
    ): Joined? {
        try {
            val keys = SecureChannel.keyExchange(raw, Role.INITIATOR, invitation.familyId, LABEL, unb64(invitation.secret))
            val channel = SecureChannel.wrap(raw, keys, invitation.inviterDeviceId)
            val inviter = exchangeHellos(channel, Role.INITIATOR, identity, name, keys.transcript)
            if (DeviceIdentity.deviceIdOf(inviter.publicKey) != invitation.inviterDeviceId) {
                throw PairingException("Inviter key does not match the QR code")
            }

            val prompt = PairingPrompt(verificationCode(keys.transcript, identity.publicKeyEncoded, inviter.publicKey), inviter.name)
            val mine = confirmation.confirm(prompt)
            channel.send(json.encodeToString(Decision(mine)).toByteArray())
            val theirs = json.decodeFromString<Decision>(channel.receive().decodeToString()).accept
            if (!mine || !theirs) return null

            val welcome = json.decodeFromString<Welcome>(channel.receive().decodeToString())
            val membership = Membership(invitation.familyId).also { it.merge(welcome.members) }
            if (membership.publicKeyOf(identity.deviceId) != identity.publicKeyEncoded) {
                throw PairingException("Inviter did not endorse this device")
            }
            return Joined(FamilyPeer(identity, unb64(welcome.familyKey), membership, wallClock), welcome.addresses)
        } catch (e: HandshakeException) {
            throw PairingException("Pairing failed: ${e.message}")
        } finally {
            raw.close()
        }
    }
}

private suspend fun exchangeHellos(
    channel: SecureChannel,
    role: Role,
    identity: DeviceIdentity,
    name: String,
    transcript: ByteArray,
): PairingHello {
    val own = PairingHello(identity.publicKeyEncoded, name, identity.sign("pair-${role.name}".toByteArray() + transcript))
    channel.send(json.encodeToString(own).toByteArray())
    val other = json.decodeFromString<PairingHello>(channel.receive().decodeToString())
    val otherRole = if (role == Role.INITIATOR) Role.RESPONDER else Role.INITIATOR
    if (!DeviceIdentity.verify(other.publicKey, "pair-${otherRole.name}".toByteArray() + transcript, other.signature)) {
        throw PairingException("Other device could not prove its key")
    }
    return other
}

/** Short authentication string: 6 digits over the whole transcript and both device keys. */
fun verificationCode(transcript: ByteArray, newcomerKey: String, inviterKey: String): String {
    val digest = Hashing.sha256("sas".toByteArray(), transcript, newcomerKey.toByteArray(), inviterKey.toByteArray())
    val number = (ByteBuffer.wrap(digest).int.toLong() and 0xffffffffL) % 1_000_000
    return "%06d".format(number)
}
