package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Scaling and display of amounts (docs/kochbibliothek.md "Portionen umrechnen"): amounts are
 * recomputed from the structured ingredient and rounded the way a cook reads them
 * (0.33 TL -> "⅓ TL", 1050 g -> "1,05 kg", 333 g -> "335 g"). Lines without an amount stay as written.
 */
object Amounts {

    /** Units that measure mass or volume, with their factor to the base unit (g or ml). */
    @Suppress("MagicNumber") // conversion table
    private val MASS = mapOf("mg" to 0.001, "g" to 1.0, "kg" to 1000.0)
    @Suppress("MagicNumber") // conversion table
    private val VOLUME = mapOf("ml" to 1.0, "cl" to 10.0, "l" to 1000.0)

    private val PLURAL = mapOf(
        "Zehe" to "Zehen", "Dose" to "Dosen", "Packung" to "Packungen", "Tasse" to "Tassen",
        "Scheibe" to "Scheiben", "Stange" to "Stangen", "Knolle" to "Knollen", "Prise" to "Prisen",
        "Zweig" to "Zweige", "Kopf" to "Köpfe", "Glas" to "Gläser",
    )

    @Suppress("MagicNumber") // the values of the fraction characters
    private val FRACTIONS = listOf(
        0.25 to "¼", 1.0 / 3 to "⅓", 0.5 to "½", 2.0 / 3 to "⅔", 0.75 to "¾",
    )

    private const val THOUSAND = 1000.0
    private const val FRACTION_TOLERANCE = 0.04
    private const val COARSE_FROM = 100.0
    private const val COARSE_STEP = 5.0
    private const val FINE_BELOW = 10.0
    private const val FINE_STEP = 0.5
    private const val WHOLE_FROM = 10.0
    private const val TWO_DECIMALS = 100.0
    private const val ONE_DECIMAL = 10.0

    /** Base unit ("g" or "ml") and the amount in it, or null when the unit is neither mass nor volume. */
    fun toBase(amount: Double, unit: String?): Pair<String, Double>? = when (unit) {
        in MASS -> "g" to amount * MASS.getValue(unit!!)
        in VOLUME -> "ml" to amount * VOLUME.getValue(unit!!)
        else -> null
    }

    /** "250 g Mehl", "2–3 Zehen Knoblauch", "Salz und Pfeffer", scaled by [factor]. */
    fun line(ingredient: Ingredient, factor: Double = 1.0): String {
        val amount = ingredient.amount ?: return ingredient.text.ifBlank { ingredient.name }
        val prefix = format(amount * factor, ingredient.amountMax?.let { it * factor }, ingredient.unit)
        val note = ingredient.note?.let { " ($it)" }.orEmpty()
        return "$prefix ${ingredient.name}$note"
    }

    /** Amount with unit, e.g. "1,05 kg", "⅓ TL", "2–3 Zehen", "4". */
    fun format(amount: Double, amountMax: Double? = null, unit: String? = null): String {
        val (shownUnit, divisor) = displayUnit(amount, unit)
        val low = number(amount / divisor, shownUnit)
        val high = amountMax?.takeIf { it > amount }?.let { number(it / divisor, shownUnit) }
        val numberText = if (high != null && high != low) "$low–$high" else low
        val plural = (amountMax ?: amount) / divisor > 1.0 + FRACTION_TOLERANCE
        val unitText = shownUnit?.let { if (plural) PLURAL[it] ?: it else it }
        return listOfNotNull(numberText, unitText).joinToString(" ")
    }

    /** 1000 g and more read as kg, 1000 ml and more as l. */
    private fun displayUnit(amount: Double, unit: String?): Pair<String?, Double> = when {
        unit == "g" && amount >= THOUSAND -> "kg" to THOUSAND
        unit == "ml" && amount >= THOUSAND -> "l" to THOUSAND
        else -> unit to 1.0
    }

    /** German number text, rounded by unit: grams to 5 above 100, spoons and pieces to common fractions. */
    internal fun number(value: Double, unit: String?): String = when (unit) {
        "g", "ml", "mg" -> decimal(roundGrams(value))
        "kg", "l" -> decimal((value * TWO_DECIMALS).roundToLong() / TWO_DECIMALS)
        "cl", "cm" -> decimal((value * ONE_DECIMAL).roundToLong() / ONE_DECIMAL)
        else -> pieces(value)
    }

    private fun roundGrams(value: Double): Double = when {
        value >= COARSE_FROM -> (value / COARSE_STEP).roundToLong() * COARSE_STEP
        value >= FINE_BELOW -> value.roundToLong().toDouble()
        else -> ((value / FINE_STEP).roundToLong() * FINE_STEP).coerceAtLeast(FINE_STEP)
    }

    /** Spoons, pieces, cans: whole number plus ¼, ⅓, ½, ⅔ or ¾ when close, else one decimal. */
    private fun pieces(value: Double): String {
        val whole = floor(value)
        val rest = value - whole
        val fraction = FRACTIONS.firstOrNull { abs(it.first - rest) < FRACTION_TOLERANCE }?.second
        return when {
            value >= WHOLE_FROM -> decimal(value.roundToLong().toDouble())
            rest < FRACTION_TOLERANCE -> decimal(whole)
            rest > 1 - FRACTION_TOLERANCE -> decimal(whole + 1)
            fraction == null -> decimal((value * ONE_DECIMAL).roundToLong() / ONE_DECIMAL)
            whole == 0.0 -> fraction
            else -> "${whole.toLong()}$fraction"
        }
    }

    /** "1,05", "250", "0,5": comma, no trailing zeros. */
    internal fun decimal(value: Double): String {
        val cents = (value * TWO_DECIMALS).roundToLong()
        val whole = cents / TWO_DECIMALS.toLong()
        val rest = abs(cents % TWO_DECIMALS.toLong())
        if (rest == 0L) return whole.toString()
        val digits = rest.toString().padStart(2, '0').trimEnd('0')
        return "$whole,$digits"
    }
}
