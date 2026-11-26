package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.Stamp
import io.github.respawnedguy.familyapp.domain.Tombstone
import kotlinx.serialization.Serializable

/** Members, items and tombstones one device sends another: only what the other side lacks. */
@Serializable
data class SyncRecords(
    val members: List<Member> = emptyList(),
    val items: List<Item> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
) {
    val size: Int get() = members.size + items.size + tombstones.size
}

/**
 * Merge rules for family data between devices (ADR 0006). Every member, item and tombstone carries
 * a [Stamp] (hybrid logical clock). Per record id the newest version wins, whether that is a record
 * or a tombstone. That is a state-based CRDT (last-writer-wins map): merging is commutative,
 * associative and idempotent, so devices that meet in any order and pass on what they got from
 * others end up with the same data (gossip, ADR 0003).
 *
 * Settings, the own person (selfId) and the device id stay local and are never sent.
 */
object SyncMerge {

    /** Newest stamp per record id, records and tombstones together. Small enough to send every time. */
    fun digest(data: FamilyData): Map<String, Stamp> {
        val out = HashMap<String, Stamp>()
        fun put(id: String, stamp: Stamp) {
            val old = out[id]
            if (old == null || stamp > old) out[id] = stamp
        }
        data.members.forEach { put(it.id, it.stamp) }
        data.items.forEach { put(it.id, it.stamp) }
        data.tombstones.forEach { put(it.id, it.stamp) }
        return out
    }

    /** Everything this device has in a newer version than [theirs] (or that the other side does not know). */
    fun newerThan(data: FamilyData, theirs: Map<String, Stamp>): SyncRecords {
        val mine = digest(data)
        fun wanted(id: String, stamp: Stamp): Boolean {
            val other = theirs[id]
            return mine[id] == stamp && (other == null || stamp > other)
        }
        return SyncRecords(
            members = data.members.filter { wanted(it.id, it.stamp) },
            items = data.items.filter { wanted(it.id, it.stamp) },
            tombstones = data.tombstones.filter { wanted(it.id, it.stamp) },
        )
    }

    /** Result of applying received records: the new state and how many records changed here. */
    data class Applied(val data: FamilyData, val changed: Int)

    /**
     * Applies received records. [photoPath] turns a photo file name into this device's path for it
     * (photos are named by a random id, so the name is the same on every device). Records are only
     * replaced by strictly newer versions, so applying the same records twice changes nothing.
     * A tombstone for this device's own person is ignored: a device must not lose its owner.
     */
    fun apply(data: FamilyData, incoming: SyncRecords, photoPath: (String) -> String): Applied {
        val current = digest(data)
        val members = data.members.associateByTo(LinkedHashMap()) { it.id }
        val items = data.items.associateByTo(LinkedHashMap()) { it.id }
        val tombstones = data.tombstones.associateByTo(LinkedHashMap()) { it.id }
        val newest = HashMap(current)
        var changed = 0

        fun newer(id: String, stamp: Stamp): Boolean {
            val known = newest[id]
            return (known == null || stamp > known).also { if (it) newest[id] = stamp }
        }

        incoming.tombstones.filter { it.id != data.selfId }.forEach { t ->
            if (newer(t.id, t.stamp)) {
                members.remove(t.id)
                items.remove(t.id)
                tombstones[t.id] = t
                changed++
            }
        }
        incoming.members.forEach { m ->
            if (newer(m.id, m.stamp)) {
                tombstones.remove(m.id)
                members[m.id] = m
                changed++
            }
        }
        incoming.items.forEach { item ->
            if (newer(item.id, item.stamp)) {
                tombstones.remove(item.id)
                val local = item.photoPath?.let { photoPath(FamilyRepository.photoName(it)) }
                items[item.id] = item.copy(photoPath = local)
                changed++
            }
        }
        if (changed == 0) return Applied(data, 0)
        return Applied(
            data.copy(
                members = members.values.toList(),
                items = items.values.toList(),
                tombstones = tombstones.values.toList(),
            ),
            changed,
        )
    }

    /** Photo file names that items here point to, for asking another device for missing files. */
    fun photoNames(data: FamilyData): Set<String> =
        data.items.mapNotNull { it.photoPath?.let(FamilyRepository::photoName) }.toSet()

    /**
     * A device joins a family (pairing). Its own entries come along: its own person becomes
     * [selfMemberId], other people it had created are matched by name with the family's people
     * (same name, ignoring case and spaces), the rest join as new people. [restamp] gives every
     * carried-over record a new stamp, so the family takes them as fresh changes of this device.
     * Settings and the device id stay as they were.
     */
    fun adopt(local: FamilyData, family: SyncRecords, selfMemberId: String?, restamp: () -> Stamp): FamilyData {
        val gone = family.tombstones.map { it.id }.toSet()
        val byName = family.members.filter { it.id !in gone }.associateBy { key(it.name) }
        val mapping = HashMap<String, String>()
        local.members.forEach { m ->
            val target = if (m.id == local.selfId) selfMemberId else byName[key(m.name)]?.id
            if (target != null) mapping[m.id] = target
        }
        fun map(id: String?): String? = id?.let { mapping[it] ?: it }
        val keptMembers = local.members.filter { it.id !in mapping && it.id != local.selfId }
            .map { it.copy(stamp = restamp()) }
        val carried = local.items.map { item ->
            item.copy(
                createdBy = map(item.createdBy) ?: item.createdBy,
                assignee = map(item.assignee),
                concerns = map(item.concerns),
                doneBy = map(item.doneBy),
                changedBy = map(item.changedBy),
                stamp = restamp(),
            )
        }
        val familyIds = (family.members.map { it.id } + family.items.map { it.id }).toSet()
        return local.copy(
            selfId = selfMemberId,
            members = family.members + keptMembers.filter { it.id !in familyIds },
            items = family.items + carried.filter { it.id !in familyIds },
            tombstones = family.tombstones + local.tombstones.filter { t -> family.tombstones.none { it.id == t.id } },
        )
    }

    private fun key(name: String) = name.trim().lowercase()
}
