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
class InstagramImport(private val pages: InstagramPages, private val videos: InstagramVideos? = null) {

    fun fetch(shortcode: String): InstagramPost? =
        pages.get(InstagramLink.embedPath(shortcode))?.let(InstagramPage::fromEmbed)
            ?: pages.get(InstagramLink.postPath(shortcode))?.let(InstagramPage::fromPostPage)

    /** The video address [cloud] gets to see, or null when it reads only the caption (ADR 0008). */
    fun videoFor(post: InstagramPost, cloud: RecipeAi): String? =
        post.videoUrl?.takeIf { cloud.readsVideo && videos != null }

    /**
     * Asks [cloud] about the caption and, if [videoUrl] is set and the download works, the video,
     * which is deleted again right afterwards (threat model TE-12). Second value: the AI saw the video.
     * Blocking: off the main thread.
     */
    fun ask(cloud: RecipeAi, caption: String, videoUrl: String?): Pair<AiOutcome, Boolean> {
        val source = videos
        val clip = if (videoUrl != null && source != null) source.download(videoUrl) else null
        if (clip == null || source == null) return cloud.extract(caption) to false
        return try {
            cloud.extract(caption, clip) to true
        } finally {
            source.discard(clip)
        }
    }
}
