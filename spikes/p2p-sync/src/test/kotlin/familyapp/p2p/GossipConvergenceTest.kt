package familyapp.p2p

import familyapp.p2p.core.FamilyPeer
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance criterion 1: three devices, 1,000 random changes, random pairwise encounters.
 * Never all three at once, and A and C never meet directly (B carries everything).
 * Some encounters break off mid-transfer. Afterwards all three hold the same state.
 */
class GossipConvergenceTest {

    @Test
    fun `three peers that never all meet converge, A and C never meet directly`() = runTest {
        for (seed in listOf(2026, 1, 7, 42, 99)) runScenario(seed)
    }

    private suspend fun runScenario(seed: Int) {
        val random = Random(seed)
        // Clocks drift: A runs 3 minutes ahead, C 2 minutes behind. HLC must still order correctly.
        var wall = 1_700_000_000_000L
        val drift = longArrayOf(180_000, 0, -120_000)
        val family = TestFamily(listOf("A", "B", "C")) { i -> { wall + drift[i] } }
        val (a, b, c) = family.peers
        val allowed = listOf(a to b, b to c)
        var dropped = 0
        var completed = 0

        repeat(1000) { i ->
            wall += random.nextLong(0, 2_000)
            val peer = family.peers[random.nextInt(3)]
            val record = "task${random.nextInt(40)}"
            when (random.nextInt(10)) {
                0 -> peer.delete(record)
                else -> peer.set(record, listOf("title", "assignee", "due", "status")[random.nextInt(4)], "v$i")
            }
            if (random.nextInt(25) == 0) {
                val (x, y) = allowed[random.nextInt(allowed.size)]
                val drop = random.nextInt(4) == 0
                val (left, right) = encounter(x, y) { raw -> if (drop) DroppingChannel(raw, random.nextInt(2, 6)) else raw }
                if (left.isSuccess && right.isSuccess) completed++ else dropped++
            }
        }
        // Final round, still never A with C.
        listOf(a to b, b to c, a to b).forEach { (x, y) ->
            val (l, r) = encounter(x, y)
            assertTrue(l.isSuccess && r.isSuccess, "final encounter failed: ${l.exceptionOrNull() ?: r.exceptionOrNull()}")
        }

        assertTrue(dropped > 0 && completed > 0, "seed $seed must exercise both clean and broken sessions")
        listOf(a, b, c).forEach { assertEquals(1000, it.log.size(), "seed $seed: every peer holds every change") }
        assertEquals(a.versionVector(), c.versionVector())
        assertEquals(a.state.snapshot(), b.state.snapshot(), "seed $seed: A = B")
        assertEquals(b.state.snapshot(), c.state.snapshot(), "seed $seed: B = C")
        assertEquals(3, c.membership.all().size, "C learned A's membership via B")
        assertTrue(a.state.snapshot().isNotEmpty())
        // Order independence: a fresh replica fed all changes in reverse gets the same state.
        val fresh = FamilyPeer(family.identities[0], family.key, a.membership.copy())
        a.log.all().groupBy { it.origin }.values.reversed().forEach { list -> list.forEach { fresh.receive(it) } }
        assertEquals(a.state.snapshot(), fresh.state.snapshot())
    }

    @Test
    fun `a broken session keeps its progress and the next one resumes`() = runTest {
        val family = TestFamily(listOf("A", "B"))
        val (a, b) = family.peers
        repeat(1000) { a.set("r$it", "title", "x$it") }
        // 200 changes per frame; drop after handshake + hello + 2 chunks.
        encounter(a, b) { DroppingChannel(it, 4) }
        val afterDrop = b.log.size()
        assertTrue(afterDrop in 1 until 1000, "partial transfer expected, got $afterDrop")
        val (l, r) = encounter(a, b)
        assertTrue(l.isSuccess && r.isSuccess)
        assertEquals(1000 - afterDrop, r.getOrThrow().received, "only the missing rest is transferred")
        assertEquals(a.state.snapshot(), b.state.snapshot())
    }
}
