package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cases from the recipe spike (spikes/recipe-import), unchanged, plus the edge cases of the port. */
class IngredientParserTest {

    private fun check(line: String, amount: Double?, unit: String?, name: String, note: String? = null, amountMax: Double? = null) {
        val p = IngredientParser.parse(line)
        assertEquals(amount, p.amount?.let { (it * 1000).roundToLong() / 1000.0 }, "amount of '$line'")
        assertEquals(amountMax, p.amountMax, "amountMax of '$line'")
        assertEquals(unit, p.unit, "unit of '$line'")
        assertEquals(name, p.name, "name of '$line'")
        assertEquals(note, p.note, "note of '$line'")
        assertEquals(line.trim(), p.text, "original text kept")
    }

    @Test fun amountUnitName() = check("250 g Kirschtomaten", 250.0, "g", "Kirschtomaten")
    @Test fun gluedUnit() = check("250g Spaghetti", 250.0, "g", "Spaghetti")
    @Test fun noteAfterComma() = check("250 g Kirschtomaten, halbiert", 250.0, "g", "Kirschtomaten", "halbiert")
    @Test fun noteInBrackets() = check("1 Dose Kichererbsen (400 g)", 1.0, "Dose", "Kichererbsen", "400 g")
    @Test fun decimalComma() = check("1,5 l Milch", 1.5, "l", "Milch")
    @Test fun slashFraction() = check("1/2 TL Salz", 0.5, "TL", "Salz")
    @Test fun mixedFraction() = check("1 1/2 EL Zucker", 1.5, "EL", "Zucker")
    @Test fun vulgarFraction() = check("½ Bund Basilikum", 0.5, "Bund", "Basilikum")
    @Test fun vulgarFractionAfterDigit() = check("1½ Tassen Reis", 1.5, "Tasse", "Reis")
    @Test fun range() = check("2-3 Zehen Knoblauch", 2.0, "Zehe", "Knoblauch", amountMax = 3.0)
    @Test fun unitWithDot() = check("1 Pck. Backpulver", 1.0, "Packung", "Backpulver")
    @Test fun noUnit() = check("1 rote Zwiebel", 1.0, null, "rote Zwiebel")
    @Test fun unitPrefixOfAWordIsNotAUnit() = check("1 gelbe Paprika", 1.0, null, "gelbe Paprika")
    @Test fun noAmount() = check("Salz und Pfeffer", null, null, "Salz und Pfeffer")
    @Test fun bulletAndEmoji() = check("✅ 2 EL Olivenöl", 2.0, "EL", "Olivenöl")
    @Test fun dashBullet() = check("- 300 g Mehl", 300.0, "g", "Mehl")
    @Test fun trailingAmount() = check("Mehl 250 g", 250.0, "g", "Mehl")
    @Test fun trailingAmountAfterComma() = check("Salz, 1 Prise", 1.0, "Prise", "Salz")

    @Test fun divisionByZeroIsNoAmount() {
        val p = IngredientParser.parse("1/0 TL Salz")
        assertTrue(p.amount == null || p.amount!!.isFinite(), "no infinite amount: ${p.amount}")
    }

    @Test fun measuredDetection() {
        assertTrue(IngredientParser.looksLikeMeasuredIngredient("400 ml Kokosmilch"))
        assertFalse(IngredientParser.looksLikeMeasuredIngredient("In 20 Minuten fertig und super lecker für die ganze Familie"))
        assertNull(IngredientParser.parse("Folgt mir für mehr").amount)
    }
}
