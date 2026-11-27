package io.github.respawnedguy.familyapp.domain.offers

import io.github.respawnedguy.familyapp.domain.recipe.IngredientKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonCalendarTest {
    private val nov = 11
    private val jan = 1

    private fun level(name: String, month: Int) = SeasonCalendar.level(IngredientKey.of(name), month)

    @Test fun everyEntryIsFoundByItsOwnNameAndKeysAreUnique() {
        val keys = SeasonCalendar.ENTRIES.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "doppelte Schlüssel: ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}")
        SeasonCalendar.ENTRIES.forEach { e ->
            assertEquals(e, SeasonCalendar.entryFor(IngredientKey.of(e.name)), e.name)
            assertTrue(e.season.isNotEmpty(), "${e.name} ohne Saison")
            assertTrue(e.season.intersect(e.stored).isEmpty(), "${e.name}: Saison und Lager überlappen")
            assertTrue((e.season + e.stored).all { it in 1..12 }, e.name)
        }
    }

    @Test fun recipeSpellingsHitTheSameEntry() {
        val moehre = SeasonCalendar.ENTRIES.single { it.name == "Möhren" }
        listOf("Möhre", "Möhren", "Karotten", "2 Karotte".substringAfter(' ')).forEach {
            assertEquals(moehre, SeasonCalendar.entryFor(IngredientKey.of(it)), it)
        }
        assertEquals("Äpfel", SeasonCalendar.entryFor(IngredientKey.of("Apfel"))?.name)
        assertEquals("Lauch", SeasonCalendar.entryFor(IngredientKey.of("Porree"))?.name)
        assertEquals("Rote Bete", SeasonCalendar.entryFor(IngredientKey.of("Rote Beete"))?.name)
    }

    @Test fun compoundsFindTheLongestEntry() {
        assertEquals("Kürbis", SeasonCalendar.entryFor(IngredientKey.of("Hokkaidokürbis"))?.name)
        assertEquals("Staudensellerie", SeasonCalendar.entryFor(IngredientKey.of("Staudensellerie"))?.name)
        assertEquals("Sellerie", SeasonCalendar.entryFor(IngredientKey.of("Knollensellerie"))?.name)
        assertEquals("Bärlauch", SeasonCalendar.entryFor(IngredientKey.of("Bärlauch"))?.name)
        assertEquals("Spinat", SeasonCalendar.entryFor(IngredientKey.of("Babyspinat"))?.name)
    }

    @Test fun lookalikesAreNoProduce() {
        listOf("Knoblauch", "Schnittlauch", "Kichererbsen", "Kidneybohnen", "Paprikapulver", "Champignons", "Banane")
            .forEach { assertNull(SeasonCalendar.entryFor(IngredientKey.of(it)), it) }
    }

    @Test fun novemberAndJanuary() {
        assertEquals(SeasonLevel.SEASON, level("Grünkohl", nov))
        assertEquals(SeasonLevel.SEASON, level("Feldsalat", jan))
        assertEquals(SeasonLevel.SEASON, level("Möhren", nov))
        assertEquals(SeasonLevel.STORED, level("Möhren", jan))
        assertEquals(SeasonLevel.STORED, level("Äpfel", jan))
        assertNull(level("Spargel", nov))
        assertNull(level("Erdbeeren", jan))
        assertTrue(SeasonCalendar.inSeason(nov).any { it.name == "Rosenkohl" })
        assertTrue(SeasonCalendar.inSeason(nov).none { it.name == "Zucchini" })
    }
}
