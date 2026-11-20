package familyapp.p2p

import familyapp.p2p.core.HybridLogicalClock
import familyapp.p2p.core.Operation
import familyapp.p2p.core.ReplicaState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unchanged from spikes/sync: the CRDT itself. */
class CrdtConvergenceTest {

    @Test
    fun `any order and duplicates converge to the same state`() {
        val random = Random(42)
        var wall = 1_000L
        val clocks = listOf("a", "b", "c").map { HybridLogicalClock(it) { wall } }
        val ops = List(2000) {
            if (random.nextInt(3) == 0) wall += random.nextLong(0, 5)
            val clock = clocks[random.nextInt(clocks.size)]
            Operation("rec${random.nextInt(20)}", "f${random.nextInt(4)}", random.nextInt(100).toString(), clock.now())
        }
        val snapshots = (1..5).map { round ->
            ReplicaState().apply { (ops + ops.take(300)).shuffled(Random(round)).forEach { apply(it) } }.snapshot()
        }
        assertTrue(snapshots.first().isNotEmpty())
        snapshots.forEach { assertEquals(snapshots.first(), it) }
    }

    @Test
    fun `observing a remote clock orders later local events after it`() {
        val local = HybridLogicalClock("a") { 100 }
        val remoteStamp = HybridLogicalClock("b") { 5_000 }.now()
        local.observe(remoteStamp)
        assertTrue(local.now() > remoteStamp)
    }
}
