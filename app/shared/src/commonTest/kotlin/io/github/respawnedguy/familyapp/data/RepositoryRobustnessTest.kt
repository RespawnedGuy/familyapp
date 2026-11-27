package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.RecordKind
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.domain.Views
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate

/** A store that fails on demand, like a full disk (M6 of the quality assessment). */
private class FlakyStore : TextStore {
    var fail = false
    var text: String? = null
    var attempts = 0
    var snapshot: String? = null
    override fun read(): String? = text
    override fun write(text: String) {
        attempts++
        if (fail) throw RuntimeException("No space left on device")
        this.text = text
    }
    override fun preserveBroken(text: String) {}
    override fun readSnapshot(): String? = snapshot
    override fun writeSnapshot(text: String?) { snapshot = text }
}

/** P0-1, P0-2, P0-5, P0-6 of docs/qualitaet/iso-25010-bewertung.md. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryRobustnessTest {
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

    // ---- P0-1: a failed write neither crashes nor stops saving ---------------------------------

    @Test
    fun failedWriteIsReportedAndLaterChangesAreSavedAgain() = runTest {
        val store = FlakyStore()
        val r = repo(store)
        store.fail = true
        r.createFamily("Nils")
        assertNotNull(r.saveProblem.value)
        assertNull(store.text)

        store.fail = false
        r.capture("Zweiter Eintrag", Source.TEXT)
        assertNull(r.saveProblem.value)
        val saved = FamilyJson.decode(store.text!!)
        assertEquals("Nils", saved.self?.name)
        assertEquals("Zweiter Eintrag", saved.items.single().title)
    }

    @Test
    fun retrySaveWritesTheCurrentState() = runTest {
        val store = FlakyStore()
        val r = repo(store)
        store.fail = true
        r.createFamily("Nils")
        store.fail = false
        r.retrySave()
        assertNull(r.saveProblem.value)
        assertEquals("Nils", FamilyJson.decode(store.text!!).self?.name)
    }

    // ---- P0-5: who did what is recorded --------------------------------------------------------

    @Test
    fun everyChangeRecordsWhoMadeIt() = runTest {
        val r = repo().apply { createFamily("Nils") }
        val me = r.current.selfId
        val item = r.capture("Milch", Source.TEXT)
        assertEquals(me, r.current.items.single().changedBy)
        r.confirm(item.id)
        r.complete(item.id)
        val done = r.current.items.single()
        assertEquals(me, done.doneBy)
        assertEquals(me, done.changedBy)
        r.reopen(item.id)
        assertNull(r.current.items.single().doneBy)
    }

    @Test
    fun loadChartCountsWhatEachPersonFinishedThisWeek() = runTest {
        val r = repo().apply { createFamily("Nils") }
        val a = r.capture("A", Source.TEXT)
        val b = r.capture("B", Source.TEXT)
        r.confirm(a.id); r.complete(a.id)
        r.confirm(b.id); r.complete(b.id)
        assertEquals(2, Views.loads(r.current, friday).single { it.member.id == r.current.selfId }.doneThisWeek)
    }

    // ---- P0-6: deletion leaves a tombstone without content ------------------------------------

    @Test
    fun deleteLeavesATombstoneAndUndoRevivesTheItem() = runTest {
        val r = repo().apply { createFamily("Nils") }
        val item = r.capture("Geheimer Arzttermin", Source.TEXT)
        val undo = r.delete(item.id)
        assertTrue(r.current.items.isEmpty())
        val tomb = r.current.tombstones.single()
        assertEquals(item.id, tomb.id)
        assertEquals(RecordKind.ITEM, tomb.kind)
        assertTrue(tomb.stamp > item.stamp)
        assertFalse(FamilyJson.encode(r.current).contains("Arzttermin"))

        r.undo(undo)
        assertEquals("Geheimer Arzttermin", r.current.items.single().title)
        assertTrue(r.current.tombstones.isEmpty())
    }

    @Test
    fun removingAMemberLeavesATombstone() = runTest {
        val r = repo().apply { createFamily("Nils") }
        val lisa = r.addMember("Lisa", MemberKind.ADULT)
        val undo = r.removeMember(lisa.id)!!
        assertEquals(RecordKind.MEMBER, r.current.tombstones.single().kind)
        r.undo(undo)
        assertTrue(r.current.tombstones.isEmpty())
        assertEquals(2, r.current.members.size)
    }

    // ---- P0-2: restore keeps the state before and can be undone --------------------------------

    @Test
    fun restoreOfAnOlderBackupWarnsAndCanBeUndone() = runTest {
        val store = FlakyStore()
        val r = repo(store).apply { createFamily("Nils") }
        r.capture("Alt", Source.TEXT)
        val oldBackup = r.exportBackup()
        r.capture("Neu", Source.TEXT)
        val before = r.current

        val result = r.restoreBackup(oldBackup)
        assertNull(result.error)
        assertNotNull(result.note, "older backup must be pointed out")
        assertEquals(listOf("Alt"), r.current.items.map { it.title })
        assertTrue(r.hasRestoreSnapshot())

        r.undo(result.undo!!)
        assertEquals(before.items, r.current.items)
        assertFalse(r.hasRestoreSnapshot())
    }

    @Test
    fun stateBeforeRestoreCanBeBroughtBackLater() = runTest {
        val r = repo(FlakyStore()).apply { createFamily("Nils") }
        val backup = r.exportBackup()
        r.capture("Nach der Sicherung", Source.TEXT)
        r.restoreBackup(backup)
        assertTrue(r.current.items.isEmpty())

        assertNull(r.restoreSnapshot())
        assertEquals("Nach der Sicherung", r.current.items.single().title)
    }

    @Test
    fun restoreKeepsStampsSoAnOldBackupCannotWinASync() = runTest {
        val r = repo().apply { createFamily("Nils") }
        val item = r.capture("A", Source.TEXT)
        val backup = r.exportBackup()
        val other = repo()
        other.restoreBackup(backup)
        assertEquals(item.stamp, other.current.items.single().stamp)
        val next = other.capture("B", Source.TEXT)
        assertTrue(next.stamp > item.stamp)
    }

    @Test
    fun restoredPhotosPointToTheUnpackedFiles() = runTest {
        val r = repo().apply { createFamily("Nils") }
        r.capture("", Source.PHOTO, "/old/phone/photos/abc.jpg")
        val other = repo()
        other.restoreBackup(r.exportBackup(), mapOf("abc.jpg" to "/new/phone/photos/abc.jpg"))
        assertEquals("/new/phone/photos/abc.jpg", other.current.items.single().photoPath)
    }

    @Test
    fun orphanPhotosAreFoundButFreshOnesAreKept() = runTest {
        val r = repo().apply { createFamily("Nils") }
        r.capture("", Source.PHOTO, "/p/used.jpg")
        val hour = PhotoCleanup.MIN_AGE_MS
        val files = listOf(StoredPhoto("/p/used.jpg", 0), StoredPhoto("/p/old.jpg", 0), StoredPhoto("/p/fresh.jpg", 10 * hour))
        assertEquals(listOf("/p/old.jpg"), PhotoCleanup.orphans(files, r.current, nowMs = 10 * hour + 1))
        assertEquals(ItemStatus.INBOX, r.current.items.single().status)
    }

    @Test
    fun recipePicturesAreBackedUpRestoredAndKeptByTheCleanup() = runTest {
        val r = repo().apply { createFamily("Nils") }
        val (recipe, _) = r.recipeStore.add(RecipeImport.fromText("Salat"), RecipeStatus.SUGGESTED)
        r.recipeStore.attachPhoto(recipe.id, "/old/phone/photos/dish.jpg")
        assertEquals(listOf("/old/phone/photos/dish.jpg"), usedPhotoPaths(r.current), "goes into the backup")
        val other = repo()
        other.restoreBackup(r.exportBackup(), mapOf("dish.jpg" to "/new/phone/photos/dish.jpg"))
        assertEquals("/new/phone/photos/dish.jpg", other.current.recipes.single().photoPath)
        val hour = PhotoCleanup.MIN_AGE_MS
        val files = listOf(StoredPhoto("/old/phone/photos/dish.jpg", 0))
        assertTrue(PhotoCleanup.orphans(files, r.current, nowMs = 10 * hour).isEmpty())
        // A deleted recipe gives its picture up; the cleanup removes it at a later start.
        r.recipeStore.delete(recipe.id)
        assertEquals(listOf("/old/phone/photos/dish.jpg"), PhotoCleanup.orphans(files, r.current, nowMs = 10 * hour))
    }
}
