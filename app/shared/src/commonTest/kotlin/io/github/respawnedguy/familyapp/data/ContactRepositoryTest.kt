package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.domain.contacts.ContactSource
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import io.github.respawnedguy.familyapp.domain.contacts.PortfolioViews
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contacts, links and portfolio facts (docs/kontakte-und-portfolio.md, stage 1). Invented people only. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactRepositoryTest {
    private var day = LocalDate(2026, 11, 27)
    private var ms = 1_000_000L

    private fun TestScope.repo(): FamilyRepository {
        var ids = 0
        return FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler), { ms++ }, { day }, { "id${ids++}" },
        ).apply { createFamily("Nils") }
    }

    private val doctor = ContactDraft("Dr. Lindner", phones = listOf("0351 123456"), organization = "Kinderarztpraxis Lindner")

    @Test
    fun addedContactIsLinkedWithSuggestedRole() = runTest {
        val r = repo()
        val mia = r.addMember("Mia", MemberKind.CHILD)
        val result = r.contactStore.add(listOf(doctor), ContactSource.PICKER, linkTo = mia.id)
        val contact = result.added.single()
        assertEquals(ContactSource.PICKER, contact.source)
        assertEquals(r.current.selfId, contact.createdBy)
        val linked = ContactViews.contactsFor(r.current, mia.id).single()
        assertEquals("Kinderarztpraxis", linked.link.role)
        // One undo takes back contact and link.
        r.undo(result.undo)
        assertTrue(ContactViews.contacts(r.current).isEmpty())
        assertTrue(ContactViews.contactsFor(r.current, mia.id).isEmpty())
        assertTrue(r.current.contacts.single().deleted, "stays as a deletion marker for sync")
        assertTrue(r.current.contacts.single().phones.isEmpty())
    }

    @Test
    fun sameNumberMergesInsteadOfDuplicating() = runTest {
        val r = repo()
        val first = r.contactStore.add(listOf(doctor), ContactSource.PICKER).added.single()
        val again = r.contactStore.add(
            listOf(ContactDraft("Praxis", phones = listOf("+49 351 123456", "0351 999"), emails = listOf("p@example.org"))),
            ContactSource.VCARD,
        )
        assertTrue(again.added.isEmpty())
        val merged = again.merged.single()
        assertEquals(first.id, merged.id)
        assertEquals("Dr. Lindner", merged.name, "what the family wrote stays")
        assertEquals(listOf("0351 123456", "0351 999"), merged.phones)
        assertEquals(listOf("p@example.org"), merged.emails)
        r.undo(again.undo)
        assertEquals(listOf("0351 123456"), r.contactStore.contact(first.id)!!.phones)
    }

    @Test
    fun twoCardsOfTheSamePersonInOneFileBecomeOne() = runTest {
        val r = repo()
        val result = r.contactStore.add(
            listOf(ContactDraft("Oma", phones = listOf("0351 1")), ContactDraft("Oma Muster", phones = listOf("+49 351 1"))),
            ContactSource.VCARD,
        )
        assertEquals(1, ContactViews.contacts(r.current).size)
        assertEquals(1, result.all.size)
    }

    @Test
    fun unusableDraftsAreIgnored() = runTest {
        val r = repo()
        val result = r.contactStore.add(listOf(ContactDraft("  ")), ContactSource.MANUAL)
        assertTrue(result.all.isEmpty())
        assertTrue(r.current.contacts.isEmpty())
    }

    @Test
    fun linkIsNmWithRolePerLink() = runTest {
        val r = repo()
        val mia = r.addMember("Mia", MemberKind.CHILD)
        val ben = r.addMember("Ben", MemberKind.CHILD)
        val c = r.contactStore.add(listOf(doctor), ContactSource.MANUAL).added.single()
        r.contactStore.link(c.id, mia.id, "Kinderarztpraxis")
        r.contactStore.link(c.id, ben.id, "")
        val roleChange = r.contactStore.link(c.id, ben.id, "Notfall")
        assertEquals(2, ContactViews.linksOf(r.current, c.id).size)
        assertEquals("Notfall", ContactViews.contactsFor(r.current, ben.id).single().link.role)
        r.undo(roleChange)
        assertEquals("", ContactViews.contactsFor(r.current, ben.id).single().link.role)
        // Same role again changes nothing; unknown people and contacts are ignored.
        assertTrue(r.contactStore.link(c.id, mia.id, "Kinderarztpraxis").people.links.isEmpty())
        assertTrue(r.contactStore.link(c.id, "nobody", "x").people.links.isEmpty())
        assertTrue(r.contactStore.link("none", mia.id, "x").people.links.isEmpty())
        val link = ContactViews.contactsFor(r.current, mia.id).single().link
        val unlink = r.contactStore.unlink(link.id)
        assertTrue(ContactViews.contactsFor(r.current, mia.id).isEmpty())
        assertTrue(r.contactStore.unlink(link.id).people.links.isEmpty(), "twice does nothing")
        r.undo(unlink)
        assertEquals(1, ContactViews.contactsFor(r.current, mia.id).size)
    }

    @Test
    fun generalContactsAreTheUnlinkedOnes() = runTest {
        val r = repo()
        val mia = r.addMember("Mia", MemberKind.CHILD)
        val c = r.contactStore.add(listOf(doctor), ContactSource.MANUAL, mia.id).added.single()
        val plumber = r.contactStore.add(listOf(ContactDraft("Klempner Weiß", phones = listOf("0351 5"))), ContactSource.MANUAL).added.single()
        assertEquals(listOf(plumber.id), ContactViews.general(r.current).map { it.id })
        assertEquals(listOf(c.id), ContactViews.searchContacts(r.current, "kinderarzt").map { it.id }, "role is searchable")
        assertEquals(listOf(plumber.id), ContactViews.searchContacts(r.current, "03515").map { it.id }, "number without spaces")
        assertTrue(ContactViews.searchContacts(r.current, " ").isEmpty())
    }

    @Test
    fun updateAndDeleteKeepWhoChangedIt() = runTest {
        val r = repo()
        val c = r.contactStore.add(listOf(doctor), ContactSource.MANUAL, null).added.single()
        val mia = r.addMember("Mia", MemberKind.CHILD)
        r.contactStore.link(c.id, mia.id, "Kinderarztpraxis")
        assertNull(r.contactStore.update(c.id, ContactDraft(" ")), "no name, no change")
        assertNull(r.contactStore.update("none", doctor))
        val undo = r.contactStore.update(c.id, doctor.copy(phones = listOf("0351 7"), address = "Hauptstr. 1, 01099 Dresden"))!!
        assertEquals(listOf("0351 7"), r.contactStore.contact(c.id)!!.phones)
        r.undo(undo)
        assertEquals(listOf("0351 123456"), r.contactStore.contact(c.id)!!.phones)
        val deleted = r.contactStore.delete(c.id)
        assertNull(r.contactStore.contact(c.id))
        assertTrue(ContactViews.contactsFor(r.current, mia.id).isEmpty(), "links go with the contact")
        r.undo(deleted)
        assertNotNull(r.contactStore.contact(c.id))
        assertEquals(1, ContactViews.contactsFor(r.current, mia.id).size)
        assertTrue(r.contactStore.delete("none").people.contacts.isEmpty())
    }

    @Test
    fun portfolioFactsWithSizesLinksAndDeletion() = runTest {
        val r = repo()
        val mia = r.addMember("Mia", MemberKind.CHILD)
        assertNull(r.portfolioStore.save(mia.id, null, EntryKind.TEXT, "Allergien", " "), "new fact needs a value")
        assertNull(r.portfolioStore.save(mia.id, null, EntryKind.TEXT, " ", "Nüsse"), "and a label")
        assertNull(r.portfolioStore.save("nobody", null, EntryKind.TEXT, "Allergien", "Nüsse"))
        r.portfolioStore.save(mia.id, null, EntryKind.SIZE, "Schuhgröße", "31")!!
        val shoe = r.current.portfolio.single()
        assertEquals(day, shoe.measuredOn)
        assertFalse(PortfolioViews.isStale(shoe, mia, day))
        day = day.plus(PortfolioViews.CHILD_SIZE_STALE_DAYS + 1, DateTimeUnit.DAY)
        assertTrue(PortfolioViews.isStale(shoe, mia, day))
        r.portfolioStore.confirm(shoe.id)
        assertFalse(PortfolioViews.isStale(r.current.portfolio.single(), mia, day), "Stimmt noch")
        // Same value again keeps the date, a new value is measured today.
        val measured = r.current.portfolio.single().measuredOn
        day = day.plus(3, DateTimeUnit.DAY)
        r.portfolioStore.save(mia.id, shoe.id, EntryKind.SIZE, "Schuhgröße", "31")
        assertEquals(measured, r.current.portfolio.single().measuredOn)
        r.portfolioStore.save(mia.id, shoe.id, EntryKind.SIZE, "Schuhgröße", "32")
        assertEquals(day, r.current.portfolio.single().measuredOn)
        // Links: bare host gets https, other schemes are refused (TE-8).
        r.portfolioStore.save(mia.id, null, EntryKind.LINK, "Elternportal", "portal.example.org")
        assertEquals("https://portal.example.org", r.current.portfolio.last().value)
        assertNull(r.portfolioStore.save(mia.id, null, EntryKind.LINK, "Böse", "javascript:alert(1)"))
        val link = r.current.portfolio.last()
        assertNull(r.portfolioStore.save(mia.id, link.id, EntryKind.LINK, "Elternportal", "intent://x"), "invalid does not delete")
        assertEquals("https://portal.example.org", r.current.portfolio.first { it.id == link.id }.value)
        // Empty value deletes; undo brings it back.
        val del = r.portfolioStore.save(mia.id, link.id, EntryKind.LINK, "Elternportal", "")!!
        assertTrue(r.current.portfolio.first { it.id == link.id }.deleted)
        r.undo(del)
        assertFalse(r.current.portfolio.first { it.id == link.id }.deleted)
        assertNull(r.portfolioStore.delete("none"))
        assertNull(r.portfolioStore.confirm("none"))
        // Order: templates first, own labels after.
        r.portfolioStore.save(mia.id, null, EntryKind.TEXT, "Allergien", "Nüsse")
        assertEquals(listOf("Schuhgröße", "Allergien", "Elternportal"), PortfolioViews.entries(r.current, mia).map { it.label })
        assertEquals(
            listOf("Kleidergröße", "Schule / Klasse", "Impfpass liegt"),
            PortfolioViews.openTemplates(r.current, mia).map { it.label },
        )
    }

    @Test
    fun adultSizesNeverGetStale() = runTest {
        val r = repo()
        val me = r.current.self!!
        r.portfolioStore.save(me.id, null, EntryKind.SIZE, "Kleidergröße", "L")
        day = day.plus(400, DateTimeUnit.DAY)
        assertFalse(PortfolioViews.isStale(r.current.portfolio.single(), me, day))
        assertEquals(4, PortfolioViews.templates(MemberKind.ADULT).size)
    }

    @Test
    fun portfolioOpenShowsWhatThePersonTakesCareOfOrIsAbout() = runTest {
        val r = repo()
        val lisa = r.addMember("Lisa", MemberKind.ADULT)
        val mia = r.addMember("Mia", MemberKind.CHILD)
        val forLisa = r.capture("Elternabend", Source.TEXT)
        r.setAssignee(forLisa.id, lisa.id)
        r.setDue(forLisa.id, null)
        val aboutMia = r.capture("Turnschuhe kaufen", Source.TEXT)
        r.setConcerns(aboutMia.id, mia.id)
        r.setAssignee(aboutMia.id, lisa.id)
        r.setDue(aboutMia.id, day)
        val waiting = r.capture("Zettel von der Kita", Source.TEXT)
        r.setConcerns(waiting.id, mia.id)
        assertEquals(listOf(aboutMia.id, forLisa.id), PortfolioViews.open(r.current, lisa.id).map { it.id }, "dated first")
        assertEquals(listOf(aboutMia.id), PortfolioViews.open(r.current, mia.id).map { it.id })
        assertEquals(listOf(waiting.id), PortfolioViews.waiting(r.current, mia.id).map { it.id })
        val kita = ContactDraft("Frau Sommer", phones = listOf("0351 2"), organization = "Kita")
        val contact = r.contactStore.add(listOf(kita), ContactSource.MANUAL).added.single()
        assertEquals(listOf(waiting.id), ContactViews.mentioningItems(r.current, contact).map { it.id }, "organisation named")
        r.complete(waiting.id)
        assertTrue(ContactViews.mentioningItems(r.current, contact).isEmpty(), "done items are not listed")
    }

    @Test
    fun takeOverAllMovesOnlyOpenTasksOfThatPerson() = runTest {
        val r = repo()
        val lisa = r.addMember("Lisa", MemberKind.ADULT)
        val a = r.capture("Elternabend", Source.TEXT).also { r.setAssignee(it.id, lisa.id); r.setDue(it.id, null) }
        val b = r.capture("Arzttermin", Source.TEXT).also { r.setAssignee(it.id, lisa.id); r.setDue(it.id, null) }
        r.complete(b.id)
        val c = r.capture("Noch im Eingang", Source.TEXT)
        val (count, undo) = r.takeOverAll(lisa.id)
        assertEquals(1, count)
        assertEquals(r.current.selfId, r.current.items.first { it.id == a.id }.assignee)
        assertEquals(lisa.id, r.current.items.first { it.id == b.id }.assignee, "done stays done by Lisa")
        assertEquals(ItemStatus.INBOX, r.current.items.first { it.id == c.id }.status)
        r.undo(undo)
        assertEquals(lisa.id, r.current.items.first { it.id == a.id }.assignee)
        assertEquals(0, r.takeOverAll(lisa.id).let { r.undo(it.second); r.takeOverAll(r.current.selfId!!).first })
        assertEquals(0, r.takeOverAll("nobody").first)
    }

    @Test
    fun removingAPersonRemovesTheirLinksAndFactsButKeepsContacts() = runTest {
        val r = repo()
        val mia = r.addMember("Mia", MemberKind.CHILD)
        val c = r.contactStore.add(listOf(doctor), ContactSource.MANUAL, mia.id).added.single()
        r.portfolioStore.save(mia.id, null, EntryKind.SIZE, "Schuhgröße", "31")
        val undo = r.removeMember(mia.id)!!
        assertNotNull(r.contactStore.contact(c.id))
        assertTrue(r.current.contactLinks.all { it.deleted })
        assertTrue(r.current.portfolio.all { it.deleted && it.value.isEmpty() })
        r.undo(undo)
        val back = r.current.member(mia.id)!!
        assertEquals(1, ContactViews.contactsFor(r.current, mia.id).size)
        assertEquals("31", PortfolioViews.entries(r.current, back).single().value)
    }

    @Test
    fun contactsAndFactsSurviveSavingAndLoading() = runTest {
        val store = InMemoryTextStore()
        var ids = 0
        val r = FamilyRepository(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { ms++ }, { day }, { "id${ids++}" })
        r.createFamily("Nils")
        val mia = r.addMember("Mia", MemberKind.CHILD)
        r.contactStore.add(listOf(doctor), ContactSource.VCARD, mia.id)
        r.portfolioStore.save(mia.id, null, EntryKind.PLACE, "Impfpass liegt", "Ordner Gesundheit")
        testScheduler.advanceUntilIdle()
        val loaded = FamilyJson.decode(store.read()!!)
        assertEquals(r.current.contacts, loaded.contacts)
        assertEquals(r.current.contactLinks, loaded.contactLinks)
        assertEquals(r.current.portfolio, loaded.portfolio)
        // A backup is the same JSON.
        assertEquals(r.current.contacts, FamilyJson.decode(r.exportBackup()).contacts)
    }
}
