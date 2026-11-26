package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.FamilyData
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncMergeTest {
    private val friday = LocalDate(2026, 11, 20)

    /** One device with its own clock (offset simulates a wrong clock) and its own id space. */
    private fun TestScope.device(name: String, clockOffset: Long = 0): Pair<FamilyRepository, () -> Unit> {
        var ms = 1_000_000L + clockOffset
        var ids = 0
        val repo = FamilyRepository(
            store = InMemoryTextStore(),
            scope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            now = { ms },
            today = { friday },
            newId = { "$name-${ids++}" },
        )
        return repo to { ms += 1_000 }
    }

    private fun path(name: String) = "/local/$name"

    /** What a sync session does, without network: both sides send what the other lacks. */
    private fun meet(a: FamilyRepository, b: FamilyRepository) {
        val forB = SyncMerge.newerThan(a.current, SyncMerge.digest(b.current))
        val forA = SyncMerge.newerThan(b.current, SyncMerge.digest(a.current))
        b.applySync(forB, ::path)
        a.applySync(forA, ::path)
    }

    private fun shared(d: FamilyData) = Triple(d.members.sortedBy { it.id }, d.items.sortedBy { it.id }, d.tombstones.sortedBy { it.id })

    @Test
    fun newestVersionWinsAndTombstoneBeatsOlderEdit() = runTest {
        val (a, tickA) = device("a")
        val (b, tickB) = device("b")
        a.createFamily("Nils")
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), null, ::path)
        val item = a.capture("Elternabend am Freitag", Source.TEXT)
        meet(a, b)
        tickA(); a.rename(item.id, "Elternabend 19 Uhr")
        tickB(); tickB(); val undo = b.delete(item.id) // later wall time: deletion wins
        meet(a, b)
        assertTrue(a.current.items.none { it.id == item.id })
        assertEquals(shared(a.current), shared(b.current))
        // Undo of the deletion is a newer change and brings it back everywhere.
        tickB(); tickB(); tickB()
        b.undo(undo)
        meet(a, b)
        assertEquals("Elternabend am Freitag", a.current.items.single { it.id == item.id }.title)
        assertTrue(a.current.tombstones.none { it.id == item.id })
    }

    @Test
    fun applyingTwiceChangesNothingAndPhotosPointToLocalFolder() = runTest {
        val (a, _) = device("a")
        val (b, _) = device("b")
        a.createFamily("Nils")
        a.capture("", Source.PHOTO, photoPath = "/data/a/photos/abc-1.jpg")
        val records = SyncMerge.newerThan(a.current, emptyMap())
        b.joinFamily(SyncRecords(), null, ::path)
        assertEquals(2, b.applySync(records, ::path))
        assertEquals(0, b.applySync(records, ::path))
        assertEquals("/local/abc-1.jpg", b.current.items.single().photoPath)
        assertEquals(setOf("abc-1.jpg"), SyncMerge.photoNames(b.current))
        assertTrue(SyncMerge.newerThan(b.current, SyncMerge.digest(a.current)).size == 0)
    }

    @Test
    fun ownPersonIsNeverDeletedByOthers() = runTest {
        val (a, tickA) = device("a")
        val (b, _) = device("b")
        a.createFamily("Nils")
        val lisa = a.addMember("Lisa", MemberKind.ADULT)
        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), lisa.id, ::path)
        tickA()
        a.removeMember(lisa.id)
        meet(a, b)
        assertEquals("Lisa", b.current.self?.name)
    }

    @Test
    fun joiningKeepsOwnEntriesAndMatchesPeopleByName() = runTest {
        val (a, _) = device("a")
        val (b, _) = device("b")
        a.createFamily("Nils")
        val lisa = a.addMember("Lisa", MemberKind.ADULT)
        val mia = a.addMember("Mia", MemberKind.CHILD)
        // Lisa used the app alone before: her own family with "mia" and a new child "Ben".
        b.createFamily("Lisa")
        val bMia = b.addMember(" mia ", MemberKind.CHILD)
        val ben = b.addMember("Ben", MemberKind.CHILD)
        val bItem = b.capture("Schwimmkurs anmelden", Source.TEXT)
        b.setConcerns(bItem.id, bMia.id)
        b.setAssignee(bItem.id, b.current.selfId!!)

        b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), lisa.id, ::path)

        val d = b.current
        assertEquals(lisa.id, d.selfId)
        assertEquals(listOf("Ben", "Lisa", "Mia", "Nils"), d.members.map { it.name }.sorted())
        val carried = d.items.single { it.id == bItem.id }
        assertEquals(mia.id, carried.concerns)
        assertEquals(lisa.id, carried.assignee)
        assertEquals(lisa.id, carried.createdBy)
        // Ben and the carried item reach the inviter with the next sync.
        meet(a, b)
        assertTrue(a.current.members.any { it.id == ben.id })
        assertEquals(shared(a.current), shared(b.current))
    }

    @Test
    fun deviceListsKeepNewestInfoButOwnLastSync() {
        val mine = listOf(KnownDevice("x", "Pixel", addresses = listOf("10.0.0.2:1"), seenMs = 10, lastSyncMs = 99))
        val theirs = listOf(
            KnownDevice("x", "Pixel 9", addresses = listOf("10.0.0.3:1"), seenMs = 20, lastSyncMs = 1),
            KnownDevice("me", "Ich", seenMs = 30),
            KnownDevice("y", "Tablet", seenMs = 5),
        )
        val merged = mergeDevices(mine, theirs, selfDeviceId = "me")
        assertEquals(listOf("x", "y"), merged.map { it.deviceId })
        assertEquals("Pixel 9", merged[0].name)
        assertEquals(99, merged[0].lastSyncMs)
        assertNull(merged[1].lastSyncMs)
        assertEquals(mine, mergeDevices(mine, listOf(theirs[0].copy(seenMs = 1)), "me"))
    }

    @Test
    fun stateFileSurvivesGarbage() {
        assertEquals(SyncState(), SyncStateJson.decode("kaputt"))
        val s = SyncState("f", "k", listOf(KnownDevice("x", "Pixel")), folder = "content://x")
        assertEquals(s, SyncStateJson.decode(SyncStateJson.encode(s)))
    }

    /**
     * Acceptance test of ADR 0003 for the app's data: three devices, random changes on all of them,
     * wrong clocks, random pairwise meetings, A and C never meet directly. With several seeds all
     * three end up with the same members, items and tombstones.
     */
    @Test
    fun threeDevicesConvergeByGossipWithoutAAndCEverMeeting() = runTest {
        repeat(5) { seed ->
            val random = Random(seed)
            val (a, tickA) = device("a", clockOffset = 0)
            val (b, tickB) = device("b", clockOffset = -300_000)
            val (c, tickC) = device("c", clockOffset = 420_000)
            a.createFamily("Nils")
            val lisa = a.addMember("Lisa", MemberKind.ADULT)
            val oma = a.addMember("Oma", MemberKind.ADULT)
            b.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), lisa.id, ::path)
            c.joinFamily(SyncMerge.newerThan(a.current, emptyMap()), oma.id, ::path)
            val devices = listOf(a to tickA, b to tickB, c to tickC)
            repeat(400) { step ->
                val (repo, tick) = devices[random.nextInt(3)]
                tick()
                randomChange(repo, random, step)
                if (random.nextInt(5) == 0) {
                    if (random.nextBoolean()) meet(a, b) else meet(b, c)
                }
            }
            // Final gossip: only via B.
            meet(a, b); meet(b, c); meet(a, b)
            assertEquals(shared(a.current), shared(b.current), "seed $seed: A ≠ B")
            assertEquals(shared(b.current), shared(c.current), "seed $seed: B ≠ C")
            assertNotEquals(0, a.current.items.size + a.current.tombstones.size)
        }
    }

    private fun randomChange(repo: FamilyRepository, random: Random, step: Int) {
        val items = repo.current.items
        val members = repo.current.members
        val item = items.randomOrNull(random)
        when (random.nextInt(8)) {
            0, 1 -> repo.capture("Eintrag $step", Source.TEXT)
            2 -> item?.let { repo.setAssignee(it.id, members.random(random).id) }
            3 -> item?.let { repo.complete(it.id) }
            4 -> item?.let { repo.rename(it.id, "Titel $step") }
            5 -> item?.let { repo.delete(it.id) }
            6 -> item?.takeIf { it.status != ItemStatus.INBOX }?.let { repo.reopen(it.id) }
            else -> if (random.nextInt(4) == 0) repo.addMember("Kind $step", MemberKind.CHILD) else item?.let { repo.confirm(it.id) }
        }
    }
}
