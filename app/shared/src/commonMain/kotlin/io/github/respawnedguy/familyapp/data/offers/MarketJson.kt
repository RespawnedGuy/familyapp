package io.github.respawnedguy.familyapp.data.offers

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/**
 * Reading untrusted JSON of the markets (threat model AN-2): wrong types give null, never an
 * exception, and only plain text and number fields are taken.
 */
internal object MarketJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/** Values as the markets write them: euros, "24 %", "0,99 €", ISO dates, product names. */
internal object MarketValues {
    private const val CENTS = 100
    private const val ISO_DATE = 10
    private const val MAX_TITLE = 160
    private val PERCENT = Regex("""(\d{1,2})\s*%""")
    private val EURO = Regex("""(\d{1,4}),(\d{2})""")
    private val WHITESPACE = Regex("""\s+""")

    fun cents(euro: Double?): Int? = euro?.let { (it * CENTS).roundToInt() }

    fun date(text: String?): LocalDate? =
        text?.let { runCatching { LocalDate.parse(it.take(ISO_DATE)) }.getOrNull() }

    fun percent(text: String?): Int? = text?.let { PERCENT.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** "0,99 €" -> 99. */
    fun euroText(text: String): Int? =
        EURO.find(text)?.let { m -> m.groupValues[1].toInt() * CENTS + m.groupValues[2].toInt() }

    /** Clean title: name and brand, whitespace collapsed, capped. */
    fun title(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
            .replace(WHITESPACE, " ").trim().take(MAX_TITLE)
}
