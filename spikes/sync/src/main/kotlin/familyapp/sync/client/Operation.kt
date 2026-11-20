package familyapp.sync.client

import kotlinx.serialization.Serializable

/**
 * One field change on one record of the family graph. A null [value] clears the field.
 * Deleting a record is the field [DELETED_FIELD] set to "true" (a tombstone), so a late
 * edit from an offline device cannot resurrect it silently.
 */
@Serializable
data class Operation(val recordId: String, val field: String, val value: String?, val hlc: Hlc) {
    val opId: String get() = "${hlc.deviceId}:${hlc.wallMs}:${hlc.counter}"

    companion object {
        const val DELETED_FIELD = "_deleted"
    }
}

@Serializable
data class OperationBatch(val operations: List<Operation>)
