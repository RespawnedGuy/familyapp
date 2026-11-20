package familyapp.p2p.core

import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.FamilyCipher
import familyapp.p2p.crypto.b64
import familyapp.p2p.crypto.unb64
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One operation as it travels between devices: encrypted with the family key and signed by
 * the device that created it ([origin]). Any peer may forward it; nobody but the origin can
 * alter it. [seq] counts the origin's changes from 1 without gaps.
 */
@Serializable
data class Change(val origin: String, val seq: Long, val ciphertext: String, val signature: String) {
    fun signedPayload(familyId: String): ByteArray = "change\n$familyId\n$origin\n$seq\n$ciphertext".toByteArray()

    companion object {
        fun associatedData(familyId: String, origin: String, seq: Long) = "$familyId/$origin/$seq".toByteArray()
    }
}

/** Version vector: per origin device, the highest contiguous seq a peer holds. */
typealias VersionVector = Map<String, Long>

/**
 * Complete log of all changes from all devices. Keeping everything is what makes gossip work:
 * B forwards A's changes to C, so A and C never need to meet.
 * Compaction (once all known devices have a change) is out of scope.
 */
class ChangeLog {
    enum class AddResult { ADDED, DUPLICATE, GAP }

    private val byOrigin = HashMap<String, MutableList<Change>>()

    fun versionVector(): VersionVector = byOrigin.mapValues { it.value.size.toLong() }

    fun nextSeq(origin: String): Long = (byOrigin[origin]?.size ?: 0) + 1L

    fun add(change: Change): AddResult {
        val list = byOrigin.getOrPut(change.origin) { mutableListOf() }
        return when {
            change.seq <= list.size -> AddResult.DUPLICATE
            change.seq == list.size + 1L -> { list += change; AddResult.ADDED }
            else -> AddResult.GAP
        }
    }

    /** What a peer with [theirs] is missing, in seq order per origin. */
    fun missingFor(theirs: VersionVector): List<Change> =
        byOrigin.toSortedMap().flatMap { (origin, list) -> list.drop((theirs[origin] ?: 0L).toInt()) }

    fun all(): List<Change> = missingFor(emptyMap())
    fun size(): Int = byOrigin.values.sumOf { it.size }
}

class UntrustedChangeException(message: String) : SecurityException(message)

/**
 * A family device: local replica, complete change log, membership. Transport-agnostic: LAN
 * sessions and the cloud-folder mailbox both only call [missingFor] and [receive].
 */
class FamilyPeer(
    val identity: DeviceIdentity,
    familyKey: ByteArray,
    val membership: Membership,
    wallClock: () -> Long = System::currentTimeMillis,
) {
    val familyId: String = membership.familyId
    val deviceId: String get() = identity.deviceId
    val state = ReplicaState()
    val log = ChangeLog()

    /** Serializes access when several sessions run at once (server mode). */
    val lock = Mutex()

    private val familyKey = familyKey.copyOf()
    private val cipher = FamilyCipher(familyKey)
    private val clock = HybridLogicalClock(identity.deviceId, wallClock)

    init {
        require(membership.contains(deviceId)) { "Device is not a member of family $familyId" }
    }

    fun exportFamilyKey(): ByteArray = familyKey.copyOf()

    fun set(recordId: String, field: String, value: String?): Change {
        val op = Operation(recordId, field, value, clock.now())
        val seq = log.nextSeq(deviceId)
        val sealed = cipher.encrypt(json.encodeToString(op).toByteArray(), Change.associatedData(familyId, deviceId, seq))
        val unsigned = Change(deviceId, seq, b64(sealed), "")
        val change = unsigned.copy(signature = identity.sign(unsigned.signedPayload(familyId)))
        log.add(change)
        state.apply(op)
        return change
    }

    fun delete(recordId: String) = set(recordId, Operation.DELETED_FIELD, "true")

    fun versionVector(): VersionVector = log.versionVector()

    fun missingFor(theirs: VersionVector): List<Change> = log.missingFor(theirs)

    /**
     * Verifies and applies a change from any transport. Throws [UntrustedChangeException] for an
     * unknown origin, a bad signature or a ciphertext that does not decrypt: the sender is then
     * dropped. Returns true if the change was new.
     */
    fun receive(change: Change): Boolean {
        val originKey = membership.publicKeyOf(change.origin)
            ?: throw UntrustedChangeException("Unknown origin device ${change.origin}")
        if (!DeviceIdentity.verify(originKey, change.signedPayload(familyId), change.signature)) {
            throw UntrustedChangeException("Bad signature on ${change.origin}#${change.seq}")
        }
        if (change.seq != log.nextSeq(change.origin)) return false // duplicate or gap, comes again later
        val op = runCatching {
            val plain = cipher.decrypt(unb64(change.ciphertext), Change.associatedData(familyId, change.origin, change.seq))
            json.decodeFromString<Operation>(plain.decodeToString())
        }.getOrElse { throw UntrustedChangeException("Cannot decrypt ${change.origin}#${change.seq}") }
        log.add(change)
        clock.observe(op.hlc)
        state.apply(op)
        return true
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
