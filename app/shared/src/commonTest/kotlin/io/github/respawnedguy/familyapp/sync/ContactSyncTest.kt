package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactSource
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contacts, links and portfolio facts between phones (docs/kontakte-und-portfolio.md, "Sync"):
 * merged per record, so a link on one phone and a new number on the other both survive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactSyncTest {
    private val day = LocalDate(2026, 11, 27)

    private fun TestScope.device(name: String): Pair<FamilyRepository, () -> Unit> {
        var ms = 1_000_000L
        var ids = 0
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler), { ms }, { day }, { "$name-${ids++}" },
        )
        return repo to { ms += 1_000 }
    }

    private fun path(name: String) = "/local/$name"

    private fun meet(a: FamilyRepository, b: FamilyRepository) {
        val forB = SyncMerge.newerThan(a.current, SyncMerge.digest(b.current))
        val forA = SyncMerge.newerThan(b.current, SyncMerge.digest(a.current))
        b.applySync(forB, ::path)
        a.applySync(forA, ::path)
    }

    private fun TestScope.pair(): Triple<FamilyRepository, FamilyRepository, Pair<() -> Unit, () -> Unit>> {
        val (a, tickA) = device("a")
        val (b, tickB) = device("b")
        a.createFamily("Nils")
        a.addMember("Mia", MemberKind.CHILD)
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), null, ::path)
        return Triple(a, b, tickA to tickB)
    }

    private val doctor = ContactDraft("Dr. Lindner", phones = listOf("0351 123456"), organization = "Kinderarztpraxis Lindner")

    @Test
    fun contactLinkAndFactReachTheOtherPhone() = runTest {
        val (a, b, _) = pair()
        val mia = a.current.members.first { it.name == "Mia" }
        val contact = a.contactStore.add(listOf(doctor), ContactSource.PICKER, mia.id).added.single()
        a.portfolioStore.save(mia.id, null, EntryKind.SIZE, "Schuhgröße", "31")
        meet(a, b)
        assertEquals(a.current.contacts, b.current.contacts)
        assertEquals("Kinderarztpraxis", ContactViews.contactsFor(b.current, mia.id).single().link.role)
        assertEquals("31", b.current.portfolio.single().value)
        assertEquals(contact.id, b.current.contacts.single().id)
    }

    @Test
    fun linkOnOnePhoneAndNewNumberOnTheOtherBothSurvive() = runTest {
        val (a, b, ticks) = pair()
        val mia = a.current.members.first { it.name == "Mia" }
        val contact = a.contactStore.add(listOf(doctor), ContactSource.MANUAL).added.single()
        meet(a, b)
        ticks.first()
        a.contactStore.link(contact.id, mia.id, "Kinderarztpraxis")
        ticks.second(); ticks.second()
        b.contactStore.update(contact.id, doctor.copy(phones = doctor.phones + "0170 5550101"))
        meet(a, b)
        for (phone in listOf(a, b)) {
            assertEquals(2, phone.contactStore.contact(contact.id)?.phones?.size)
            assertEquals(1, ContactViews.linksOf(phone.current, contact.id).size)
        }
        assertEquals(a.current.contactLinks, b.current.contactLinks)
    }

    @Test
    fun deletingAContactReachesTheOtherPhoneWithoutItsData() = runTest {
        val (a, b, ticks) = pair()
        val mia = a.current.members.first { it.name == "Mia" }
        val contact = a.contactStore.add(listOf(doctor), ContactSource.MANUAL, mia.id).added.single()
        meet(a, b)
        ticks.second()
        b.contactStore.delete(contact.id)
        meet(a, b)
        val gone = a.current.contacts.single()
        assertTrue(gone.deleted && gone.phones.isEmpty() && gone.organization == null)
        assertTrue(ContactViews.contactsFor(a.current, mia.id).isEmpty())
    }

    @Test
    fun joiningCarriesOwnContactsOverToTheFamilyIds() = runTest {
        val (a, _) = device("a")
        val (b, _) = device("b")
        a.createFamily("Nils")
        b.createFamily("Lisa")
        val ownMia = b.addMember("Mia", MemberKind.CHILD)
        b.contactStore.add(listOf(doctor), ContactSource.MANUAL, ownMia.id)
        b.portfolioStore.save(ownMia.id, null, EntryKind.TEXT, "Allergien", "keine")
        val lisa = a.addMember("Lisa", MemberKind.ADULT)
        val familyMia = a.addMember("Mia", MemberKind.CHILD)
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), lisa.id, ::path)
        assertEquals(familyMia.id, b.current.contactLinks.single().memberId)
        assertEquals(familyMia.id, b.current.portfolio.single().memberId)
        assertEquals(lisa.id, b.current.contacts.single().createdBy)
        meet(a, b)
        assertEquals("Dr. Lindner", ContactViews.contactsFor(a.current, familyMia.id).single().contact.name)
    }

    @Test
    fun olderAppVersionsSkipTheNewRecords() = runTest {
        val (a, _) = pair()
        a.contactStore.add(listOf(doctor), ContactSource.MANUAL)
        val wire = syncJson.encodeToString(SyncRecords.serializer(), SyncMerge.newerThan(a.current, emptyMap()))
        // An old app reads members and items and ignores the rest (ignoreUnknownKeys).
        val old = Json.parseToJsonElement(wire).jsonObject
        assertEquals(a.current.members.size, old.getValue("members").jsonArray.size)
        val fromOld = syncJson.decodeFromString(SyncRecords.serializer(), """{"members":[],"items":[],"tombstones":[]}""")
        assertTrue(fromOld.contacts.isEmpty() && fromOld.contactLinks.isEmpty() && fromOld.portfolio.isEmpty())
    }
}
