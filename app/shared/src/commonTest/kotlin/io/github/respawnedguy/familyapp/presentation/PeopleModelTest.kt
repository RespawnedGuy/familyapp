package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactSource
import io.github.respawnedguy.familyapp.domain.contacts.ContactViews
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Platform that records what would open another app; [picked] is what the system picker returns. */
private class ContactsPlatform(
    var picked: ContactDraft? = null,
    var appsInstalled: Boolean = true,
) : PlatformServices {
    val opened = mutableListOf<String>()
    override val canPickContacts = true
    override fun pickContact(onResult: (ContactDraft?) -> Unit) = onResult(picked)
    private fun open(what: String): Boolean { opened += what; return appsInstalled }
    override fun dial(number: String) = open("tel:$number")
    override fun sendSms(number: String) = open("sms:$number")
    override fun sendMail(address: String) = open("mail:$address")
    override fun showAddress(address: String) = open("geo:$address")
    override fun openWebLink(url: String) = open("web:$url")
    override fun addToPhoneBook(draft: ContactDraft) = open("book:${draft.name}")

    override val speech: SpeechInput? = null
    override fun elapsedMs() = 0L
    override fun reducedMotion() = false
    override fun takePhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun writeBackup(folder: String, backup: BackupContent): String? = null
    override fun readBackup(folder: String, code: String?): RestoreRead = RestoreRead.Failed("leer")
    override fun newRecoveryCode() = "ABCD-EFGH-JKMN-PQRS-TVWX"
    override fun requestNotificationPermission() = Unit
    override fun describeFolder(folder: String) = folder
}

/** Contacts and portfolio screens without UI (docs/kontakte-und-portfolio.md, stage 1). Invented people only. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleModelTest {
    private val day = LocalDate(2026, 11, 27)
    private var ids = 0

    private fun TestScope.model(platform: ContactsPlatform = ContactsPlatform()): AppModel {
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            { testScheduler.currentTime }, { day }, { "id${ids++}" },
        )
        return AppModel(repo, platform, backgroundScope, { day }, UnconfinedTestDispatcher(testScheduler))
            .apply { createFamily("Nils") }
    }

    private val doctor = ContactDraft("Dr. Lindner", phones = listOf("0351 123456"), organization = "Kinderarztpraxis Lindner")

    private val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Dr. Lindner\r\nTEL;TYPE=WORK:0351 123456\r\n" +
        "ORG:Kinderarztpraxis Lindner\r\nEND:VCARD\r\n"

    private fun message(m: AppModel) = m.feedback.value?.message

    @Test
    fun backReturnsToThePortfolioTheContactWasOpenedFrom() = runTest {
        val m = model()
        val mia = m.repo.addMember("Mia", MemberKind.CHILD)
        m.kitchen.openLibrary()
        m.people.openPortfolio(mia.id)
        assertNull(m.kitchen.route.value, "the kitchen closes")
        val contact = m.repo.contactStore.add(listOf(doctor), ContactSource.MANUAL).added.single()
        m.people.openContact(contact.id)
        m.people.openContact(contact.id)
        assertEquals(PeopleRoute.Contact(contact.id), m.people.route.value)
        assertTrue(m.handleBack())
        assertEquals(PeopleRoute.Portfolio(mia.id), m.people.route.value, "the same screen twice is one step")
        assertTrue(m.handleBack())
        assertNull(m.people.route.value)
        assertFalse(m.people.back())
        m.people.openPortfolio("nobody")
        m.people.openContact("nobody")
        assertNull(m.people.route.value)
    }

    @Test
    fun tabsAndSettingsCloseContacts() = runTest {
        val m = model()
        m.people.openContacts()
        m.openSettings()
        assertNull(m.people.route.value)
        m.people.openContacts()
        assertFalse(m.settingsOpen.value)
        m.select(Tab.TODAY)
        assertNull(m.people.route.value)
    }

    @Test
    fun sharedVCardIsStoredAtOnceWithUndo() = runTest {
        val m = model()
        m.captureSharedContact(vcard)
        assertEquals(Tab.FAMILY, m.tab.value)
        val contact = ContactViews.contacts(m.repo.current).single()
        assertEquals("Dr. Lindner gespeichert.", message(m))
        assertEquals(PeopleRoute.Contact(contact.id), m.people.route.value)
        m.undo(m.feedback.value!!)
        assertTrue(ContactViews.contacts(m.repo.current).isEmpty())
    }

    @Test
    fun sharedVCardMessagesSayWhatHappened() = runTest {
        val m = model()
        m.captureSharedContact(null)
        assertTrue(message(m).orEmpty().startsWith("Kontakt nicht übernommen"), "refused or unreadable (KT-1)")
        m.people.contacts.importVCard("BEGIN:VCARD\r\nEND:VCARD\r\n")
        assertEquals("In der geteilten Datei war kein Kontakt.", message(m))
        m.people.contacts.importVCard("x".repeat(1_000_001))
        assertTrue(message(m).orEmpty().contains("1 MB"))
        m.people.contacts.importVCard(vcard)
        // Same number, new mail address: merged instead of a second Dr. Lindner.
        m.people.contacts.importVCard(vcard.replace("END:VCARD", "EMAIL:praxis@example.org\r\nEND:VCARD"))
        assertEquals("Mit vorhandenem Kontakt Dr. Lindner zusammengeführt.", message(m))
        assertEquals(listOf("praxis@example.org"), ContactViews.contacts(m.repo.current).single().emails)
        val many = (1..21).joinToString("") { "BEGIN:VCARD\r\nFN:Person $it\r\nTEL:0351 $it\r\nEND:VCARD\r\n" }
        m.people.contacts.importVCard(many)
        assertTrue(message(m).orEmpty().startsWith("20 Kontakte gespeichert. 1 nicht übernommen"))
        assertEquals(PeopleRoute.Contacts, m.people.route.value)
    }

    @Test
    fun beforeAFamilyExistsASharedContactIsIgnored() = runTest {
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            { testScheduler.currentTime }, { day }, { "id${ids++}" },
        )
        val m = AppModel(repo, ContactsPlatform(), backgroundScope, { day }, UnconfinedTestDispatcher(testScheduler))
        m.captureSharedContact(vcard)
        assertTrue(repo.current.contacts.isEmpty())
    }

    @Test
    fun pickerInAPortfolioLinksAndStaysThere() = runTest {
        val platform = ContactsPlatform(picked = doctor)
        val m = model(platform)
        assertTrue(m.people.canPickContacts)
        val mia = m.repo.addMember("Mia", MemberKind.CHILD)
        m.people.openPortfolio(mia.id)
        m.people.contacts.pickFromPhoneBook(mia.id)
        assertEquals(PeopleRoute.Portfolio(mia.id), m.people.route.value)
        assertEquals("Kinderarztpraxis", ContactViews.contactsFor(m.repo.current, mia.id).single().link.role)
        platform.picked = null
        m.people.contacts.pickFromPhoneBook()
        assertEquals(1, m.repo.current.contacts.size, "cancelled picker adds nothing")
        platform.picked = ContactDraft(" ")
        m.people.contacts.pickFromPhoneBook()
        assertEquals("Kein Name oder keine Nummer gefunden, nichts gespeichert.", message(m))
    }

    @Test
    fun formCreatesEditsAndDeletes() = runTest {
        val m = model()
        val mia = m.repo.addMember("Mia", MemberKind.CHILD)
        m.people.openContacts()
        m.people.newContact()
        m.people.contacts.saveContact(null, ContactDraft(""))
        assertEquals("Bitte einen Namen oder eine Nummer eintragen.", message(m))
        m.people.contacts.saveContact(null, doctor)
        val contact = ContactViews.contacts(m.repo.current).single()
        assertEquals(PeopleRoute.Contact(contact.id), m.people.route.value, "the form is replaced by the contact")
        m.people.editContact(contact.id)
        m.people.contacts.saveContact(contact.id, ContactDraft(""))
        assertEquals("Bitte einen Namen oder eine Nummer eintragen.", message(m))
        m.people.contacts.saveContact(contact.id, doctor.copy(name = "Dr. Anna Lindner"))
        assertEquals("Gespeichert.", message(m))
        assertEquals(PeopleRoute.Contact(contact.id), m.people.route.value)
        m.people.contacts.deleteContact(contact.id)
        assertEquals("Dr. Anna Lindner gelöscht.", message(m))
        assertEquals(PeopleRoute.Contacts, m.people.route.value)
        m.people.contacts.deleteContact(contact.id)
        // From a portfolio the new contact is linked and the form goes back to the portfolio.
        m.people.openPortfolio(mia.id)
        m.people.newContact(mia.id)
        m.people.contacts.saveContact(null, ContactDraft("Oma", phones = listOf("0351 9")), mia.id)
        assertEquals(PeopleRoute.Portfolio(mia.id), m.people.route.value)
        assertEquals("Oma", ContactViews.contactsFor(m.repo.current, mia.id).single().contact.name)
    }

    @Test
    fun linksAndRoles() = runTest {
        val m = model()
        val mia = m.repo.addMember("Mia", MemberKind.CHILD)
        val contact = m.repo.contactStore.add(listOf(doctor), ContactSource.MANUAL).added.single()
        assertEquals("Kinderarztpraxis", m.people.contacts.suggestedRole(contact))
        m.people.contacts.link(contact.id, mia.id, "Kinderarztpraxis")
        assertEquals("Verknüpft mit Mia.", message(m))
        val link = ContactViews.linksOf(m.repo.current, contact.id).single()
        m.people.contacts.unlink(link.id)
        assertEquals("Nicht mehr mit Mia verknüpft.", message(m))
        m.people.contacts.link(contact.id, "nobody", "x")
        m.people.contacts.unlink("nothing")
        assertEquals("Nicht mehr mit Mia verknüpft.", message(m), "unknown ids change nothing")
    }

    @Test
    fun portfolioFactsAndTakingOver() = runTest {
        val m = model()
        val lisa = m.repo.addMember("Lisa", MemberKind.ADULT)
        m.people.portfolio.saveEntry(lisa.id, null, EntryKind.LINK, "Portal", "javascript:alert(1)")
        assertEquals("Als Link gehen nur Webadressen (https://…).", message(m))
        m.people.portfolio.saveEntry(lisa.id, null, EntryKind.TEXT, "", "x")
        assertEquals("Bitte Bezeichnung und Wert eintragen.", message(m))
        m.people.portfolio.saveEntry(lisa.id, null, EntryKind.SIZE, "Schuhgröße", "39")
        assertEquals("Gespeichert.", message(m))
        val entry = m.repo.current.portfolio.single()
        m.people.portfolio.confirmEntry(entry.id)
        assertEquals("Als aktuell vermerkt.", message(m))
        m.people.portfolio.deleteEntry(entry.id)
        assertEquals("Gelöscht.", message(m))

        m.people.portfolio.takeOverAll(lisa.id)
        assertEquals("Lisa hat gerade keine offenen Aufgaben.", message(m))
        val item = m.repo.capture("Elternabend", Source.TEXT)
        m.repo.setAssignee(item.id, lisa.id)
        m.repo.setDue(item.id, null)
        m.people.portfolio.takeOverAll(lisa.id)
        assertEquals("1 Aufgaben von Lisa übernommen.", message(m))
        assertEquals(m.repo.current.selfId, m.repo.current.items.single().assignee)
    }

    @Test
    fun otherAppsGetOnlySafeValues() = runTest {
        val platform = ContactsPlatform()
        val m = model(platform)
        m.people.apps.call("+49 (351) 123-456;999")
        m.people.apps.sms("0170 555 0101")
        m.people.apps.mail("praxis@example.org")
        m.people.apps.showAddress("Hauptstraße 1, 01067 Dresden")
        m.people.apps.openLink("javascript:alert(1)")
        m.people.apps.openLink("intent://x")
        m.people.apps.openLink("https://example.org/portal")
        m.people.apps.addToPhoneBook(m.repo.contactStore.add(listOf(doctor), ContactSource.MANUAL).added.single())
        assertEquals(
            listOf(
                "tel:+49351123456999", "sms:01705550101", "mail:praxis@example.org", "geo:Hauptstraße 1, 01067 Dresden",
                "web:https://example.org/portal", "book:Dr. Lindner",
            ),
            platform.opened,
            "only digits reach the dialler, only http(s) the browser (KT-2, TE-8)",
        )
        platform.appsInstalled = false
        m.people.apps.call("0351 1")
        assertEquals("Keine Telefon-App gefunden.", message(m))
    }
}
