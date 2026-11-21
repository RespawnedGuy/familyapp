package familyapp.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IngredientLineParserTest {

    private fun check(line: String, amount: Double?, unit: String?, name: String, note: String? = null, amountMax: Double? = null) {
        val p = IngredientLineParser.parse(line)
        assertEquals(amount, p.amount?.let { Math.round(it * 1000) / 1000.0 }, "amount of '$line'")
        assertEquals(amountMax, p.amountMax, "amountMax of '$line'")
        assertEquals(unit, p.unit, "unit of '$line'")
        assertEquals(name, p.name, "name of '$line'")
        assertEquals(note, p.note, "note of '$line'")
    }

    @Test fun `amount unit name`() = check("250 g Kirschtomaten", 250.0, "g", "Kirschtomaten")
    @Test fun `glued unit`() = check("250g Spaghetti", 250.0, "g", "Spaghetti")
    @Test fun `note after comma`() = check("250 g Kirschtomaten, halbiert", 250.0, "g", "Kirschtomaten", "halbiert")
    @Test fun `note in brackets`() = check("1 Dose Kichererbsen (400 g)", 1.0, "Dose", "Kichererbsen", "400 g")
    @Test fun `decimal comma`() = check("1,5 l Milch", 1.5, "l", "Milch")
    @Test fun `slash fraction`() = check("1/2 TL Salz", 0.5, "TL", "Salz")
    @Test fun `mixed fraction`() = check("1 1/2 EL Zucker", 1.5, "EL", "Zucker")
    @Test fun `vulgar fraction`() = check("½ Bund Basilikum", 0.5, "Bund", "Basilikum")
    @Test fun `vulgar fraction after digit`() = check("1½ Tassen Reis", 1.5, "Tasse", "Reis")
    @Test fun range() = check("2-3 Zehen Knoblauch", 2.0, "Zehe", "Knoblauch", amountMax = 3.0)
    @Test fun `unit with dot`() = check("1 Pck. Backpulver", 1.0, "Packung", "Backpulver")
    @Test fun `no unit`() = check("1 rote Zwiebel", 1.0, null, "rote Zwiebel")
    @Test fun `unit prefix of a word is not a unit`() = check("1 gelbe Paprika", 1.0, null, "gelbe Paprika")
    @Test fun `no amount`() = check("Salz und Pfeffer", null, null, "Salz und Pfeffer")
    @Test fun `bullet and emoji`() = check("✅ 2 EL Olivenöl", 2.0, "EL", "Olivenöl")
    @Test fun `dash bullet`() = check("- 300 g Mehl", 300.0, "g", "Mehl")
    @Test fun `trailing amount`() = check("Mehl 250 g", 250.0, "g", "Mehl")
    @Test fun `trailing amount after comma`() = check("Salz, 1 Prise", 1.0, "Prise", "Salz")

    @Test fun `measured detection`() {
        assertEquals(true, IngredientLineParser.looksLikeMeasuredIngredient("400 ml Kokosmilch"))
        assertEquals(false, IngredientLineParser.looksLikeMeasuredIngredient("In 20 Minuten fertig und super lecker für die ganze Familie"))
        assertNull(IngredientLineParser.parse("Folgt mir für mehr").amount)
    }
}
