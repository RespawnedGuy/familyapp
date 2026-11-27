package io.github.respawnedguy.familyapp.domain.recipe

/**
 * How big a recipe picture is stored, whatever its source (reel cover, video frame). Small enough
 * that a library of a hundred recipes stays a few dozen MB on every phone and in every sync, large
 * enough for a full-width header on a phone: the short side at most [MAX_SHORT_PX], the long side at
 * most [MAX_LONG_PX], re-encoded as JPEG (which also drops any metadata of the original, TE-13).
 */
object RecipePhoto {
    const val MAX_SHORT_PX = 900
    const val MAX_LONG_PX = 1600
    const val JPEG_QUALITY = 80

    /** A picture larger than this (either side) is refused before decoding (decompression bomb, TE-13). */
    const val MAX_SOURCE_PX = 8_000

    /** Downloaded picture files above this are refused; Instagram's covers are well below 1 MB. */
    const val MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024

    /** Target size for a picture of [width] × [height], never enlarged; null for nonsense sizes. */
    fun target(width: Int, height: Int): Pair<Int, Int>? {
        if (minOf(width, height) <= 0 || maxOf(width, height) > MAX_SOURCE_PX) return null
        val short = minOf(width, height).toDouble()
        val long = maxOf(width, height).toDouble()
        val factor = minOf(1.0, MAX_SHORT_PX / short, MAX_LONG_PX / long)
        return maxOf(1, (width * factor).toInt()) to maxOf(1, (height * factor).toInt())
    }

    /**
     * Where to take a frame from a video of [durationSeconds] when no cover could be loaded: the
     * second the AI named for the finished dish if it lies inside the video, else two thirds in
     * (recipe reels usually end on the plated dish, the last seconds often on a call to follow).
     */
    fun frameSecond(durationSeconds: Double, aiSecond: Double?): Double {
        if (durationSeconds <= 0.0) return 0.0
        return aiSecond?.takeIf { it >= 0.0 && it < durationSeconds } ?: (durationSeconds * FRAME_SHARE)
    }

    private const val FRAME_SHARE = 2.0 / 3.0
}
