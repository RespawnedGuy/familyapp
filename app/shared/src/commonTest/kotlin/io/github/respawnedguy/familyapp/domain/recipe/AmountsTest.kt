package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rounding as in docs/kochbibliothek.md "Portionen umrechnen". */
class AmountsTest {

    @Test fun gramsBecomeKilosFromOneThousand() {
        assertEquals("1,05 kg", Amounts.format(1050.0, unit = "g"))
        assertEquals("1 kg", Amounts.format(1000.0, unit = "g"))
        assertEquals("1,5 l", Amounts.format(1500.0, unit = "ml"))
    }

    @Test fun gramsAreRoundedTheWayCooksRead() {
        assertEquals("335 g", Amounts.format(1000.0 / 3, unit = "g"))
        assertEquals("67 g", Amounts.format(66.6, unit = "g"))
        assertEquals("7,5 g", Amounts.format(7.4, unit = "g"))
        assertEquals("0,5 g", Amounts.format(0.1, unit = "g"))
    }

    @Test fun spoonsAndPiecesUseFractions() {
        assertEquals("⅓ TL", Amounts.format(0.33, unit = "TL"))
        assertEquals("1½ EL", Amounts.format(1.5, unit = "EL"))
        assertEquals("¾", Amounts.format(0.75))
        assertEquals("2", Amounts.format(1.98))
        assertEquals("1,2", Amounts.format(1.2))
        assertEquals("13", Amounts.format(12.6))
    }

    @Test fun unitsArePluralAboveOne() {
        assertEquals("2 Zehen", Amounts.format(2.0, unit = "Zehe"))
        assertEquals("1 Zehe", Amounts.format(1.0, unit = "Zehe"))
        assertEquals("2–3 Zehen", Amounts.format(2.0, 3.0, "Zehe"))
        assertEquals("2 Bund", Amounts.format(2.0, unit = "Bund"))
    }

    @Test fun lineScalesAmountAndRange() {
        val garlic = IngredientParser.parse("2-3 Zehen Knoblauch, gehackt")
        assertEquals("4–6 Zehen Knoblauch (gehackt)", Amounts.line(garlic, 2.0))
        assertEquals("½ TL Salz", Amounts.line(IngredientParser.parse("1 TL Salz"), 0.5))
    }

    @Test fun lineWithoutAmountStaysAsWritten() {
        assertEquals("Salz und Pfeffer", Amounts.line(IngredientParser.parse("Salz und Pfeffer"), 3.0))
    }

    @Test fun decimalHasNoTrailingZeros() {
        assertEquals("250", Amounts.decimal(250.0))
        assertEquals("0,5", Amounts.decimal(0.5))
        assertEquals("1,05", Amounts.decimal(1.05))
    }
}
