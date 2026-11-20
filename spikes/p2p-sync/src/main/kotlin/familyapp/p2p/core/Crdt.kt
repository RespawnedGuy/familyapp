package familyapp.p2p.core

import kotlinx.serialization.Serializable

// Copied unchanged from spikes/sync (PR #3): HLC, operation, LWW register per field.

/**
 * Hybrid Logical Clock timestamp (Kulkarni et al. 2014). Orders events causally even when
 * device clocks drift; the device id breaks ties so every replica picks the same winner.
 */
@Serializable
data class Hlc(val wallMs: Long, val counter: Int, val deviceId: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wallMs, Hlc::counter, Hlc::deviceId)
}

class HybridLogicalClock(private val deviceId: String, private val wallClock: () -> Long) {
    private var last = Hlc(0, 0, deviceId)

    fun now(): Hlc {
        val wall = wallClock()
        last = if (wall > last.wallMs) Hlc(wall, 0, deviceId) else last.copy(counter = last.counter + 1)
        return last
    }

    fun observe(remote: Hlc) {
        val wall = wallClock()
        val maxWall = maxOf(wall, last.wallMs, remote.wallMs)
        val counter = when {
            maxWall == last.wallMs && maxWall == remote.wallMs -> maxOf(last.counter, remote.counter) + 1
            maxWall == last.wallMs -> last.counter + 1
            maxWall == remote.wallMs -> remote.counter + 1
            else -> 0
        }
        last = Hlc(maxWall, counter, deviceId)
    }
}

/** One field change on one record. Deleting a record is a tombstone field, so late edits cannot resurrect it silently. */
@Serializable
data class Operation(val recordId: String, val field: String, val value: String?, val hlc: Hlc) {
    companion object {
        const val DELETED_FIELD = "_deleted"
    }
}

/** Last-writer-wins register per (record, field): commutative, associative, idempotent. */
class ReplicaState {
    private data class Cell(val value: String?, val hlc: Hlc)

    private val cells = HashMap<Pair<String, String>, Cell>()

    fun apply(op: Operation): Boolean {
        val key = op.recordId to op.field
        val current = cells[key]
        if (current != null && current.hlc >= op.hlc) return false
        cells[key] = Cell(op.value, op.hlc)
        return true
    }

    fun get(recordId: String, field: String): String? = cells[recordId to field]?.value

    fun isDeleted(recordId: String): Boolean = get(recordId, Operation.DELETED_FIELD) == "true"

    fun snapshot(): Map<String, String?> =
        cells.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .associate { "${it.key.first}.${it.key.second}" to it.value.value }
}
