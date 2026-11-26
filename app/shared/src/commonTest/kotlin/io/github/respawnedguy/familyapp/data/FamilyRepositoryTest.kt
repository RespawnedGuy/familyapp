package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Origin
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.Views
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyRepositoryTest {
    private val friday = LocalDate(2026, 11, 20)
    private var clockMs = 1_000L
    private var ids = 0

    private fun TestScope.repo(store: TextStore = InMemoryTextStore()) = FamilyRepository(
        store = store,
        scope = backgroundScope,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        now = { clockMs++ },
        today = { friday },
        newId = { "id${ids++}" },
    )

    private fun TestScope.family(): FamilyRepository = repo().apply {
        createFamily("Nils")
        addMember("Lisa", MemberKind.ADULT)
        addMember("Mia", MemberKind.CHILD)
    }

    private fun FamilyRepository.memberId(name: String) = current.members.first { it.name == name }.id

    @Test
    fun onboardingCreatesSelfWithDeviceAndFirstColor() = runTest {
        val r = repo()
        r.createFamily("  Nils ")
        val me = assertNotNull(r.current.self)
        assertEquals("Nils", me.name)
        assertEquals(0, me.colorIndex)
        assertTrue(me.hasDevice)
    }

    @Test
    fun membersGetFreeColors() = runTest {
        val r = family()
        assertEquals(listOf(0, 1, 2), r.current.members.map { it.colorIndex })
    }

    @Test
    fun captureAppliesRulesAsSuggestionsOnly() = runTest {
        val r = family()
        val item = r.capture("mia braucht bis Donnerstag neue Hausschuhe", Source.VOICE)
        assertEquals("Mia braucht bis Donnerstag neue Hausschuhe", item.title)
        assertEquals(ItemStatus.INBOX, item.status)
        assertEquals(r.memberId("Mia"), item.concerns)
        assertEquals(Origin.RULE, item.concernsOrigin)
        assertEquals(LocalDate(2026, 11, 26), item.due)
        assertFalse(item.dueDecided)
        assertNull(item.assignee, "kümmert sich is never guessed")
    }

    @Test
    fun choosingPersonConfirmsWhenDateWasSuggested() = runTest {
        val r = family()
        val item = r.capture("Hausschuhe bis Donnerstag", Source.VOICE)
        val result = r.setAssignee(item.id, r.memberId("Lisa"))
        assertTrue(result.confirmed)
        val now = r.current.items.single()
        assertEquals(ItemStatus.OPEN, now.status)
        assertTrue(now.dueDecided)
        assertEquals(2, now.inboxTaps)
    }

    @Test
    fun personAndDateBothMissingNeedsTwoChoices() = runTest {
        val r = family()
        val item = r.capture("Oma anrufen", Source.TEXT)
        assertFalse(r.setAssignee(item.id, r.memberId("Lisa")).confirmed)
        assertEquals(ItemStatus.INBOX, r.current.items.single().status)
        assertTrue(r.setDue(item.id, null).confirmed)
        assertEquals(ItemStatus.OPEN, r.current.items.single().status)
        assertEquals(4, r.current.items.single().inboxTaps)
    }

    @Test
    fun concernsChoiceDoesNotConfirm() = runTest {
        val r = family()
        val item = r.capture("Turnbeutel", Source.TEXT)
        r.setConcerns(item.id, r.memberId("Mia"))
        assertEquals(ItemStatus.INBOX, r.current.items.single().status)
    }

    @Test
    fun confirmWithoutAssigneeMeansIDoIt() = runTest {
        val r = family()
        val item = r.capture("Milch kaufen", Source.TEXT)
        r.confirm(item.id)
        val now = r.current.items.single()
        assertEquals(ItemStatus.OPEN, now.status)
        assertEquals(r.current.selfId, now.assignee)
        assertNull(now.due)
        assertEquals(1, now.inboxTaps)
        assertEquals(listOf(now), Views.mine(r.current))
    }

    @Test
    fun undoRevertsOnlyTheTouchedItem() = runTest {
        val r = family()
        val a = r.capture("A", Source.TEXT)
        val b = r.capture("B", Source.TEXT)
        val undo = r.markIrrelevant(a.id)
        r.confirm(b.id)
        r.undo(undo)
        assertEquals(ItemStatus.INBOX, r.current.items.first { it.id == a.id }.status)
        assertEquals(ItemStatus.OPEN, r.current.items.first { it.id == b.id }.status)
    }

    @Test
    fun undoOfCaptureRemovesTheItem() = runTest {
        val r = family()
        val a = r.capture("A", Source.TEXT)
        r.undo(Undo(mapOf(a.id to null)))
        assertTrue(r.current.items.isEmpty())
    }

    @Test
    fun everyChangeGetsANewerStamp() = runTest {
        val r = family()
        val a = r.capture("A", Source.TEXT)
        r.confirm(a.id)
        val after = r.current.items.single().stamp
        assertTrue(after > a.stamp)
        assertEquals(r.current.deviceId, after.device)
    }

    @Test
    fun removingMemberSendsTheirOpenItemsBackToInbox() = runTest {
        val r = family()
        val lisa = r.memberId("Lisa")
        val a = r.capture("Elternabend", Source.TEXT)
        r.setAssignee(a.id, lisa)
        r.setDue(a.id, null)
        val undo = assertNotNull(r.removeMember(lisa))
        val item = r.current.items.single()
        assertEquals(ItemStatus.INBOX, item.status)
        assertNull(item.assignee)
        r.undo(undo)
        assertEquals(lisa, r.current.items.single().assignee)
        assertNotNull(r.current.member(lisa))
    }

    @Test
    fun selfCannotBeRemoved() = runTest {
        val r = family()
        assertNull(r.removeMember(r.current.selfId!!))
    }

    @Test
    fun dataIsPersistedAndReloaded() = runTest {
        val store = InMemoryTextStore()
        val r = repo(store)
        r.createFamily("Nils")
        r.capture("Mia bis morgen", Source.VOICE)
        val again = repo(store)
        assertEquals(r.current, again.current)
        assertTrue(store.writes >= 2)
    }

    @Test
    fun brokenFileIsKeptAndAppStartsEmpty() = runTest {
        val store = InMemoryTextStore("{kaputt")
        val r = repo(store)
        assertNull(r.current.selfId)
        assertEquals(listOf("{kaputt"), store.broken)
        assertNotNull(r.loadProblem)
    }

    @Test
    fun backupRoundTripKeepsThisDeviceId() = runTest {
        val r = family()
        r.capture("A", Source.TEXT)
        val backup = r.exportBackup()
        val other = repo()
        assertNull(other.restoreBackup(backup).error)
        assertEquals(r.current.items, other.current.items)
        assertEquals(r.current.members, other.current.members)
        assertFalse(other.current.deviceId == r.current.deviceId)
    }

    @Test
    fun restoreRejectsForeignFiles() = runTest {
        val r = family()
        assertNotNull(r.restoreBackup("""{"hello":1}""").error)
        assertNotNull(r.restoreBackup("kein json").error)
        assertEquals(3, r.current.members.size)
    }

    @Test
    fun latencyKeepsTheLast50Samples() = runTest {
        val r = family()
        repeat(60) { r.recordLatency("Kachel", it.toLong()) }
        assertEquals(50, r.current.settings.latency.size)
        assertEquals(10L, r.current.settings.latency.first().ms)
    }
}
