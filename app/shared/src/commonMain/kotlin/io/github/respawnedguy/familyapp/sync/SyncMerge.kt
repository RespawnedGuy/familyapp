package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.usedPhotoNames
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.Item
import io.github.respawnedguy.familyapp.domain.Member
import io.github.respawnedguy.familyapp.domain.Stamp
import io.github.respawnedguy.familyapp.domain.Tombstone
import io.github.respawnedguy.familyapp.domain.contacts.Contact
import io.github.respawnedguy.familyapp.domain.contacts.ContactLink
import io.github.respawnedguy.familyapp.domain.contacts.PortfolioEntry
import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingEntry
import kotlinx.serialization.Serializable

/**
 * Records one device sends another: only what the other side lacks. Recipes and shopping entries
 * are extra fields that older app versions skip when reading (ignoreUnknownKeys), so a family can
 * update one phone after the other.
 */
@Serializable
data class SyncRecords(
    val members: List<Member> = emptyList(),
    val items: List<Item> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val shopping: List<ShoppingEntry> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val contactLinks: List<ContactLink> = emptyList(),
    val portfolio: List<PortfolioEntry> = emptyList(),
) {
    val size: Int
        get() = members.size + items.size + tombstones.size + recipes.size + shopping.size +
            contacts.size + contactLinks.size + portfolio.size
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
        data.recipes.forEach { put(it.id, it.stamp) }
        data.shopping.forEach { put(it.id, it.stamp) }
        data.contacts.forEach { put(it.id, it.stamp) }
        data.contactLinks.forEach { put(it.id, it.stamp) }
        data.portfolio.forEach { put(it.id, it.stamp) }
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
            recipes = data.recipes.filter { wanted(it.id, it.stamp) },
            shopping = data.shopping.filter { wanted(it.id, it.stamp) },
            contacts = data.contacts.filter { wanted(it.id, it.stamp) },
            contactLinks = data.contactLinks.filter { wanted(it.id, it.stamp) },
            portfolio = data.portfolio.filter { wanted(it.id, it.stamp) },
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
        // Deleting a recipe or entry is a newer version with deleted = true, so plain last-writer-wins covers it.
        val incomingRecipes = incoming.recipes.map { it.copy(photoPath = it.photoPath?.let(localPhoto(photoPath))) }
        val (recipes, recipesChanged) = newestWins(data.recipes, incomingRecipes, Recipe::id, Recipe::stamp)
        val (shopping, shoppingChanged) =
            newestWins(data.shopping, incoming.shopping, ShoppingEntry::id, ShoppingEntry::stamp)
        val (contacts, contactsChanged) = newestWins(data.contacts, incoming.contacts, Contact::id, Contact::stamp)
        val (links, linksChanged) =
            newestWins(data.contactLinks, incoming.contactLinks, ContactLink::id, ContactLink::stamp)
        val (portfolio, portfolioChanged) =
            newestWins(data.portfolio, incoming.portfolio, PortfolioEntry::id, PortfolioEntry::stamp)
        changed += recipesChanged + shoppingChanged + contactsChanged + linksChanged + portfolioChanged
        if (changed == 0) return Applied(data, 0)
        return Applied(
            data.copy(
                members = members.values.toList(),
                items = items.values.toList(),
                tombstones = tombstones.values.toList(),
                recipes = recipes,
                shopping = shopping,
                contacts = contacts,
                contactLinks = links,
                portfolio = portfolio,
            ),
            changed,
        )
    }

    /** Last writer wins per id for records that delete themselves (recipes, shopping entries, contacts, facts). */
    private fun <T> newestWins(
        local: List<T>,
        incoming: List<T>,
        id: (T) -> String,
        stamp: (T) -> Stamp,
    ): Pair<List<T>, Int> {
        val byId = local.associateByTo(LinkedHashMap(), id)
        var changed = 0
        incoming.forEach { record ->
            val known = byId[id(record)]
            if (known == null || stamp(record) > stamp(known)) {
                byId[id(record)] = record
                changed++
            }
        }
        return byId.values.toList() to changed
    }

    /** Photo file names that items and recipes here point to, for asking another device for missing files. */
    fun photoNames(data: FamilyData): Set<String> = usedPhotoNames(data)

    /** Another device's path for a photo becomes this device's path for the same file name. */
    private fun localPhoto(photoPath: (String) -> String): (String) -> String =
        { path -> photoPath(FamilyRepository.photoName(path)) }

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
        val recipes = local.recipes.map { r ->
            r.copy(createdBy = map(r.createdBy) ?: r.createdBy, changedBy = map(r.changedBy), stamp = restamp())
        }
        val shopping = local.shopping.map { e ->
            e.copy(
                createdBy = map(e.createdBy) ?: e.createdBy,
                changedBy = map(e.changedBy),
                checkedBy = map(e.checkedBy),
                stamp = restamp(),
            )
        }
        val contacts = local.contacts.map { c ->
            c.copy(createdBy = map(c.createdBy) ?: c.createdBy, changedBy = map(c.changedBy), stamp = restamp())
        }
        // Links and facts point at people: a person matched by name keeps their contacts and facts.
        val links = local.contactLinks.map { l ->
            l.copy(memberId = map(l.memberId) ?: l.memberId, changedBy = map(l.changedBy), stamp = restamp())
        }
        val portfolio = local.portfolio.map { e ->
            e.copy(memberId = map(e.memberId) ?: e.memberId, changedBy = map(e.changedBy), stamp = restamp())
        }
        val familyIds = (family.members.map { it.id } + family.items.map { it.id } +
            family.recipes.map { it.id } + family.shopping.map { it.id } + family.contacts.map { it.id } +
            family.contactLinks.map { it.id } + family.portfolio.map { it.id }).toSet()
        return local.copy(
            selfId = selfMemberId,
            members = family.members + keptMembers.filter { it.id !in familyIds },
            items = family.items + carried.filter { it.id !in familyIds },
            recipes = family.recipes + recipes.filter { it.id !in familyIds },
            shopping = family.shopping + shopping.filter { it.id !in familyIds },
            contacts = family.contacts + contacts.filter { it.id !in familyIds },
            contactLinks = family.contactLinks + links.filter { it.id !in familyIds },
            portfolio = family.portfolio + portfolio.filter { it.id !in familyIds },
            tombstones = family.tombstones + local.tombstones.filter { t -> family.tombstones.none { it.id == t.id } },
        )
    }

    private fun key(name: String) = name.trim().lowercase()
}
