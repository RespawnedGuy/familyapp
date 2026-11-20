package familyapp.p2p.net

import familyapp.p2p.core.Endorsement
import familyapp.p2p.core.Membership
import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.EphemeralKey
import familyapp.p2p.crypto.FrameCipher
import familyapp.p2p.crypto.Hashing
import familyapp.p2p.crypto.Hkdf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.crypto.AEADBadTagException

enum class Role { INITIATOR, RESPONDER }

class HandshakeException(message: String) : SecurityException(message)

/**
 * Mutually authenticated, encrypted session between two family devices (SIGMA-style):
 * 1. Both send an ephemeral X25519 key; the shared secret and the transcript hash give two
 *    directional ChaCha20-Poly1305 keys (forward secrecy).
 * 2. Inside the encrypted channel each side proves its Ed25519 device key by signing the
 *    transcript and shows its membership chain. A device outside the family is rejected
 *    before any family data flows.
 *
 * Spike stand-in for Noise XX: same structure, but self-made. The apps use a vetted
 * implementation (see ADR 0003).
 */
class SecureChannel private constructor(
    private val raw: FrameChannel,
    private val sendCipher: FrameCipher,
    private val receiveCipher: FrameCipher,
    val peerDeviceId: String,
    val transcriptHash: ByteArray,
) {
    suspend fun send(plaintext: ByteArray) = raw.send(sendCipher.seal(plaintext))

    suspend fun receive(): ByteArray = try {
        receiveCipher.open(raw.receive())
    } catch (e: AEADBadTagException) {
        raw.close()
        throw HandshakeException("Frame failed authentication (tampered, replayed or reordered)")
    }

    fun close() = raw.close()

    @Serializable
    private data class AuthMessage(val publicKey: String, val signature: String, val members: List<Endorsement>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Runs the handshake. On success the peer's membership chain has been merged into [membership]. */
        suspend fun establish(
            raw: FrameChannel,
            role: Role,
            identity: DeviceIdentity,
            membership: Membership,
            protocolLabel: String = "familyapp-p2p-sync-v1",
        ): SecureChannel {
            val keys = keyExchange(raw, role, membership.familyId, protocolLabel, extraSecret = ByteArray(0))
            val channel = SecureChannel(raw, keys.send, keys.receive, peerDeviceId = "", transcriptHash = keys.transcript)

            val own = AuthMessage(
                publicKey = identity.publicKeyEncoded,
                signature = identity.sign(authPayload(role, keys.transcript)),
                members = membership.all(),
            )
            channel.send(json.encodeToString(own).toByteArray())
            // The family id is part of the key derivation: a device of another family fails right here.
            val authFrame = try {
                channel.receive()
            } catch (e: HandshakeException) {
                throw HandshakeException("Peer is not a member of family ${membership.familyId} (keys do not match)")
            }
            val peer = json.decodeFromString<AuthMessage>(authFrame.decodeToString())

            val peerRole = if (role == Role.INITIATOR) Role.RESPONDER else Role.INITIATOR
            if (!DeviceIdentity.verify(peer.publicKey, authPayload(peerRole, keys.transcript), peer.signature)) {
                raw.close()
                throw HandshakeException("Peer could not prove its device key")
            }
            val peerId = DeviceIdentity.deviceIdOf(peer.publicKey)
            val candidate = membership.copy().also { it.merge(peer.members) }
            if (candidate.publicKeyOf(peerId) != peer.publicKey) {
                raw.close()
                throw HandshakeException("Device $peerId is not a member of family ${membership.familyId}")
            }
            membership.merge(peer.members)
            return SecureChannel(raw, channel.sendCipher, channel.receiveCipher, peerId, keys.transcript)
        }

        internal class SessionKeys(val send: FrameCipher, val receive: FrameCipher, val transcript: ByteArray)

        /**
         * Ephemeral key exchange shared by sync sessions and pairing. Pairing mixes the QR secret
         * into the key derivation, so without the secret not even the first encrypted frame opens.
         */
        internal suspend fun keyExchange(
            raw: FrameChannel,
            role: Role,
            familyId: String,
            protocolLabel: String,
            extraSecret: ByteArray,
        ): SessionKeys {
            val ephemeral = EphemeralKey()
            raw.send(ephemeral.publicEncoded)
            val peerEphemeral = raw.receive()
            val (initiatorKey, responderKey) =
                if (role == Role.INITIATOR) ephemeral.publicEncoded to peerEphemeral else peerEphemeral to ephemeral.publicEncoded
            val transcript = Hashing.sha256(protocolLabel.toByteArray(), familyId.toByteArray(), initiatorKey, responderKey)
            val shared = runCatching { ephemeral.agree(peerEphemeral) }
                .getOrElse { raw.close(); throw HandshakeException("Invalid ephemeral key") }
            val material = Hkdf.derive(shared + extraSecret, transcript, "$protocolLabel/keys", 64)
            val initiatorToResponder = material.copyOfRange(0, 32)
            val responderToInitiator = material.copyOfRange(32, 64)
            return if (role == Role.INITIATOR) {
                SessionKeys(FrameCipher(initiatorToResponder), FrameCipher(responderToInitiator), transcript)
            } else {
                SessionKeys(FrameCipher(responderToInitiator), FrameCipher(initiatorToResponder), transcript)
            }
        }

        internal fun wrap(raw: FrameChannel, keys: SessionKeys, peerDeviceId: String) =
            SecureChannel(raw, keys.send, keys.receive, peerDeviceId, keys.transcript)

        private fun authPayload(role: Role, transcript: ByteArray): ByteArray = "auth-${role.name}".toByteArray() + transcript
    }
}
