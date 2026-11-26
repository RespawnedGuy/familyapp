package io.github.respawnedguy.familyapp.sync

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * A folder that several devices see: Syncthing, Synology Drive, Nextcloud, Google Drive (through
 * the system file picker) or a plain local folder. Same shape as the backup folder access.
 */
interface SyncFolderFiles {
    /** Names of the files in the sub folder [sub]. */
    fun list(sub: String): List<String>
    fun read(sub: String, name: String): ByteArray?
    fun write(sub: String, name: String, data: ByteArray)
}

/** One device's complete view of the family, as stored in the shared folder. */
@Serializable
data class FolderSnapshot(
    val protocol: Int,
    val schema: Int,
    val device: KnownDevice,
    val devices: List<KnownDevice> = emptyList(),
    val records: SyncRecords,
)

/** What one folder round brought. */
data class FolderOutcome(
    val received: Int,
    val wrote: Boolean,
    val photos: Int,
    val others: List<String>,
    val problem: String? = null,
)

/**
 * Sync through a shared folder, the second transport of ADR 0003 ("Briefkasten"). It works without
 * both devices being online at the same time, which direct connections cannot do.
 *
 * Layout (all files encrypted with a key derived from the family key, the folder sees only ciphertext):
 * - `familyapp-abgleich/<device id>.fas`: everything this device knows (members, items, tombstones).
 *   Every device writes only its own file, so the folder service never has to merge files and
 *   conflict copies ("…sync-conflict…", "(1)") are simply ignored.
 * - `familyapp-abgleich-fotos/<photo name>.fap`: each photo once, never changed.
 *
 * File format: magic (4) | nonce (12) | AES-256-GCM(content), the file name is associated data,
 * so files cannot be swapped or renamed unnoticed.
 */
class FolderSync(private val crypto: SyncCrypto, private val key: ByteArray) {
    private var lastWritten: Int? = null
    private val lastRead = HashMap<String, Int>()

    /** One round: write own state if it changed, read the others' states and photos. */
    suspend fun round(files: SyncFolderFiles, me: SyncParticipant, mine: suspend () -> FolderSnapshot): FolderOutcome {
        val snapshot = mine()
        val ownName = "${snapshot.device.deviceId}$STATE_EXT"
        // Fingerprint without the time stamp, so an unchanged state is not written again.
        val fingerprint = encode(snapshot.copy(device = snapshot.device.copy(seenMs = 0))).hashCode()
        val wrote = fingerprint != lastWritten
        if (wrote) {
            files.write(STATE_DIR, ownName, seal(STATE_MAGIC, ownName, encode(snapshot).encodeToByteArray()))
            lastWritten = fingerprint
        }

        var received = 0
        var problem: String? = null
        val others = mutableListOf<String>()
        files.list(STATE_DIR).filter { STATE_NAME.matches(it) && it != ownName }.forEach { name ->
            val bytes = files.read(STATE_DIR, name) ?: return@forEach
            val other = runCatching { decode(open(STATE_MAGIC, name, bytes)) }.getOrNull()
                ?: return@forEach // other family, broken or half-synced file: try again next round
            others += other.device.name
            if (other.protocol != snapshot.protocol || other.schema != snapshot.schema) {
                problem = "${other.device.name} hat eine andere App-Version. Bitte auf beiden Geräten aktualisieren."
                return@forEach
            }
            if (lastRead[name] == bytes.contentHashCode()) return@forEach
            received += me.apply(other.records)
            me.met(Hello(other.protocol, other.schema, other.device, other.devices))
            lastRead[name] = bytes.contentHashCode()
        }
        return FolderOutcome(received, wrote, photos(files, me), others, problem)
    }

    private suspend fun photos(files: SyncFolderFiles, me: SyncParticipant): Int {
        val present = files.list(PHOTO_DIR).toSet()
        // Own photos into the folder, once each.
        me.ownPhotoNames().filter { "$it$PHOTO_EXT" !in present }.forEach { name ->
            val bytes = me.readPhoto(name) ?: return@forEach
            files.write(PHOTO_DIR, "$name$PHOTO_EXT", seal(PHOTO_MAGIC, name, bytes))
        }
        var fetched = 0
        me.missingPhotos().filter { "$it$PHOTO_EXT" in present }.forEach { name ->
            val sealed = files.read(PHOTO_DIR, "$name$PHOTO_EXT") ?: return@forEach
            val bytes = runCatching { open(PHOTO_MAGIC, name, sealed) }.getOrNull() ?: return@forEach
            me.writePhoto(name, bytes)
            fetched++
        }
        return fetched
    }

    private fun encode(snapshot: FolderSnapshot) = syncJson.encodeToString(FolderSnapshot.serializer(), snapshot)

    private fun decode(bytes: ByteArray) =
        syncJson.decodeFromString(FolderSnapshot.serializer(), bytes.decodeToString())

    private fun seal(magic: ByteArray, name: String, plain: ByteArray): ByteArray {
        val nonce = crypto.randomBytes(NONCE_BYTES)
        return magic + nonce + crypto.aesGcmSeal(key, nonce, plain, magic + name.encodeToByteArray())
    }

    private fun open(magic: ByteArray, name: String, sealed: ByteArray): ByteArray {
        if (sealed.size <= magic.size + NONCE_BYTES || !sealed.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw SyncAuthException("Keine FamilyApp-Abgleichdatei")
        }
        val nonce = sealed.copyOfRange(magic.size, magic.size + NONCE_BYTES)
        val body = sealed.copyOfRange(magic.size + NONCE_BYTES, sealed.size)
        return crypto.aesGcmOpen(key, nonce, body, magic + name.encodeToByteArray())
    }

    companion object {
        const val STATE_DIR = "familyapp-abgleich"
        const val PHOTO_DIR = "familyapp-abgleich-fotos"
        private const val STATE_EXT = ".fas"
        private const val PHOTO_EXT = ".fap"
        private const val NONCE_BYTES = 12
        private val STATE_NAME = Regex("[A-Za-z0-9-]{8,64}\\.fas")
        private val STATE_MAGIC = "FAS1".encodeToByteArray()
        private val PHOTO_MAGIC = "FAP1".encodeToByteArray()

        /** Folder key: separate from the key used for direct connections. */
        fun key(crypto: SyncCrypto, familyKey: String, familyId: String): ByteArray =
            crypto.hkdf(
                ikm = Base64.decode(familyKey),
                salt = "familyapp-folder-v1".encodeToByteArray(),
                info = familyId.encodeToByteArray(),
                length = KEY_BYTES,
            )

        private const val KEY_BYTES = 32
    }
}
