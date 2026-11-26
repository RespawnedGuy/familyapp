package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.CURRENT_SCHEMA
import io.github.respawnedguy.familyapp.domain.ItemStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P0-7: data written by every released version must load without loss (ISO 25010 recoverability). */
class FamilyJsonMigrationTest {

    @Test
    fun everyReleasedSchemaLoadsAndIsUpgraded() {
        GoldenFiles.ALL.forEach { (schema, text) ->
            val data = FamilyJson.decode(text)
            assertEquals(CURRENT_SCHEMA, data.schema, "schema $schema")
            assertTrue(data.items.isNotEmpty() && data.members.isNotEmpty(), "schema $schema")
        }
    }

    @Test
    fun schema1KeepsEveryField() {
        val d = FamilyJson.decode(GoldenFiles.SCHEMA_1)
        assertEquals(listOf("Nils", "Lisa", "Mia"), d.members.map { it.name })
        assertEquals("id1", d.selfId)
        val shoes = d.items.first { it.title.startsWith("Mia braucht") }
        assertEquals(ItemStatus.OPEN, shoes.status)
        assertEquals("id2", shoes.assignee)
        assertEquals("id3", shoes.concerns)
        assertEquals(LocalDate(2026, 11, 27), shoes.due)
        assertTrue(shoes.reasons.isNotEmpty())
        assertEquals("/data/user/0/io.github.respawnedguy.familyapp/files/photos/abc.jpg", d.items.first { it.title == "Foto" }.photoPath)
        assertEquals(ItemStatus.DONE, d.items.first { it.title == "Milch" }.status)
        assertEquals(900L, d.settings.latency.single().ms)
        // Before P1-10 every sample measured "until recording runs".
        assertEquals(io.github.respawnedguy.familyapp.domain.Metric.CAPTURE_READY, d.settings.latency.single().metric)
        assertNull(shoes.changedBy)
        assertTrue(d.tombstones.isEmpty())
    }

    @Test
    fun currentFormatRoundTrips() {
        val d = FamilyJson.decode(GoldenFiles.SCHEMA_1)
        assertEquals(d, FamilyJson.decode(FamilyJson.encode(d)))
    }

    @Test
    fun fileFromANewerAppIsNotReadSilently() {
        val newer = GoldenFiles.SCHEMA_1.replaceFirst("\"schema\": 1", "\"schema\": ${CURRENT_SCHEMA + 1}")
        assertFailsWith<NewerSchemaException> { FamilyJson.decode(newer) }
    }
}
