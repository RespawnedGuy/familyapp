package io.github.respawnedguy.familyapp.domain.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Threat model TE-13: how big recipe pictures are stored and where a video frame is taken. */
class RecipePhotoTest {

    @Test
    fun reelCoverIsShrunkToTheLimits() {
        // Instagram's reel covers are 1080 × 1920 (9:16): the short side decides.
        assertEquals(900 to 1600, RecipePhoto.target(1080, 1920))
        assertEquals(1600 to 900, RecipePhoto.target(1920, 1080))
        // Square post: 1080 → 900.
        assertEquals(900 to 900, RecipePhoto.target(1080, 1080))
        // Very long strip: the long side decides.
        assertEquals(400 to 1600, RecipePhoto.target(1000, 4000))
    }

    @Test
    fun smallPicturesAreNeverEnlarged() {
        assertEquals(640 to 640, RecipePhoto.target(640, 640))
        assertEquals(1 to 1, RecipePhoto.target(1, 1))
    }

    @Test
    fun nonsenseAndBombSizesAreRefused() {
        assertNull(RecipePhoto.target(0, 100))
        assertNull(RecipePhoto.target(-1, 100))
        assertNull(RecipePhoto.target(RecipePhoto.MAX_SOURCE_PX + 1, 100))
        assertNull(RecipePhoto.target(100, 50_000))
        assertEquals(900 to 900, RecipePhoto.target(RecipePhoto.MAX_SOURCE_PX, RecipePhoto.MAX_SOURCE_PX))
    }

    @Test
    fun frameAtTheAisSecondElseTwoThirdsIn() {
        assertEquals(41.5, RecipePhoto.frameSecond(60.0, 41.5))
        assertEquals(40.0, RecipePhoto.frameSecond(60.0, null))
        assertEquals(40.0, RecipePhoto.frameSecond(60.0, 75.0), "a second beyond the end is ignored")
        assertEquals(40.0, RecipePhoto.frameSecond(60.0, -3.0))
        assertEquals(0.0, RecipePhoto.frameSecond(0.0, 12.0), "unknown length: first frame")
    }
}
