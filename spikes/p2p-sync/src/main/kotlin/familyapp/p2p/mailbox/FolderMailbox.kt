package familyapp.p2p.mailbox

import familyapp.p2p.core.Change
import familyapp.p2p.core.Endorsement
import familyapp.p2p.core.FamilyPeer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * Minimal file store a shared cloud folder has to offer: list, read, create. No locking, no
 * rename, no delete, no overwrite. Google Drive (REST, drive.file), Dropbox, OneDrive and
 * WebDAV (Nextcloud, Synology) can all do this. The spike uses a local directory.
 */
interface BlobStore {
    fun list(prefix: String): List<String>
    fun read(name: String): ByteArray?
    /** Creates [name] if it does not exist yet. Never overwrites. */
    fun createIfAbsent(name: String, content: ByteArray)
}

class LocalFolderStore(private val root: Path) : BlobStore {
    override fun list(prefix: String): List<String> {
        val dir = root.resolve(prefix)
        if (!dir.exists()) return emptyList()
        return Files.walk(dir).use { paths ->
            paths.filter { it.isRegularFile() && !it.name.startsWith(".") }
                .map { it.relativeTo(root).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }
    }

    override fun read(name: String): ByteArray? = root.resolve(name).takeIf { it.exists() }?.readBytes()

    override fun createIfAbsent(name: String, content: ByteArray) {
        val target = root.resolve(name)
        if (target.exists()) return
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".tmp-", "")
        Files.write(temp, content)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    }
}

data class MailboxStats(val published: Int, val fetched: Int)

/**
 * Store-and-forward transport over a shared folder. The difference to LAN sync: devices never
 * have to be online at the same time, so two iPhones can exchange changes.
 *
 * Layout (all content encrypted and signed exactly like on the LAN, the folder is untrusted):
 *   members/<deviceId>.json                         endorsement chain, verified on read
 *   changes/<origin>/<fromSeq>-<toSeq>.<writer>.json  batch of changes
 * Every file is written once and never changed, and the writer id is part of the name, so
 * two devices never write the same file: no sync conflicts, no locking. Any device publishes
 * what the folder is still missing, including changes it got from others on the LAN.
 */
class FolderMailbox(private val store: BlobStore) {
    @Serializable
    private data class Batch(val changes: List<Change>)

    private val json = Json { ignoreUnknownKeys = true }

    fun publish(peer: FamilyPeer): Int {
        peer.membership.all().forEach { e ->
            store.createIfAbsent("members/${e.deviceId}.json", json.encodeToString(e).toByteArray())
        }
        val present = presentInFolder()
        val missing = peer.missingFor(present)
        missing.groupBy { it.origin }.forEach { (origin, changes) ->
            changes.chunked(BATCH).forEach { batch ->
                val name = "changes/$origin/%010d-%010d.%s.json".format(batch.first().seq, batch.last().seq, peer.deviceId.take(8))
                store.createIfAbsent(name, json.encodeToString(Batch(batch)).toByteArray())
            }
        }
        return missing.size
    }

    fun fetch(peer: FamilyPeer): Int {
        peer.membership.merge(store.list("members").mapNotNull { name ->
            store.read(name)?.let { runCatching { json.decodeFromString<Endorsement>(it.decodeToString()) }.getOrNull() }
        })
        val mine = peer.versionVector()
        var applied = 0
        files().filter { it.toSeq > (mine[it.origin] ?: 0L) }
            .sortedWith(compareBy({ it.origin }, { it.fromSeq }))
            .forEach { file ->
                val batch = store.read(file.name)?.let { json.decodeFromString<Batch>(it.decodeToString()) } ?: return@forEach
                batch.changes.forEach { if (peer.receive(it)) applied++ }
            }
        return applied
    }

    fun sync(peer: FamilyPeer): MailboxStats {
        val fetched = fetch(peer)
        return MailboxStats(publish(peer), fetched)
    }

    private data class FileRef(val name: String, val origin: String, val fromSeq: Long, val toSeq: Long)

    private fun files(): List<FileRef> = store.list("changes").mapNotNull { name ->
        val parts = name.split('/')
        val match = FILE_NAME.matchEntire(parts.last()) ?: return@mapNotNull null
        if (parts.size != 3) return@mapNotNull null
        FileRef(name, parts[1], match.groupValues[1].toLong(), match.groupValues[2].toLong())
    }

    /** Highest contiguous seq per origin that the folder already holds (from file names only). */
    private fun presentInFolder(): Map<String, Long> = files().groupBy { it.origin }.mapValues { (_, refs) ->
        var covered = 0L
        refs.sortedBy { it.fromSeq }.forEach { if (it.fromSeq <= covered + 1) covered = maxOf(covered, it.toSeq) }
        covered
    }

    private companion object {
        const val BATCH = 500
        // Tolerates conflict copies such as "...json (1)" by ignoring them; the original is enough.
        val FILE_NAME = Regex("""(\d{10})-(\d{10})\.[0-9a-f]{8}\.json""")
    }
}
