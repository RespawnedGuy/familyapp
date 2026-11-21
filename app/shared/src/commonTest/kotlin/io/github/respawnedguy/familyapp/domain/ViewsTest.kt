package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewsTest {
    private val friday = LocalDate(2026, 11, 20)
    private val s = Stamp(0, 0, "d")
    private val nils = Member("n", "Nils", 0, MemberKind.ADULT, true, s)
    private val lisa = Member("l", "Lisa", 1, MemberKind.ADULT, false, s)
    private val mia = Member("m", "Mia", 2, MemberKind.CHILD, false, s)

    private fun item(id: String, status: ItemStatus, assignee: String? = null, due: LocalDate? = null, created: Long = 0, title: String = id) =
        Item(id, title, Source.TEXT, created, "n", status = status, assignee = assignee, due = due, dueDecided = due != null, stamp = s)

    private fun data(vararg items: Item) = FamilyData(deviceId = "d", selfId = "n", members = listOf(nils, lisa, mia), items = items.toList())

    @Test
    fun inboxNewestFirst() {
        val d = data(item("old", ItemStatus.INBOX, created = 1), item("new", ItemStatus.INBOX, created = 2), item("x", ItemStatus.OPEN, "n"))
        assertEquals(listOf("new", "old"), Views.inbox(d).map { it.id })
    }

    @Test
    fun mineOverdueFirstUndatedLast() {
        val d = data(
            item("undated", ItemStatus.OPEN, "n"),
            item("later", ItemStatus.OPEN, "n", LocalDate(2026, 11, 30)),
            item("overdue", ItemStatus.OPEN, "n", LocalDate(2026, 11, 18)),
            item("lisas", ItemStatus.OPEN, "l", LocalDate(2026, 11, 18)),
        )
        assertEquals(listOf("overdue", "later", "undated"), Views.mine(d).map { it.id })
    }

    @Test
    fun loadCountsOnlyFixedDatesOfAdults() {
        val d = data(
            item("a", ItemStatus.OPEN, "l", LocalDate(2026, 11, 18)),
            item("b", ItemStatus.OPEN, "l", LocalDate(2026, 11, 25)),
            item("c", ItemStatus.OPEN, "l"),
            item("d", ItemStatus.DONE, "l", LocalDate(2026, 11, 18)),
        )
        val loads = Views.loads(d, friday)
        assertEquals(listOf("Nils", "Lisa"), loads.map { it.member.name })
        assertEquals(Load(lisa, open = 2, overdue = 1), loads[1])
    }

    @Test
    fun upcomingGroupsSixWeeksAndKeepsOverdueInFirstWeek() {
        val d = data(
            item("overdue", ItemStatus.OPEN, "n", LocalDate(2026, 11, 10)),
            item("thisweek", ItemStatus.INBOX, due = LocalDate(2026, 11, 21)),
            item("week3", ItemStatus.OPEN, "l", LocalDate(2026, 12, 2)),
            item("toofar", ItemStatus.OPEN, "l", LocalDate(2027, 2, 1)),
            item("done", ItemStatus.DONE, "l", LocalDate(2026, 11, 21)),
        )
        val weeks = Views.upcoming(d, friday)
        assertEquals(6, weeks.size)
        assertEquals(LocalDate(2026, 11, 16), weeks[0].monday)
        assertEquals(listOf("overdue", "thisweek"), weeks[0].items.map { it.id })
        assertEquals(listOf("week3"), weeks[2].items.map { it.id })
        assertEquals(3, weeks.sumOf { it.items.size })
    }

    @Test
    fun searchFindsTitlesAndPeopleAcrossStatuses() {
        val d = data(
            item("1", ItemStatus.DONE, "l", title = "Hausschuhe kaufen"),
            item("2", ItemStatus.INBOX, title = "Zahnarzt"),
        )
        assertEquals(listOf("1"), Views.search(d, "hausschuhe").map { it.id })
        assertEquals(listOf("1"), Views.search(d, "lisa schuhe").map { it.id })
        assertEquals(emptyList(), Views.search(d, "  "))
    }

    @Test
    fun doneThisWeekCountsFromMonday() {
        val d = data(
            item("a", ItemStatus.DONE).copy(doneOn = LocalDate(2026, 11, 16)),
            item("b", ItemStatus.DONE).copy(doneOn = LocalDate(2026, 11, 15)),
        )
        assertEquals(1, Views.doneThisWeek(d, friday))
    }
}
