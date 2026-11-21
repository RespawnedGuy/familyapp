package io.github.respawnedguy.familyapp.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes a stored photo, scaled down to at most [maxSizePx] on the longer side. Platform code. */
fun interface PhotoLoader {
    fun load(path: String, maxSizePx: Int): ImageBitmap?
}
