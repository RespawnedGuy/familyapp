package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.domain.Stamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/** Version of the sync messages. Devices with another version do not sync (they say so). */
const val SYNC_PROTOCOL = 1

@Serializable
sealed interface SyncMessage

/** Who is talking: device, person, data format and where it can be reached. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocol: Int,
    val schema: Int,
    val device: KnownDevice,
    /** Devices this device knows, so a device paired at B is known to C before they ever met. */
    val devices: List<KnownDevice> = emptyList(),
) : SyncMessage

@Serializable
@SerialName("digest")
data class Digest(val stamps: Map<String, Stamp>) : SyncMessage

@Serializable
@SerialName("records")
data class Records(val records: SyncRecords) : SyncMessage

@Serializable
@SerialName("want-photos")
data class WantPhotos(val names: List<String>) : SyncMessage

@Serializable
@SerialName("photo")
data class Photo(val name: String, val data: String) : SyncMessage

@Serializable
@SerialName("done")
data object Done : SyncMessage

/** The other side cannot sync with us (e.g. other app version); [reason] is shown to the person. */
@Serializable
@SerialName("refuse")
data class Refuse(val reason: String) : SyncMessage

class SyncRefusedException(message: String) : Exception(message)

/** What a sync session needs from this device. Implemented by [SyncController]. */
interface SyncParticipant {
    suspend fun hello(): Hello
    suspend fun digest(): Map<String, Stamp>
    suspend fun recordsFor(theirs: Map<String, Stamp>): SyncRecords

    /** Applies the records and returns how many changed here. */
    suspend fun apply(records: SyncRecords): Int

    /** Photo files items here point to that are not on this device yet. */
    suspend fun missingPhotos(): List<String>

    /** Photo files items here point to that are on this device (to put into a shared folder). */
    suspend fun ownPhotoNames(): List<String>
    suspend fun readPhoto(name: String): ByteArray?
    suspend fun writePhoto(name: String, bytes: ByteArray)
    suspend fun met(other: Hello)
}

/** Outcome of one sync session, from this device's view. */
data class SyncOutcome(val other: KnownDevice, val received: Int, val sent: Int, val photos: Int)

/**
 * One sync between two family devices over an authenticated [SecureChannel]. Only one side writes
 * at a time, so neither side can block the other with full buffers:
 *
 * 1. I → R: Hello, Digest
 * 2. R → I: Hello, Digest, Records for I
 * 3. I → R: Records for R, WantPhotos
 * 4. R → I: Photo…, WantPhotos
 * 5. I → R: Photo…, Done
 *
 * Every step is applied as soon as it arrives, so a broken connection loses nothing already received.
 */
class SyncSession(private val channel: FrameChannel, private val me: SyncParticipant) {

    suspend fun initiate(): SyncOutcome {
        val myHello = me.hello()
        send(myHello)
        send(Digest(me.digest()))
        val hello = receive<Hello>()
        compatible(myHello, hello)
        me.met(hello)
        val theirDigest = receive<Digest>().stamps
        val received = me.apply(receive<Records>().records)
        val forThem = me.recordsFor(theirDigest)
        send(Records(forThem))
        send(WantPhotos(me.missingPhotos()))
        val photos = receivePhotosUntil<WantPhotos>()
        sendPhotos(photos.second.names)
        send(Done)
        return SyncOutcome(hello.device, received, forThem.size, photos.first)
    }

    suspend fun respond(): SyncOutcome {
        val hello = receive<Hello>()
        val myHello = me.hello()
        compatible(myHello, hello)
        me.met(hello)
        val theirDigest = receive<Digest>().stamps
        send(myHello)
        send(Digest(me.digest()))
        val forThem = me.recordsFor(theirDigest)
        send(Records(forThem))
        val received = me.apply(receive<Records>().records)
        val wanted = receive<WantPhotos>().names
        sendPhotos(wanted)
        send(WantPhotos(me.missingPhotos()))
        val photos = receivePhotosUntil<Done>()
        return SyncOutcome(hello.device, received, forThem.size, photos.first)
    }

    private suspend fun compatible(mine: Hello, theirs: Hello) {
        val problem = when {
            theirs.protocol != mine.protocol || theirs.schema != mine.schema ->
                "${theirs.device.name} hat eine andere App-Version. Bitte auf beiden Geräten aktualisieren."
            else -> null
        } ?: return
        send(Refuse(problem))
        throw SyncRefusedException(problem)
    }

    private suspend fun sendPhotos(names: List<String>) {
        names.forEach { name ->
            val bytes = me.readPhoto(name) ?: return@forEach
            send(Photo(name, Base64.encode(bytes)))
        }
    }

    private suspend inline fun <reified T : SyncMessage> receivePhotosUntil(): Pair<Int, T> {
        var count = 0
        while (true) {
            when (val m = receiveAny()) {
                is Photo -> {
                    me.writePhoto(m.name, Base64.decode(m.data))
                    count++
                }
                is T -> return count to m
                else -> throw SyncAuthException("Unerwartete Nachricht ${m::class.simpleName}")
            }
        }
    }

    private suspend fun send(message: SyncMessage) {
        channel.send(syncJson.encodeToString(SyncMessage.serializer(), message).encodeToByteArray())
    }

    private suspend fun receiveAny(): SyncMessage {
        val m = syncJson.decodeFromString(SyncMessage.serializer(), channel.receive().decodeToString())
        if (m is Refuse) throw SyncRefusedException(m.reason)
        return m
    }

    private suspend inline fun <reified T : SyncMessage> receive(): T =
        receiveAny() as? T ?: throw SyncAuthException("Unerwartete Nachricht, erwartet ${T::class.simpleName}")
}
