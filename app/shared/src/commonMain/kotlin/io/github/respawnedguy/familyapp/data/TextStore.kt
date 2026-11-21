package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.FamilyData
import kotlinx.serialization.json.Json

/**
 * Where the family data lives on the device: one JSON file in the app's private storage
 * (Nils asked on 21.11.2026 about a visible folder; that is offered as backup, see PR text).
 * Implementations must replace the file atomically (write temp file, then rename).
 */
interface TextStore {
    fun read(): String?
    fun write(text: String)

    /** Keeps an unreadable file next to the real one instead of overwriting it. */
    fun preserveBroken(text: String)
}

class InMemoryTextStore(var text: String? = null) : TextStore {
    val broken = mutableListOf<String>()
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
}

object FamilyJson {
    private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(data: FamilyData): String = compact.encodeToString(FamilyData.serializer(), data)
    fun encodePretty(data: FamilyData): String = pretty.encodeToString(FamilyData.serializer(), data)
    fun decode(text: String): FamilyData = compact.decodeFromString(FamilyData.serializer(), text)
}
