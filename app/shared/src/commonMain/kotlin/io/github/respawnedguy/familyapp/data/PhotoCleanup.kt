package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.FamilyData

/** A photo file in the app's private photo folder. */
data class StoredPhoto(val path: String, val modifiedMs: Long)

/**
 * Finds photo files no item points to any more (deleted items, cancelled captures). Runs at app
 * start, so the undo after deleting still has its photo during the session. Files younger than
 * [minAgeMs] are kept because a capture may be just saving them (P0-4 of the quality plan).
 */
object PhotoCleanup {
    const val MIN_AGE_MS = 60 * 60 * 1000L

    fun orphans(files: List<StoredPhoto>, data: FamilyData, nowMs: Long, minAgeMs: Long = MIN_AGE_MS): List<String> {
        val used = data.items.mapNotNull { it.photoPath?.let(FamilyRepository::photoName) }.toSet()
        return files.filter { FamilyRepository.photoName(it.path) !in used && nowMs - it.modifiedMs >= minAgeMs }.map { it.path }
    }
}
