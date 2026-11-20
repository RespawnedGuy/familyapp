package familyapp.sync

import familyapp.sync.client.HybridLogicalClock
import familyapp.sync.client.Operation
import familyapp.sync.client.ReplicaState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrdtConvergenceTest {

    @Test
    fun `any order and duplicates converge to the same state`() {
        val random = Random(42)
        var wall = 1_000L
        val clocks = listOf("a", "b", "c").map { HybridLogicalClock(it) { wall } }
        val ops = List(2000) {
            if (random.nextInt(3) == 0) wall += random.nextLong(0, 5) // equal wall times force counter + tie-break
            val clock = clocks[random.nextInt(clocks.size)]
            Operation("rec${random.nextInt(20)}", "f${random.nextInt(4)}", random.nextInt(100).toString(), clock.now())
        }

        val snapshots = (1..5).map { round ->
            val shuffled = (ops + ops.take(300)).shuffled(Random(round))
            ReplicaState().apply { shuffled.forEach { apply(it) } }.snapshot()
        }

        assertTrue(snapshots.first().isNotEmpty())
        snapshots.forEach { assertEquals(snapshots.first(), it) }
    }

    @Test
    fun `later write wins and tombstone marks deletion`() {
        var wall = 10L
        val a = HybridLogicalClock("a") { wall }
        val state = ReplicaState()
        val first = Operation("task1", "title", "Turnschuhe", a.now())
        wall = 20
        val second = Operation("task1", "title", "Turnschuhe Gr. 32", a.now())
        state.apply(second)
        state.apply(first)
        assertEquals("Turnschuhe Gr. 32", state.get("task1", "title"))

        state.apply(Operation("task1", Operation.DELETED_FIELD, "true", a.now()))
        assertTrue(state.isDeleted("task1"))
    }

    @Test
    fun `observing a remote clock orders later local events after it`() {
        val local = HybridLogicalClock("a") { 100 }
        val remoteStamp = HybridLogicalClock("b") { 5_000 }.now()
        local.observe(remoteStamp)
        assertTrue(local.now() > remoteStamp)
    }
}
