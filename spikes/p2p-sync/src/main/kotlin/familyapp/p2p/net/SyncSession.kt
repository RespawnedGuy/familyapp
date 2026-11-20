package familyapp.p2p.net

import familyapp.p2p.core.Change
import familyapp.p2p.core.FamilyPeer
import familyapp.p2p.core.VersionVector
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface SyncMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(val versionVector: VersionVector) : SyncMessage

    @Serializable
    @SerialName("changes")
    data class Changes(val changes: List<Change>) : SyncMessage

    @Serializable
    @SerialName("done")
    data object Done : SyncMessage
}

data class SyncStats(val peerDeviceId: String, val sent: Int, val received: Int)

/**
 * Anti-entropy over one secure channel, symmetric for both sides:
 * 1. exchange version vectors,
 * 2. send everything the other side is missing (own and forwarded changes, per origin in seq
 *    order, in chunks), then Done,
 * 3. apply incoming chunks as they arrive.
 * Changes are applied immediately, so a dropped connection keeps all progress so far; the
 * next session simply starts from the new version vectors.
 */
object SyncSession {
    private const val CHUNK = 200
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(channel: SecureChannel, peer: FamilyPeer): SyncStats = coroutineScope {
        channel.sendMessage(SyncMessage.Hello(peer.lock.withLock { peer.versionVector() }))
        val theirs = (channel.receiveMessage() as? SyncMessage.Hello)?.versionVector
            ?: throw ProtocolException("Expected hello")
        val outgoing = peer.lock.withLock { peer.missingFor(theirs) }

        launch {
            outgoing.chunked(CHUNK).forEach { channel.sendMessage(SyncMessage.Changes(it)) }
            channel.sendMessage(SyncMessage.Done)
        }

        var received = 0
        while (true) {
            when (val message = channel.receiveMessage()) {
                is SyncMessage.Changes -> peer.lock.withLock {
                    message.changes.forEach { if (peer.receive(it)) received++ }
                }
                SyncMessage.Done -> break
                is SyncMessage.Hello -> throw ProtocolException("Unexpected hello")
            }
        }
        SyncStats(channel.peerDeviceId, outgoing.size, received)
    }

    private suspend fun SecureChannel.sendMessage(message: SyncMessage) =
        send(json.encodeToString(SyncMessage.serializer(), message).toByteArray())

    private suspend fun SecureChannel.receiveMessage(): SyncMessage =
        json.decodeFromString(SyncMessage.serializer(), receive().decodeToString())
}

class ProtocolException(message: String) : RuntimeException(message)
