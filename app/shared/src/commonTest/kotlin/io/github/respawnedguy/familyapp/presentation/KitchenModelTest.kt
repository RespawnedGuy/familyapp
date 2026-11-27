package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.data.AiOutcome
import io.github.respawnedguy.familyapp.data.InstagramPages
import io.github.respawnedguy.familyapp.data.InstagramVideos
import io.github.respawnedguy.familyapp.data.VideoClip
import io.github.respawnedguy.familyapp.data.RecipeAi
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.contacts.ContactDraft
import io.github.respawnedguy.familyapp.domain.contacts.ContactSource
import io.github.respawnedguy.familyapp.domain.contacts.EntryKind
import io.github.respawnedguy.familyapp.domain.recipe.AiRecipe
import io.github.respawnedguy.familyapp.domain.recipe.RecipeImport
import io.github.respawnedguy.familyapp.domain.recipe.RecipeStatus
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class KitchenPlatform(
    private val ai: RecipeAi? = null,
    override val instagramVideos: InstagramVideos? = null,
    override val instagramPages: InstagramPages? = null,
) : PlatformServices {
    val screenOn = mutableListOf<Boolean>()
    val aiKeys = mutableListOf<String>()
    override fun recipeAi(apiKey: String): RecipeAi? { aiKeys += apiKey; return ai }
    override val speech: SpeechInput? = null
    override fun elapsedMs() = 0L
    override fun reducedMotion() = false
    override fun takePhoto(onResult: (String?) -> Unit) = onResult(null)
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun writeBackup(folder: String, backup: BackupContent): String? = null
    override fun readBackup(folder: String, code: String?): RestoreRead = RestoreRead.Failed("leer")
    override fun newRecoveryCode() = "ABCD-EFGH-JKMN-PQRS-TVWX"
    override fun requestNotificationPermission() = Unit
    override fun describeFolder(folder: String) = folder
    override fun keepScreenOn(on: Boolean) { screenOn += on }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KitchenModelTest {
    private val friday = LocalDate(2026, 11, 20)
    private var ids = 0

    private fun TestScope.model(platform: KitchenPlatform = KitchenPlatform()): AppModel {
        val repo = FamilyRepository(
            InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler),
            { testScheduler.currentTime }, { friday }, { "id${ids++}" },
        )
        return AppModel(repo, platform, backgroundScope, { friday }, UnconfinedTestDispatcher(testScheduler))
            .apply { createFamily("Nils") }
    }

    private val caption = "Ofengemüse für 2 Personen\nZutaten:\n500 g Kartoffeln\n2 Paprika\n2 EL Öl\n" +
        "Zubereitung:\n1. Gemüse schneiden.\n2. 30 Minuten backen."

    @Test
    fun sharedRecipeBecomesSuggestionNotInboxCard() = runTest {
        val m = model()
        m.captureShared(caption, null)
        assertTrue(m.repo.current.items.isEmpty(), "no inbox card for a recipe")
        val recipe = m.repo.current.recipes.single()
        assertEquals(RecipeStatus.SUGGESTED, recipe.status)
        assertEquals(RecipeActions.SHARED_RECIPE, m.feedback.value?.message)
        // Undo on the feedback bar takes it back.
        m.undo(m.feedback.value!!)
        assertTrue(m.repo.current.recipes.single().deleted)
    }

    @Test
    fun sharedInstagramLinkWaitsForText() = runTest {
        val m = model()
        m.captureShared("https://www.instagram.com/reel/XYZ/?igsh=abc", null)
        val recipe = m.repo.current.recipes.single()
        assertTrue(recipe.ingredients.isEmpty())
        assertEquals(RecipeActions.SHARED_LINK_ONLY, m.feedback.value?.message)
        m.kitchen.recipes.fillFromText(recipe.id, caption)
        val filled = m.repo.recipeStore.recipe(recipe.id)!!
        assertEquals("Ofengemüse für 2 Personen", filled.title)
        assertEquals(3, filled.ingredients.size)
        assertEquals("https://www.instagram.com/reel/XYZ/", filled.sourceUrl)
        assertEquals(RecipeStatus.CONFIRMED, filled.status)
    }

    /** An embed page as Instagram serves it for an image post (structure only, invented text). */
    private fun embedPage(text: String) =
        "<div class=\"Caption\"><a class=\"CaptionUsername\" href=\"#\">koch.beispiel</a><br><br>" +
            text.replace("\n", "<br>") + "<div class=\"CaptionComments\"></div></div>"

    @Test
    fun sharedInstagramLinkGetsItsCaptionAndStaysASuggestion() = runTest {
        val asked = mutableListOf<String>()
        val m = model(KitchenPlatform { path -> asked += path; embedPage(caption) })
        m.captureShared("https://www.instagram.com/reel/XYZ/?igsh=abc", null)
        assertEquals(RecipeActions.FETCHING_CAPTION, m.feedback.value?.message)
        runCurrent()
        assertEquals(listOf("/p/XYZ/embed/captioned/"), asked)
        val recipe = m.repo.current.recipes.single()
        assertEquals("Ofengemüse für 2 Personen", recipe.title)
        assertEquals(3, recipe.ingredients.size)
        assertEquals(2, recipe.steps.size)
        assertEquals(RecipeStatus.SUGGESTED, recipe.status, "a person still confirms (HITL)")
        assertEquals("https://www.instagram.com/reel/XYZ/", recipe.sourceUrl)
        assertEquals(caption, recipe.sourceText)
        assertEquals(RecipeActions.CAPTION_FETCHED, m.feedback.value?.message)
        // Undo takes back only the fetched text; the link-only suggestion stays.
        m.undo(m.feedback.value!!)
        val back = m.repo.recipeStore.recipe(recipe.id)!!
        assertTrue(back.ingredients.isEmpty())
        assertEquals(RecipeImport.INSTAGRAM_TITLE, back.title)
    }

    @Test
    fun unreachableInstagramLeavesTheLinkAndSaysSo() = runTest {
        val m = model(KitchenPlatform { null })
        m.captureShared("https://www.instagram.com/p/XYZ/", null)
        runCurrent()
        val recipe = m.repo.current.recipes.single()
        assertTrue(recipe.ingredients.isEmpty())
        assertEquals(RecipeImport.INSTAGRAM_TITLE, recipe.title)
        assertEquals(RecipeActions.CAPTION_UNAVAILABLE, m.feedback.value?.message)
    }

    @Test
    fun captionWithoutIngredientsAsksForThem() = runTest {
        val m = model(KitchenPlatform { embedPage("Unser liebstes Abendessen! Rezept im Video \uD83D\uDE0D") })
        m.captureShared("https://www.instagram.com/p/XYZ/", null)
        runCurrent()
        val recipe = m.repo.current.recipes.single()
        assertEquals(RecipeStatus.SUGGESTED, recipe.status)
        assertEquals(RecipeActions.CAPTION_WITHOUT_INGREDIENTS, m.feedback.value?.message)
        assertTrue(recipe.sourceText!!.startsWith("Unser liebstes Abendessen"))
    }

    @Test
    fun recipeChangedWhileFetchingIsNotOverwritten() = runTest {
        lateinit var m: AppModel
        m = model(
            KitchenPlatform {
                // The person edits the suggestion before Instagram answers.
                val id = m.repo.current.recipes.single().id
                m.kitchen.recipes.fillFromText(id, "Eigenes Rezept\nZutaten:\n1 Ei\n2 Brötchen\nZubereitung:\n1. Braten.")
                embedPage(caption)
            },
        )
        m.captureShared("https://www.instagram.com/p/XYZ/", null)
        runCurrent()
        assertEquals("Eigenes Rezept", m.repo.current.recipes.single().title)
    }

    private val videoCaption = "Unser liebstes Abendessen! Alles steht im Video \uD83D\uDE0D"

    @Test
    fun cloudAiReadsWhatTheRulesCannotAndItStaysASuggestion() = runTest {
        val asked = mutableListOf<String>()
        lateinit var m: AppModel
        var messageWhileAsking: String? = null
        val ai = RecipeAi { text ->
            asked += text
            messageWhileAsking = m.feedback.value?.message
            AiOutcome.Ok(AiRecipe("Abendessen", 2.0, null, listOf("2 Eier", "1 Brötchen"), listOf("Braten")))
        }
        val platform = KitchenPlatform(ai) { embedPage(videoCaption) }
        m = model(platform)
        // KT-4: the family has contacts and portfolio facts; none of it may reach the AI.
        val mia = m.repo.addMember("Mia", MemberKind.CHILD)
        m.repo.contactStore.add(listOf(ContactDraft("Dr. Lindner", phones = listOf("0351 123456"))), ContactSource.MANUAL, mia.id)
        m.repo.portfolioStore.save(mia.id, null, EntryKind.SIZE, "Schuhgröße", "31")
        m.setRecipeAiKey("  sk-test  ")
        m.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        runCurrent()
        assertEquals(RecipeActions.ASKING_AI, messageWhileAsking)
        assertEquals(listOf("sk-test"), platform.aiKeys)
        assertEquals(listOf(videoCaption), asked, "only the public caption goes to the AI, no contact or fact (KT-4)")
        val recipe = m.repo.current.recipes.single()
        assertEquals("Abendessen", recipe.title)
        assertEquals(2, recipe.ingredients.size)
        assertEquals(RecipeStatus.SUGGESTED, recipe.status)
        assertEquals(videoCaption, recipe.sourceText)
        assertEquals(RecipeActions.AI_FILLED, m.feedback.value?.message)
        // Undo takes back only the AI's part: the caption stays.
        m.undo(m.feedback.value!!)
        assertTrue(m.repo.current.recipes.single().ingredients.isEmpty())
        assertEquals(videoCaption, m.repo.current.recipes.single().sourceText)
    }

    @Test
    fun cloudAiFailureAndEmptyAnswerAreSaid() = runTest {
        val m = model(KitchenPlatform({ AiOutcome.Failed("Kein Guthaben beim KI-Anbieter.") }) { embedPage(videoCaption) })
        m.setRecipeAiKey("sk-test")
        m.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        runCurrent()
        assertEquals("${RecipeActions.AI_FAILED} Kein Guthaben beim KI-Anbieter.", m.feedback.value?.message)

        val n = model(KitchenPlatform({ AiOutcome.Ok(AiRecipe("Nichts")) }) { embedPage(videoCaption) })
        n.setRecipeAiKey("sk-test")
        n.captureShared("https://www.instagram.com/p/ABC/", null)
        runCurrent()
        runCurrent()
        assertEquals(RecipeActions.AI_FOUND_NOTHING, n.feedback.value?.message)
        assertTrue(n.repo.current.recipes.single().ingredients.isEmpty())
    }

    /** Embed page of a reel: the media JSON with caption and video address, double encoded like Instagram's. */
    private fun reelPage(text: String, videoUrl: String): String {
        val media = buildJsonObject {
            putJsonObject("gql_data") {
                putJsonObject("shortcode_media") {
                    put("is_video", true)
                    put("video_url", videoUrl)
                    putJsonObject("edge_media_to_caption") {
                        putJsonArray("edges") { addJsonObject { putJsonObject("node") { put("text", text) } } }
                    }
                }
            }
        }
        return "<script>{\"contextJSON\":${Json.encodeToString(String.serializer(), media.toString())}}</script>"
    }

    private class FakeVideos(private val clip: VideoClip?) : InstagramVideos {
        val downloaded = mutableListOf<String>()
        val discarded = mutableListOf<VideoClip>()
        override fun download(url: String): VideoClip? { downloaded += url; return clip }
        override fun discard(clip: VideoClip) { discarded += clip }
    }

    private class VideoAi(private val answer: AiOutcome, private val onAsk: () -> Unit = {}) : RecipeAi {
        val withVideo = mutableListOf<Pair<String, VideoClip>>()
        val textOnly = mutableListOf<String>()
        override val readsVideo = true
        override fun extract(text: String): AiOutcome { textOnly += text; return answer }
        override fun extract(text: String, video: VideoClip): AiOutcome {
            onAsk()
            withVideo += text to video
            return answer
        }
    }

    private val cdnVideo = "https://scontent-fra5-1.cdninstagram.com/o1/v/t16/f2/m86/clip.mp4?efg=abc&oh=sig"

    @Test
    fun geminiWatchesTheReelAndTheVideoIsDeletedAfterwards() = runTest {
        val clip = VideoClip("/cache/instagram-video/v.mp4", 1_000, "video/mp4")
        val videos = FakeVideos(clip)
        lateinit var m: AppModel
        var messageWhileAsking: String? = null
        val recipe = AiRecipe("Shakshuka", 2.0, 20, listOf("4 Eier", "1 Dose Tomaten"), listOf("Kochen"))
        val ai = VideoAi(AiOutcome.Ok(recipe)) { messageWhileAsking = m.feedback.value?.message }
        m = model(KitchenPlatform(ai, videos) { reelPage(videoCaption, cdnVideo) })
        m.setRecipeAiKey("AIzaTest")
        m.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        runCurrent()
        assertEquals(RecipeActions.ASKING_AI_VIDEO, messageWhileAsking)
        assertEquals(listOf(cdnVideo), videos.downloaded, "the address comes from Instagram's page, not from the link")
        assertEquals(listOf(videoCaption to clip), ai.withVideo)
        assertEquals(listOf(clip), videos.discarded, "TE-12: the video does not stay on the phone")
        val saved = m.repo.current.recipes.single()
        assertEquals("Shakshuka", saved.title)
        assertEquals(RecipeStatus.SUGGESTED, saved.status)
        assertEquals(RecipeActions.AI_FILLED, m.feedback.value?.message)
    }

    @Test
    fun withoutTheVideoFileTheAiStillReadsTheCaption() = runTest {
        val videos = FakeVideos(null)
        val ai = VideoAi(AiOutcome.Ok(AiRecipe("Nichts")))
        val m = model(KitchenPlatform(ai, videos) { reelPage(videoCaption, cdnVideo) })
        m.setRecipeAiKey("AIzaTest")
        m.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        runCurrent()
        assertEquals(listOf(videoCaption), ai.textOnly)
        assertTrue(videos.discarded.isEmpty())
        assertEquals(RecipeActions.AI_FOUND_NOTHING_VIDEO, m.feedback.value?.message)
    }

    @Test
    fun nothingInTheVideoEitherIsSaidAndTextOnlyAisNeverGetTheVideo() = runTest {
        val videos = FakeVideos(VideoClip("/c/v.mp4", 1, "video/mp4"))
        val m = model(KitchenPlatform(VideoAi(AiOutcome.Ok(AiRecipe("Nichts"))), videos) { reelPage(videoCaption, cdnVideo) })
        m.setRecipeAiKey("AIzaTest")
        m.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        runCurrent()
        assertEquals(RecipeActions.AI_FOUND_NOTHING_IN_VIDEO, m.feedback.value?.message)

        // Claude reads text only: no download at all.
        val other = FakeVideos(VideoClip("/c/v.mp4", 1, "video/mp4"))
        val n = model(KitchenPlatform({ AiOutcome.Ok(AiRecipe("Nichts")) }, other) { reelPage(videoCaption, cdnVideo) })
        n.setRecipeAiKey("sk-ant-test")
        n.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        runCurrent()
        assertTrue(other.downloaded.isEmpty())
        assertEquals(RecipeActions.AI_FOUND_NOTHING_VIDEO, n.feedback.value?.message)
    }

    @Test
    fun cloudAiStaysUnusedWithoutKeyOrWhenRulesSucceed() = runTest {
        val platform = KitchenPlatform({ error("must not be called") }) { embedPage(videoCaption) }
        val m = model(platform)
        m.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        assertTrue(platform.aiKeys.isEmpty(), "no key, no AI")
        assertEquals(RecipeActions.CAPTION_WITHOUT_INGREDIENTS, m.feedback.value?.message)

        val withKey = KitchenPlatform({ error("must not be called") }) { embedPage(caption) }
        val n = model(withKey)
        n.setRecipeAiKey("sk-test")
        n.captureShared("https://www.instagram.com/reel/XYZ/", null)
        runCurrent()
        assertTrue(withKey.aiKeys.isEmpty(), "the rules found the recipe, no AI needed")
        assertEquals(RecipeActions.CAPTION_FETCHED, n.feedback.value?.message)
        n.setRecipeAiKey(" ")
        assertEquals(null, n.repo.current.settings.recipeAiKey)
    }

    @Test
    fun otherSharedLinksFetchNothing() = runTest {
        val asked = mutableListOf<String>()
        val m = model(KitchenPlatform { path -> asked += path; null })
        m.captureShared(caption + "\nhttps://www.instagram.com/p/XYZ/", null)
        m.captureShared("https://example.org/rezept", null)
        runCurrent()
        assertTrue(asked.isEmpty(), "a full caption or another site needs no fetch: $asked")
    }

    @Test
    fun otherSharedTextStaysACapture() = runTest {
        val m = model()
        m.captureShared("Elternabend am Donnerstag", null)
        assertEquals(1, m.repo.current.items.size)
        assertTrue(m.repo.current.recipes.isEmpty())
    }

    @Test
    fun recipeTextWithPhotoStaysACapture() = runTest {
        val m = model()
        m.captureShared(caption, "/photos/x.jpg")
        assertEquals(1, m.repo.current.items.size)
    }

    @Test
    fun cookingKeepsScreenOnOnlyWhileOpen() = runTest {
        val platform = KitchenPlatform()
        val m = model(platform)
        m.kitchen.recipes.createFromText(caption)
        val id = m.repo.current.recipes.single().id
        assertEquals(KitchenRoute.Detail(id), m.kitchen.route.value)
        m.kitchen.setServings(4)
        m.kitchen.startCooking(id)
        assertEquals(KitchenRoute.Cook(id, 4), m.kitchen.route.value)
        assertTrue(m.handleBack())
        assertEquals(KitchenRoute.Detail(id), m.kitchen.route.value)
        m.kitchen.startCooking(id)
        m.kitchen.recipes.cooked(id)
        assertEquals(listOf(true, false, true, false), platform.screenOn)
        assertEquals(friday, m.repo.recipeStore.recipe(id)?.cookedOn)
    }

    @Test
    fun backWalksOutOfTheKitchenAndTabsClose() = runTest {
        val m = model()
        m.select(Tab.FAMILY)
        m.kitchen.openLibrary()
        m.kitchen.newRecipe()
        assertTrue(m.handleBack())
        assertEquals(KitchenRoute.Library, m.kitchen.route.value)
        assertTrue(m.handleBack())
        assertNull(m.kitchen.route.value)
        assertEquals(Tab.FAMILY, m.tab.value)
        m.kitchen.openShopping()
        m.select(Tab.TODAY)
        assertNull(m.kitchen.route.value)
        m.kitchen.openShopping()
        m.openSettings()
        assertNull(m.kitchen.route.value)
    }

    @Test
    fun shoppingFromRecipeUsesChosenServingsAndAsksForBasics() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText(caption)
        val recipe = m.repo.current.recipes.single()
        m.kitchen.setServings(4)
        m.kitchen.shopping.addRecipe(recipe)
        val view = ShoppingList.view(m.repo.current.shopping)
        assertEquals("1 kg", view.open.single { it.name == "Kartoffeln" }.amounts)
        assertEquals(listOf("Öl"), view.ask.map { it.name })
        assertEquals("2 Zutaten auf der Einkaufsliste, 1 als Frage „Hast du noch …?“.", m.feedback.value?.message)
        m.kitchen.shopping.toggle(view.open.first())
        assertEquals(1, ShoppingList.view(m.repo.current.shopping).checked.size)
        m.kitchen.shopping.clearChecked()
        assertTrue(ShoppingList.view(m.repo.current.shopping).checked.isEmpty())
    }

    @Test
    fun servingsStayInBounds() = runTest {
        val m = model()
        m.kitchen.setServings(0)
        assertEquals(1, m.kitchen.servings.value)
        m.kitchen.setServings(1000)
        assertEquals(40, m.kitchen.servings.value)
    }

    @Test
    fun emptyTextCreatesNothingAndUnknownRecipeIsIgnored() = runTest {
        val m = model()
        m.kitchen.recipes.createFromText("   ")
        assertTrue(m.repo.current.recipes.isEmpty())
        m.kitchen.openRecipe("nope")
        assertNull(m.kitchen.route.value)
        assertFalse(m.kitchen.back())
        m.kitchen.recipes.createFromText("Nur ein Gedanke ohne Zutaten")
        assertEquals(RecipeActions.NO_INGREDIENTS_FOUND, m.feedback.value?.message)
        val r = m.repo.current.recipes.single()
        m.kitchen.recipes.delete(r)
        assertEquals(KitchenRoute.Library, m.kitchen.route.value)
        assertIs<String>(m.feedback.value?.message)
    }
}
