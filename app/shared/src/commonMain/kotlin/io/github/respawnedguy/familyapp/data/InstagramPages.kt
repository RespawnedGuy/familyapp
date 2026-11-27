package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.recipe.InstagramLink
import io.github.respawnedguy.familyapp.domain.recipe.InstagramPage
import io.github.respawnedguy.familyapp.domain.recipe.InstagramPost

/**
 * Loads a page from www.instagram.com, without login and without cookies. Takes only a path that
 * [InstagramLink] built from a shortcode, so the host is fixed (threat model TE-9). Blocking: call it
 * off the main thread. Null for anything but a normal page: error, login redirect, timeout, too big.
 */
fun interface InstagramPages {
    fun get(path: String): String?
}

/**
 * The caption of a shared Instagram post, fetched on the phone, without a server and without
 * yt-dlp (ADR 0007). Embed page first, the normal post page as a second chance. The result is
 * still only text for the rules; the recipe stays a suggestion until a person confirms it.
 */
class InstagramImport(
    private val pages: InstagramPages,
    private val videos: InstagramVideos? = null,
    /** Second way to the same pages, e.g. a browser engine, when Instagram refuses the plain fetch. */
    private val browser: InstagramPages? = null,
    /** Stores the post's cover or a video frame as the recipe's picture; null: recipes stay without. */
    private val photos: RecipePhotos? = null,
) {
    /** True where this platform can give recipes a picture. */
    val makesPhotos: Boolean get() = photos != null


    fun fetch(shortcode: String): InstagramPost? =
        fetch(pages, shortcode) ?: browser?.let { fetch(it, shortcode) }

    private fun fetch(source: InstagramPages, shortcode: String): InstagramPost? =
        source.get(InstagramLink.embedPath(shortcode))?.let(InstagramPage::fromEmbed)
            ?: source.get(InstagramLink.postPath(shortcode))?.let(InstagramPage::fromPostPage)

    /** Caption and cover of a post; the cover is optional and never holds up the text. Blocking. */
    fun fetchWithPhoto(shortcode: String): Pair<InstagramPost?, String?> {
        val post = runCatching { fetch(shortcode) }.getOrNull()
        return post to post?.let(::photo)
    }

    /** The post's cover as a stored, shrunk picture (TE-13); null without cover or on any failure. */
    fun photo(post: InstagramPost): String? {
        val url = post.imageUrl ?: return null
        return photos?.let { runCatching { it.fromImage(url) }.getOrNull() }
    }

    /** The video address [cloud] gets to see, or null when it reads only the caption (ADR 0008). */
    fun videoFor(post: InstagramPost, cloud: RecipeAi): String? =
        post.videoUrl?.takeIf { cloud.readsVideo && videos != null }

    /**
     * Asks [cloud] about the caption and, if [videoUrl] is set and the download works, the video,
     * which is deleted again right afterwards (threat model TE-12). With [wantFrame] (the recipe has
     * no picture yet) a frame of that video becomes its picture before the file goes, at the second
     * the AI named for the finished dish. Blocking: off the main thread.
     */
    fun ask(cloud: RecipeAi, caption: String, videoUrl: String?, wantFrame: Boolean = false): AiAnswer {
        val source = videos
        val clip = if (videoUrl != null && source != null) source.download(videoUrl) else null
        if (clip == null || source == null) return AiAnswer(cloud.extract(caption), sawVideo = false)
        return try {
            val outcome = cloud.extract(caption, clip)
            val second = (outcome as? AiOutcome.Ok)?.recipe?.dishSecond
            val frame = if (wantFrame) photos?.let { runCatching { it.fromVideo(clip, second) }.getOrNull() } else null
            AiAnswer(outcome, sawVideo = true, photoPath = frame)
        } finally {
            source.discard(clip)
        }
    }
}

/** What [InstagramImport.ask] brings back: the AI's reading, whether it saw the video, maybe a frame. */
data class AiAnswer(val outcome: AiOutcome, val sawVideo: Boolean, val photoPath: String? = null)
