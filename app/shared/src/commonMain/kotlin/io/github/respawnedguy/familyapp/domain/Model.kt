package io.github.respawnedguy.familyapp.domain

import io.github.respawnedguy.familyapp.domain.recipe.Recipe
import io.github.respawnedguy.familyapp.domain.recipe.ShoppingEntry
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Index into the fixed person palette (design D6). Never derived from the system theme. */
const val PERSON_COLOR_COUNT = 4

@Serializable
enum class MemberKind { ADULT, CHILD }

/**
 * A family member. Created in the GUI only, never hard-coded.
 * [hasDevice] is false for people without their own phone (child, grandparent).
 */
@Serializable
data class Member(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val kind: MemberKind = MemberKind.ADULT,
    val hasDevice: Boolean = false,
    val stamp: Stamp,
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Serializable
enum class Source { VOICE, PHOTO, TEXT, SHARE }

@Serializable
enum class ItemStatus {
    /** Waiting for a human decision (HITL). */
    INBOX,

    /** Confirmed: has someone who takes care of it and a decided date (or explicitly none). */
    OPEN,
    DONE,
    IRRELEVANT,
}

/** Who set a field: a deterministic rule (shown as suggestion with reason) or a person. */
@Serializable
enum class Origin { RULE, PERSON }

@Serializable
data class Item(
    val id: String,
    val title: String,
    val source: Source,
    val createdMs: Long,
    val createdBy: String,
    val photoPath: String? = null,
    val status: ItemStatus = ItemStatus.INBOX,
    /** Whom it concerns ("betrifft"), e.g. a child. Optional. */
    val concerns: String? = null,
    val concernsOrigin: Origin? = null,
    /** Who takes care of it ("kümmert sich"). */
    val assignee: String? = null,
    val assigneeOrigin: Origin? = null,
    val due: LocalDate? = null,
    /** True once a date was chosen or "Kein Datum" was picked; a rule suggestion does not count. */
    val dueDecided: Boolean = false,
    val dueOrigin: Origin? = null,
    /** Why the rules suggested what they suggested; shown small on the card. */
    val reasons: List<String> = emptyList(),
    val doneMs: Long? = null,
    /** Local day of completion, for "diese Woche erledigt". */
    val doneOn: LocalDate? = null,
    /** Taps spent on this card while it was in the inbox (local metric, design doc "Prüfung" 4). */
    val inboxTaps: Int = 0,
    /** Member who marked it done. Transparency: nothing gets done secretly (KONZEPT, Phase 5). */
    val doneBy: String? = null,
    /** Member who made the last change (ISO 25010 accountability, QA-6). Null for data from schema 1. */
    val changedBy: String? = null,
    val stamp: Stamp,
) {
    val hasFixedDate: Boolean get() = due != null
}

@Serializable
enum class CaptureAction { VOICE, PHOTO, TEXT }

/**
 * One local measurement in milliseconds (P1-10). [trigger] says where it started (Knopf, Kachel,
 * Verknüpfung, Symbol). Samples from before P1-10 have no [metric] and were all [Metric.CAPTURE_READY].
 */
@Serializable
data class LatencySample(val trigger: String, val ms: Long, val atMs: Long, val metric: Metric = Metric.CAPTURE_READY)

@Serializable
data class Settings(
    val firstCaptureAction: CaptureAction = CaptureAction.VOICE,
    /** Off by default: speech stays on the device; online recognition (Google) only if switched on. */
    val allowOnlineSpeech: Boolean = false,
    val backupFolder: String? = null,
    /**
     * Recovery code that encrypts the backup (P0-3, design D7). Created when a backup folder is
     * chosen; without it a backup cannot be opened. Lives only in private app storage and on paper.
     */
    val backupCode: String? = null,
    val lastBackupMs: Long? = null,
    val lastBackupError: String? = null,
    val latency: List<LatencySample> = emptyList(),
    /** Reminders and staged escalation for deadlines (P1-1). On by default, switchable in settings. */
    val remindersOn: Boolean = true,
    /** Until when reminders were already handled (epoch ms), so none is shown twice. */
    val remindersCheckedMs: Long? = null,
    /** The app asked once for the notification permission (Android 13+); never nag again. */
    val notificationsAsked: Boolean = false,
    /** Number of items when the "only on this phone" hint was put aside (P1-11); null = never. */
    val localOnlyHintDismissedAt: Int? = null,
)

@Serializable
enum class RecordKind { ITEM, MEMBER }

/**
 * Marks a deleted record (ADR 0002: tombstones). Without it, sync would bring a deleted item back
 * from a device that still has it. Holds no content: deleted means the text is gone.
 */
@Serializable
data class Tombstone(val id: String, val kind: RecordKind, val stamp: Stamp, val deletedBy: String? = null)

/** Current version of the stored format. Older files are upgraded by [io.github.respawnedguy.familyapp.data.FamilyJson]. */
const val CURRENT_SCHEMA = 2

/**
 * Everything stored on this device. Sync (ADR 0003) exchanges members, items, tombstones, recipes
 * and shopping entries by [Stamp]. Recipes and shopping entries came later (kitchen library,
 * 26.11.2026) as additional fields: older app versions ignore them, so the schema stays 2 and
 * devices with different versions keep syncing everything else.
 */
@Serializable
data class FamilyData(
    val schema: Int = CURRENT_SCHEMA,
    val deviceId: String,
    val selfId: String? = null,
    val members: List<Member> = emptyList(),
    val items: List<Item> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
    val settings: Settings = Settings(),
    val recipes: List<Recipe> = emptyList(),
    val shopping: List<ShoppingEntry> = emptyList(),
) {
    val self: Member? get() = members.firstOrNull { it.id == selfId }
    fun member(id: String?): Member? = id?.let { i -> members.firstOrNull { it.id == i } }
}

/**
 * Design D4: choosing a person or a date confirms the card as soon as both are settled.
 * A date suggested by a rule counts as settled once a person chooses someone (they saw it on the card).
 */
val Item.readyToConfirm: Boolean get() = assignee != null && (dueDecided || due != null)
