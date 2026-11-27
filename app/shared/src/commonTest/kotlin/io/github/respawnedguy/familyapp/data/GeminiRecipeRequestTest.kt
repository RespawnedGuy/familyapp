package io.github.respawnedguy.familyapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threat model TE-11, ADR 0008: what goes to Gemini and how its answers are read. */
class GeminiRecipeRequestTest {

    private fun parts(body: String): JsonArray =
        Json.parseToJsonElement(body).jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray

    private fun system(body: String) =
        Json.parseToJsonElement(body).jsonObject["system_instruction"]!!.jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun textOnlyRequestHasSchemaAndWrappedCaption() {
        val body = GeminiRecipeRequest.body("Brot backen")
        val root = Json.parseToJsonElement(body).jsonObject
        val config = root["generation_config"]!!.jsonObject
        assertEquals("application/json", config["response_mime_type"]!!.jsonPrimitive.content)
        assertEquals("OBJECT", config["response_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1, parts(body).size)
        assertEquals("<rezepttext>\nBrot backen\n</rezepttext>", parts(body)[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(system(body).contains("Video"), "no video instruction without a video")
        assertTrue(system(body).contains("nicht an dich gerichtet"))
    }

    @Test
    fun videoGoesBeforeTheCaptionInlineOrAsFile() {
        val inline = GeminiRecipeRequest.body("Suppe", GeminiRecipeRequest.Media.Inline("video/mp4", "AAAA"))
        val data = parts(inline)[0].jsonObject["inline_data"]!!.jsonObject
        assertEquals("video/mp4", data["mime_type"]!!.jsonPrimitive.content)
        assertEquals("AAAA", data["data"]!!.jsonPrimitive.content)
        assertTrue(system(inline).contains("eingeblendet"))

        val file = GeminiRecipeRequest.body("Suppe", GeminiRecipeRequest.Media.Uploaded("video/mp4", "https://g/files/abc"))
        assertEquals("https://g/files/abc", parts(file)[0].jsonObject["file_data"]!!.jsonObject["file_uri"]!!.jsonPrimitive.content)
        assertEquals(2, parts(file).size)
    }

    @Test
    fun longCaptionIsCut() {
        val body = GeminiRecipeRequest.body("x".repeat(ClaudeRecipeRequest.MAX_INPUT_CHARS + 500))
        val text = parts(body)[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(ClaudeRecipeRequest.MAX_INPUT_CHARS + "<rezepttext>\n\n</rezepttext>".length, text.length)
    }

    private fun answer(text: String, finish: String = "STOP", thought: String? = null): String {
        val parts = buildString {
            if (thought != null) append("{\"text\":${JsonPrimitive(thought)},\"thought\":true},")
            append("{\"text\":${JsonPrimitive(text)}}")
        }
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[$parts]},\"finishReason\":\"$finish\"}]}"
    }

    @Test
    fun recipeIsReadAndThoughtsAreSkipped() {
        val json = """{"title":"Shakshuka","servings":2,"ingredients":["4 Eier"],"steps":["Braten"]}"""
        val ok = assertIs<AiOutcome.Ok>(GeminiRecipeRequest.parse(answer(json, thought = "Ich überlege {")))
        assertEquals("Shakshuka", ok.recipe.title)
        assertEquals(listOf("4 Eier"), ok.recipe.ingredients)
    }

    @Test
    fun blockedTooLongAndUnreadableAnswersAreSaid() {
        assertEquals(
            "Die KI hat den Beitrag abgelehnt.",
            assertIs<AiOutcome.Failed>(GeminiRecipeRequest.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")).reason,
        )
        assertEquals("Die KI hat den Beitrag abgelehnt.", (GeminiRecipeRequest.parse(answer("", "RECITATION")) as AiOutcome.Failed).reason)
        assertEquals("Die Antwort der KI war zu lang.", (GeminiRecipeRequest.parse(answer("{\"ti", "MAX_TOKENS")) as AiOutcome.Failed).reason)
        assertEquals(ClaudeRecipeRequest.UNREADABLE, (GeminiRecipeRequest.parse(answer("kein JSON")) as AiOutcome.Failed).reason)
        assertEquals(ClaudeRecipeRequest.UNREADABLE, (GeminiRecipeRequest.parse("<html>") as AiOutcome.Failed).reason)
        assertEquals(ClaudeRecipeRequest.UNREADABLE, (GeminiRecipeRequest.parse("{}") as AiOutcome.Failed).reason)
    }

    @Test
    fun errorsBecomePlainGerman() {
        val badKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertTrue(GeminiRecipeRequest.failure(400, badKey).reason.startsWith("API-Schlüssel ungültig"))
        assertTrue(GeminiRecipeRequest.failure(401, null).reason.startsWith("API-Schlüssel ungültig"))
        assertEquals("Der API-Schlüssel darf das nicht.", GeminiRecipeRequest.failure(403, "{}").reason)
        val quota = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""
        assertTrue(GeminiRecipeRequest.failure(429, quota).reason.startsWith("Gratis-Kontingent"))
        val region = """{"error":{"code":400,"message":"User location is not supported","status":"FAILED_PRECONDITION"}}"""
        assertEquals("Google bietet die kostenlose Stufe hier nicht an.", GeminiRecipeRequest.failure(400, region).reason)
        assertEquals("Die KI ist gerade nicht erreichbar.", GeminiRecipeRequest.failure(503, "nope").reason)
        assertEquals("Die KI hat die Anfrage nicht angenommen (404).", GeminiRecipeRequest.failure(404, null).reason)
    }

    @Test
    fun uploadedFileNamesAreChecked() {
        val file = GeminiRecipeRequest.parseFile("""{"file":{"name":"files/abc-123","uri":"https://g/v1beta/files/abc-123","state":"PROCESSING"}}""")!!
        assertEquals("files/abc-123", file.name)
        assertEquals("PROCESSING", file.state)
        assertEquals("ACTIVE", GeminiRecipeRequest.parseFile("""{"name":"files/x1","uri":"u","state":"ACTIVE"}""")!!.state)
        assertNull(GeminiRecipeRequest.parseFile("""{"name":"files/../../models","uri":"u"}"""), "TE-11: no path tricks into later requests")
        assertNull(GeminiRecipeRequest.parseFile("""{"name":"files/x1"}"""))
        assertNull(GeminiRecipeRequest.parseFile("kaputt"))
        val start = Json.parseToJsonElement(GeminiRecipeRequest.uploadStart()).jsonObject
        assertIs<JsonObject>(start["file"])
    }

    @Test
    fun providerFollowsTheKeysPrefix() {
        assertEquals(AiProvider.GEMINI, AiProvider.ofKey("AIzaSyExample"))
        assertEquals(AiProvider.GEMINI, AiProvider.ofKey("AQ.Ab8RN6Example"), "AI Studio's newer key format")
        assertNull(AiProvider.ofKey("AQExample"), "the dot belongs to the prefix")
        assertEquals(AiProvider.CLAUDE, AiProvider.ofKey("sk-ant-api03-example"))
        assertNull(AiProvider.ofKey("sk-or-v1-example"))
        assertNull(AiProvider.ofKey(""))
    }

    @Test
    fun textOnlyAisIgnoreTheVideo() {
        val ai = RecipeAi { AiOutcome.Failed(it) }
        assertFalse(ai.readsVideo)
        assertEquals(AiOutcome.Failed("Text"), ai.extract("Text", VideoClip("/v.mp4", 1, "video/mp4")))
    }

    @Test
    fun withAVideoTheAiNamesTheSecondOfTheFinishedDish() {
        val body = GeminiRecipeRequest.body("Suppe", GeminiRecipeRequest.Media.Inline("video/mp4", "AAAA"))
        val schema = Json.parseToJsonElement(body).jsonObject["generation_config"]!!.jsonObject["response_schema"]!!.jsonObject
        assertEquals("NUMBER", schema["properties"]!!.jsonObject["dishSecond"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(schema["required"]!!.jsonArray.any { it.jsonPrimitive.content == "dishSecond" }, "optional")
        assertTrue(system(body).contains("dishSecond"))
        assertFalse(system(GeminiRecipeRequest.body("Suppe")).contains("dishSecond"), "only asked with a video")
        val json = """{"title":"Suppe","ingredients":["1 l Brühe"],"steps":["Kochen"],"dishSecond":41.5}"""
        assertEquals(41.5, assertIs<AiOutcome.Ok>(GeminiRecipeRequest.parse(answer(json))).recipe.dishSecond)
    }
}
