package io.github.respawnedguy.familyapp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.respawnedguy.familyapp.ui.DarkFamilyColors
import io.github.respawnedguy.familyapp.ui.FamilyColors
import io.github.respawnedguy.familyapp.ui.LightFamilyColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Penpot design tokens (docs/gestaltung/penpot/familyapp-tokens.json, W3C DTCG format) must
 * match the colours in Theme.kt, so designs drawn in Penpot use exactly the app's colours.
 * If this fails after a colour change, update the JSON file and import it into Penpot again.
 */
class PenpotTokensTest {
    private val tokens: JsonObject by lazy {
        // Gradle runs JVM tests with the module directory (app/shared) as working directory.
        val file = File("../../docs/gestaltung/penpot/familyapp-tokens.json")
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun lightColorsMatchTheme() = assertSetMatches("Hell", LightFamilyColors)

    @Test
    fun darkColorsMatchTheme() = assertSetMatches("Dunkel", DarkFamilyColors)

    private fun assertSetMatches(set: String, c: FamilyColors) {
        val expected = linkedMapOf(
            "background" to c.background, "card" to c.card, "text" to c.text,
            "textSecondary" to c.textSecondary, "textTertiary" to c.textTertiary,
            "accent" to c.accent, "onAccent" to c.onAccent, "overdue" to c.overdue,
            "dueSoon" to c.dueSoon, "outline" to c.outline,
        ).mapValues { hex(it.value) } +
            c.persons.mapIndexed { i, color -> "person.${i + 1}" to hex(color) }

        val colors = tokens.getValue(set).jsonObject.getValue("color").jsonObject
        val actual = buildMap {
            for ((name, token) in colors) {
                val obj = token.jsonObject
                if ("\$value" in obj) {
                    put(name, obj.getValue("\$value").jsonPrimitive.content)
                } else {
                    for ((sub, t) in obj) put("$name.$sub", t.jsonObject.getValue("\$value").jsonPrimitive.content)
                }
            }
        }
        assertEquals(expected, actual, "Penpot tokens in set \"$set\" differ from Theme.kt")
    }

    private fun hex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)
}
