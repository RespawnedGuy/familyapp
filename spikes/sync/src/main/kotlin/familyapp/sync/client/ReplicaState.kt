package familyapp.sync.client

/**
 * Last-writer-wins register per (record, field), ordered by HLC. Applying the same set of
 * operations in any order, any number of times, yields the same state on every device
 * (commutative, associative, idempotent: a state-based CRDT, Shapiro et al. 2011).
 */
class ReplicaState {
    private data class Cell(val value: String?, val hlc: Hlc)

    private val cells = HashMap<Pair<String, String>, Cell>()

    /** Returns true if the operation changed the state. */
    fun apply(op: Operation): Boolean {
        val key = op.recordId to op.field
        val current = cells[key]
        if (current != null && current.hlc >= op.hlc) return false
        cells[key] = Cell(op.value, op.hlc)
        return true
    }

    fun get(recordId: String, field: String): String? = cells[recordId to field]?.value

    fun isDeleted(recordId: String): Boolean = get(recordId, Operation.DELETED_FIELD) == "true"

    /** Deterministic snapshot for comparing replicas. */
    fun snapshot(): Map<String, String?> =
        cells.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .associate { "${it.key.first}.${it.key.second}" to it.value.value }
}
