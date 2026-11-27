package io.github.respawnedguy.familyapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.respawnedguy.familyapp.data.HttpInstagramImages
import io.github.respawnedguy.familyapp.data.RecipePhotos
import io.github.respawnedguy.familyapp.data.VideoClip
import io.github.respawnedguy.familyapp.domain.recipe.RecipePhoto
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Recipe pictures on Android (threat model TE-13): the cover is downloaded by [images], decoded
 * with a size check first (no decompression bomb), scaled to [RecipePhoto] limits and written as a
 * new JPEG into [photoDir], the same private folder as captured photos, so sync, backup and cleanup
 * treat it like any photo. A video frame comes from the clip the AI got anyway, never a new download.
 */
class AndroidRecipePhotos(
    private val photoDir: File,
    private val images: HttpInstagramImages = HttpInstagramImages(),
) : RecipePhotos {

    override fun fromImage(url: String): String? {
        val bytes = images.download(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val target = RecipePhoto.target(bounds.outWidth, bounds.outHeight)
        val bitmap = target?.let { (width, _) ->
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, width) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        return if (target != null && bitmap != null) store(bitmap, target.first, target.second) else null
    }

    override fun fromVideo(clip: VideoClip, atSecond: Double?): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(clip.path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val second = RecipePhoto.frameSecond(durationMs / MS_PER_SECOND, atSecond)
            val frame = retriever.getFrameAtTime(
                (second * US_PER_SECOND).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
            val target = frame?.let { RecipePhoto.target(it.width, it.height) }
            if (frame != null && target != null) store(frame, target.first, target.second) else null
        } catch (_: RuntimeException) {
            // MediaMetadataRetriever throws IllegalArgumentException/RuntimeException for unreadable files.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Scales [source] to exactly [width] × [height] and saves it as JPEG; the original bitmap is freed. */
    private fun store(source: Bitmap, width: Int, height: Int): String? {
        val scaled = if (source.width == width && source.height == height) source
        else Bitmap.createScaledBitmap(source, width, height, true)
        val file = File(photoDir.apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
        return try {
            val ok = file.outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, RecipePhoto.JPEG_QUALITY, it)
            }
            if (!ok) file.delete()
            file.absolutePath.takeIf { ok }
        } catch (_: IOException) {
            file.delete()
            null
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    /** Largest power of two that keeps the decoded picture at least as wide as the target. */
    private fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    private companion object {
        const val MS_PER_SECOND = 1000.0
        const val US_PER_SECOND = 1_000_000.0
    }
}
