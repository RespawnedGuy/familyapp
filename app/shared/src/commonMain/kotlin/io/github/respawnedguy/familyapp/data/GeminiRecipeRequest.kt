package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.AiRecipe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Requests and responses of the Gemini API (generateContent, Files API) for [RecipeAi], as plain
 * JSON so the shared code builds and checks them; the transport is HttpGeminiRecipeAi on Android
 * (ADR 0008). Video and caption go in one request, the answer must match [schema].
 */
object GeminiRecipeRequest {
    const val BASE = "https://generativelanguage.googleapis.com"

    /**
     * Google's alias for the newest Flash model: on the free tier, reads video, swapped by Google with
     * two weeks' notice. A fixed version would stop working when Google retires it.
     */
    const val MODEL = "gemini-flash-latest"
    const val GENERATE_PATH = "/v1beta/models/$MODEL:generateContent"
    const val UPLOAD_PATH = "/upload/v1beta/files"

    /** Google's limit for a request with inline data is 20 MB; base64 makes the video a third larger. */
    const val MAX_INLINE_BYTES = 14L * 1024 * 1024
    private const val MAX_OUTPUT_TOKENS = 8_192

    private val json = Json { ignoreUnknownKeys = true }

    /** OpenAPI subset as generateContent's response_schema expects it (upper-case type names). */
    private val schema = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("title") { put("type", "STRING") }
            putJsonObject("servings") { put("type", "NUMBER") }
            putJsonObject("totalMinutes") { put("type", "INTEGER") }
            listOf("ingredients", "steps").forEach { list ->
                putJsonObject(list) {
                    put("type", "ARRAY")
                    putJsonObject("items") { put("type", "STRING") }
                }
            }
        }
        putJsonArray("required") { add("title"); add("ingredients"); add("steps") }
    }

    /** Media part for a video, inline (base64) or as an uploaded file. */
    sealed interface Media {
        data class Inline(val mimeType: String, val base64: String) : Media
        data class Uploaded(val mimeType: String, val fileUri: String) : Media
    }

    fun body(text: String, media: Media? = null): String = buildJsonObject {
        putJsonObject("system_instruction") {
            putJsonArray("parts") {
                addJsonObject {
                    val video = if (media != null) " " + RecipeAiPrompt.VIDEO else ""
                    put("text", RecipeAiPrompt.INSTRUCTIONS + video)
                }
            }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    when (media) {
                        is Media.Inline -> addJsonObject {
                            putJsonObject("inline_data") { put("mime_type", media.mimeType); put("data", media.base64) }
                        }
                        is Media.Uploaded -> addJsonObject {
                            putJsonObject("file_data") {
                                put("mime_type", media.mimeType)
                                put("file_uri", media.fileUri)
                            }
                        }
                        null -> Unit
                    }
                    addJsonObject { put("text", RecipeAiPrompt.wrap(text, ClaudeRecipeRequest.MAX_INPUT_CHARS)) }
                }
            }
        }
        putJsonObject("generation_config") {
            put("response_mime_type", "application/json")
            put("response_schema", schema)
            put("max_output_tokens", MAX_OUTPUT_TOKENS)
        }
    }.toString()

    /** Body that starts a resumable upload (Files API); the size and type go in headers. */
    fun uploadStart(): String = buildJsonObject {
        putJsonObject("file") { put("display_name", "rezept-video") }
    }.toString()

    /** Name ("files/…"), URI and state of an uploaded file, from the upload or the status response. */
    data class UploadedFile(val name: String, val uri: String, val state: String?)

    fun parseFile(response: String): UploadedFile? {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
        val file = (root?.get("file") as? JsonObject) ?: root
        val name = file?.text("name")?.takeIf { FILE_NAME.matches(it) }
        val uri = file?.text("uri")
        return if (name != null && uri != null) UploadedFile(name, uri, file.text("state")) else null
    }

    /** The recipe from a 200 response, or why there is none. */
    fun parse(response: String): AiOutcome {
        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: return AiOutcome.Failed(ClaudeRecipeRequest.UNREADABLE)
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val text = parts.mapNotNull { it as? JsonObject }
            .filter { (it["thought"] as? JsonPrimitive)?.booleanOrNull != true }
            .joinToString("") { it.text("text").orEmpty() }
        val recipe = runCatching { json.decodeFromString(AiRecipe.serializer(), text) }.getOrNull()
        val finish = candidate?.text("finishReason")
        val blocked = (root["promptFeedback"] as? JsonObject)?.text("blockReason")
        return when {
            blocked != null || finish in REFUSED -> AiOutcome.Failed("Die KI hat den Beitrag abgelehnt.")
            finish == "MAX_TOKENS" -> AiOutcome.Failed("Die Antwort der KI war zu lang.")
            recipe == null -> AiOutcome.Failed(ClaudeRecipeRequest.UNREADABLE)
            else -> AiOutcome.Ok(recipe)
        }
    }

    /** Short German reason for an HTTP error of the Gemini API. A wrong key comes back as 400. */
    fun failure(status: Int, body: String?): AiOutcome.Failed {
        val error = body?.let { b ->
            runCatching { json.parseToJsonElement(b).jsonObject["error"] as? JsonObject }.getOrNull()
        }
        val message = error?.text("message").orEmpty()
        val code = error?.text("status") ?: (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return AiOutcome.Failed(
            when {
                status == HTTP_UNAUTHORIZED || message.contains("API key", ignoreCase = true) ->
                    "API-Schlüssel ungültig. Bitte in den Einstellungen prüfen."
                status == HTTP_FORBIDDEN -> "Der API-Schlüssel darf das nicht."
                status == HTTP_TOO_MANY ->
                    "Gratis-Kontingent bei Google aufgebraucht oder zu viele Anfragen. Später noch einmal."
                code == "FAILED_PRECONDITION" -> "Google bietet die kostenlose Stufe hier nicht an."
                status >= HTTP_SERVER -> "Die KI ist gerade nicht erreichbar."
                else -> "Die KI hat die Anfrage nicht angenommen ($status)."
            },
        )
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    /** Answers Google stops for its own rules (safety, copyright, blocklists). */
    private val REFUSED =
        setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "LANGUAGE", "OTHER")

    /** "files/abc-123": the only shape of name the app ever asks Google about again. */
    private val FILE_NAME = Regex("""^files/[a-z0-9-]{1,64}$""")

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_SERVER = 500
}
