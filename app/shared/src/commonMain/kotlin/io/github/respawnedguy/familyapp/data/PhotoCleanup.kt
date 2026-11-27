package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.domain.FamilyData

/**
 * File names of every photo the family data points to: captures and recipe pictures. Sync, backup
 * and cleanup all use this one list, so a new kind of picture cannot be forgotten in one of them.
 */
fun usedPhotoNames(data: FamilyData): Set<String> =
    usedPhotoPaths(data).map(FamilyRepository::photoName).toSet()

/** Paths of every photo the family data points to (deleted recipes are emptied and have none). */
fun usedPhotoPaths(data: FamilyData): List<String> =
    data.items.mapNotNull { it.photoPath } + data.recipes.filter { !it.deleted }.mapNotNull { it.photoPath }

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
        val used = usedPhotoNames(data)
        return files.filter { FamilyRepository.photoName(it.path) !in used && nowMs - it.modifiedMs >= minAgeMs }.map { it.path }
    }
}
