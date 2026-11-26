package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.CURRENT_SCHEMA
import io.github.respawnedguy.familyapp.domain.FamilyData
import kotlinx.serialization.json.Json

/**
 * Where the family data lives on the device: one JSON file in the app's private storage
 * (Nils asked on 21.11.2026 about a visible folder; that is offered as backup, see PR text).
 * Implementations must replace the file atomically (write temp file, then rename).
 */
interface TextStore {
    fun read(): String?

    /** Replaces the stored text atomically. May throw (disk full, I/O error); the repository handles it. */
    fun write(text: String)

    /** Keeps an unreadable file next to the real one instead of overwriting it. */
    fun preserveBroken(text: String)

    /** The state before the last restore from a backup, so it can be brought back later (P0-2). */
    fun readSnapshot(): String?
    fun writeSnapshot(text: String?)
}

class InMemoryTextStore(var text: String? = null) : TextStore {
    val broken = mutableListOf<String>()
    var snapshot: String? = null
    var writes = 0
        private set

    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
        writes++
    }

    override fun preserveBroken(text: String) {
        broken += text
    }

    override fun readSnapshot(): String? = snapshot
    override fun writeSnapshot(text: String?) {
        snapshot = text
    }
}

/** The stored file comes from a newer app version; reading it would drop fields on the next write. */
class NewerSchemaException(found: Int) :
    IllegalStateException("Die Daten stammen aus einer neueren App-Version (Format $found, diese App kennt $CURRENT_SCHEMA).")

object FamilyJson {
    private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(data: FamilyData): String = compact.encodeToString(FamilyData.serializer(), data)
    fun encodePretty(data: FamilyData): String = pretty.encodeToString(FamilyData.serializer(), data)

    /**
     * Reads any released format and upgrades it to [CURRENT_SCHEMA]. Every released format has a
     * golden file in commonTest (FamilyJsonMigrationTest), so a change that breaks old data fails there.
     * Schema 1 → 2: tombstones, doneBy and changedBy were added with defaults, nothing to convert.
     */
    fun decode(text: String): FamilyData {
        val data = compact.decodeFromString(FamilyData.serializer(), text)
        if (data.schema > CURRENT_SCHEMA) throw NewerSchemaException(data.schema)
        return if (data.schema < CURRENT_SCHEMA) data.copy(schema = CURRENT_SCHEMA) else data
    }
}
