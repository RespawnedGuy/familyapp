package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * One bar of the load chart ("Lastkarte"): only items with a fixed date count (Nils, 20.11.2026).
 * [doneThisWeek] counts everything this member finished since Monday, so done work is visible too.
 */
data class Load(val member: Member, val open: Int, val overdue: Int, val doneThisWeek: Int = 0)

data class Week(val monday: LocalDate, val items: List<Item>)

/** Read-only views over [FamilyData] for the five screens. Pure functions, tested in commonTest. */
object Views {

    /** Newest first: what was just captured is on top ("Liegt oben im Eingang"). */
    fun inbox(data: FamilyData): List<Item> =
        data.items.filter { it.status == ItemStatus.INBOX }.sortedByDescending { it.createdMs }

    /** Overdue first, then by date, items without date last. */
    fun openFor(data: FamilyData, memberId: String?): List<Item> =
        data.items.filter { it.status == ItemStatus.OPEN && it.assignee == memberId }.sortedWith(byDue)

    fun mine(data: FamilyData): List<Item> = openFor(data, data.selfId)

    fun doneThisWeek(data: FamilyData, today: LocalDate): Int {
        val monday = DateFormat.mondayOf(today)
        return data.items.count { it.status == ItemStatus.DONE && it.doneOn != null && it.doneOn >= monday }
    }

    fun isOverdue(item: Item, today: LocalDate): Boolean =
        item.status == ItemStatus.OPEN && item.due != null && item.due < today

    fun loads(data: FamilyData, today: LocalDate): List<Load> {
        val monday = DateFormat.mondayOf(today)
        return data.members.filter { it.kind == MemberKind.ADULT }.map { m ->
            val fixed = data.items.filter { it.status == ItemStatus.OPEN && it.assignee == m.id && it.hasFixedDate }
            val done = data.items.count { it.status == ItemStatus.DONE && it.doneBy == m.id && it.doneOn != null && it.doneOn >= monday }
            Load(m, fixed.size, fixed.count { isOverdue(it, today) }, done)
        }
    }

    /**
     * Quality plan P1-11 (FMEA D3/D4): everything lives on one phone only. Shown on "Heute" from
     * [LOCAL_ONLY_MIN_ITEMS] entries, never as a notification; put aside, it waits for
     * [LOCAL_ONLY_SNOOZE_ITEMS] more entries.
     */
    fun showLocalOnlyHint(data: FamilyData, paired: Boolean): Boolean {
        if (paired || data.settings.backupFolder != null) return false
        val count = data.items.size
        val dismissed = data.settings.localOnlyHintDismissedAt
        return count >= LOCAL_ONLY_MIN_ITEMS && (dismissed == null || count >= dismissed + LOCAL_ONLY_SNOOZE_ITEMS)
    }

    const val LOCAL_ONLY_MIN_ITEMS = 10
    const val LOCAL_ONLY_SNOOZE_ITEMS = 30

    /** Inbox items nobody takes care of yet (shown under the load chart). */
    fun unowned(data: FamilyData): Int = data.items.count { it.status == ItemStatus.INBOX && it.assignee == null }

    /** Six calendar weeks from this week's Monday; inbox and open items with a date. */
    fun upcoming(data: FamilyData, today: LocalDate, weeks: Int = 6): List<Week> {
        val first = DateFormat.mondayOf(today)
        val end = first.plus(weeks * 7, DateTimeUnit.DAY)
        val dated = data.items.filter {
            (it.status == ItemStatus.OPEN || it.status == ItemStatus.INBOX) && it.due != null && it.due < end
        }
        return (0 until weeks).map { w ->
            val monday = first.plus(w * 7, DateTimeUnit.DAY)
            val next = monday.plus(7, DateTimeUnit.DAY)
            // Overdue items stay visible in the first week instead of disappearing.
            Week(monday, dated.filter { (w == 0 && it.due!! < monday) || (it.due!! >= monday && it.due < next) }.sortedWith(byDue))
        }
    }

    /** Full-text search over titles, reasons and people; every status, newest first. */
    fun search(data: FamilyData, query: String): List<Item> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return data.items.filter { item ->
            val haystack = buildString {
                append(item.title).append(' ')
                item.reasons.forEach { append(it).append(' ') }
                data.member(item.concerns)?.let { append(it.name).append(' ') }
                data.member(item.assignee)?.let { append(it.name).append(' ') }
            }.lowercase()
            terms.all { haystack.contains(it) }
        }.sortedByDescending { it.createdMs }
    }

    private val byDue = compareBy<Item>({ it.due == null }, { it.due }, { it.createdMs })
}
