package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.atTime
import kotlinx.datetime.plus

enum class ReminderKind {
    /** Morning of the due day, to the person who takes care of it. */
    DUE_TODAY,

    /** The day after: the deadline passed, to the person who takes care of it. */
    MISSED,

    /** One more day later (or right away if nobody takes care of it): to everyone else. */
    ESCALATED,
}

data class Reminder(val itemId: String, val title: String, val kind: ReminderKind, val at: LocalDateTime)

/**
 * One notification for everything that falls due at the same moment. Nils, 26.11.2026: as few
 * system notifications as possible, never one per item; the details wait in the app, so the
 * person decides when to sit down and organise instead of hearing a constant background hum.
 * Hence a short title, no item titles (also nothing private on the lock screen).
 */
data class ReminderBundle(val at: LocalDateTime, val reminders: List<Reminder>) {
    val title: String
        get() = when {
            reminders.size > 1 -> "${reminders.size} Fristen brauchen heute einen Blick"
            reminders.single().kind == ReminderKind.DUE_TODAY -> "Dringende Frist heute"
            else -> "Eine Frist ist abgelaufen"
        }

    /** Neutral wording (ADR 0003): this device may not know yet that someone else finished it. */
    val text: String get() = "Details siehst du in der App."
}

/**
 * Staged escalation from the concept (phase 6, decided by Nils on 20.11.2026): deadline missed →
 * the person in charge; one day later → everyone. Runs on every device for the items it knows,
 * without a server (ADR 0003). Pure function of the data and the clock, so it is fully testable.
 *
 * Rules that keep it from becoming new mental load:
 * - only items with a date, only while open or still in the inbox (done/irrelevant never remind),
 * - at most three reminders per item, never daily nagging,
 * - all reminders at [hour] in the morning, so nothing arrives in the quiet hours (21–7 h),
 * - everything of one morning is a single notification, so at most one per day.
 */
class ReminderPlanner(private val hour: Int = 8) {

    /** Every reminder this device would show for [data], in time order. */
    fun all(data: FamilyData): List<Reminder> {
        val self = data.selfId ?: return emptyList()
        return data.items.filter { (it.status == ItemStatus.OPEN || it.status == ItemStatus.INBOX) && it.due != null }
            .flatMap { item -> forItem(item, self) }
            .sortedBy { it.at }
    }

    private fun forItem(item: Item, self: String): List<Reminder> {
        val due = item.due ?: return emptyList()
        fun r(kind: ReminderKind, day: LocalDate) = Reminder(item.id, item.title, kind, day.atTime(LocalTime(hour, 0)))
        val owner = item.assignee.takeIf { item.status == ItemStatus.OPEN }
        return when (owner) {
            self -> listOf(r(ReminderKind.DUE_TODAY, due), r(ReminderKind.MISSED, due.plus(1, DateTimeUnit.DAY)))
            // Nobody takes care of it: everyone hears about it as soon as the deadline passed.
            null -> listOf(r(ReminderKind.ESCALATED, due.plus(1, DateTimeUnit.DAY)))
            else -> listOf(r(ReminderKind.ESCALATED, due.plus(2, DateTimeUnit.DAY)))
        }
    }

    /** Reminders due in the window (after, now], bundled per moment. [after] null = only the current moment. */
    fun dueBetween(data: FamilyData, after: LocalDateTime?, now: LocalDateTime): List<ReminderBundle> =
        all(data).filter { it.at <= now && (after == null || it.at > after) && (after != null || it.at == now) }
            .groupBy { it.at }.map { (at, list) -> ReminderBundle(at, list) }

    /**
     * What the app shows on top of "Heute" when it is opened: per item the latest stage reached by
     * [today], so the same rules decide what is urgent in the app and in the notification.
     */
    fun urgent(data: FamilyData, today: LocalDate): List<Reminder> =
        all(data).filter { it.at.date <= today }.groupBy { it.itemId }.map { (_, list) -> list.last() }
            .sortedWith(compareBy<Reminder> { it.kind == ReminderKind.ESCALATED }.thenBy { it.at })

    /** One line under "Heute wichtig": why the item is listed, without blame. */
    fun reason(reminder: Reminder, data: FamilyData): String {
        val item = data.items.firstOrNull { it.id == reminder.itemId }
        val due = item?.due?.let { "Frist war " + DateFormat.short(it) } ?: "Frist abgelaufen"
        return when (reminder.kind) {
            ReminderKind.DUE_TODAY -> "heute fällig, bei dir"
            ReminderKind.MISSED -> "$due, bei dir"
            ReminderKind.ESCALATED -> {
                val who = item?.takeIf { it.status == ItemStatus.OPEN }?.let { data.member(it.assignee) }
                if (who == null) "$due, noch niemand zuständig" else "$due, ${who.name} kümmert sich"
            }
        }
    }

    /** When the next reminder falls due after [now], or null if there is none. */
    fun next(data: FamilyData, now: LocalDateTime): LocalDateTime? = all(data).firstOrNull { it.at > now }?.at

    /**
     * One run of the reminder alarm: what to show now and when to wake up next.
     * [checked] is when the last run happened; null on the very first run, which shows nothing
     * so that switching the feature on never floods the person with old deadlines. Runs missed
     * while the phone was off are folded into a single notification, newest state per item.
     */
    fun check(data: FamilyData, checked: LocalDateTime?, now: LocalDateTime): ReminderCheck {
        if (!data.settings.remindersOn) return ReminderCheck(show = null, next = null)
        val due = if (checked == null) emptyList() else dueBetween(data, checked, now).flatMap { it.reminders }
        val latest = due.groupBy { it.itemId }.map { (_, list) -> list.last() }
        val show = latest.takeIf { it.isNotEmpty() }?.let { ReminderBundle(now, it) }
        return ReminderCheck(show, next(data, now))
    }
}

/** Result of [ReminderPlanner.check]: a notification to show (or none) and the next alarm time. */
data class ReminderCheck(val show: ReminderBundle?, val next: LocalDateTime?)
