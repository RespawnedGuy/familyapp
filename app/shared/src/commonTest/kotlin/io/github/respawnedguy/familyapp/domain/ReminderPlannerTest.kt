package io.github.respawnedguy.familyapp.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1-1 / QA-9: staged escalation with a fake clock (docs/qualitaet/iso-25010-bewertung.md). */
class ReminderPlannerTest {
    private val stamp = Stamp(0, 0, "d")
    private val nils = Member("nils", "Nils", 0, stamp = stamp)
    private val lisa = Member("lisa", "Lisa", 1, stamp = stamp)
    private val friday = LocalDate(2026, 11, 27)

    private fun item(id: String, assignee: String?, status: ItemStatus = ItemStatus.OPEN, due: LocalDate? = friday) =
        Item(id, "Aufgabe $id", Source.TEXT, 0, "nils", status = status, assignee = assignee, due = due, stamp = stamp)

    private fun data(vararg items: Item) = FamilyData(deviceId = "d", selfId = "nils", members = listOf(nils, lisa), items = items.toList())

    private val planner = ReminderPlanner()
    private fun at(day: Int, hour: Int = 8) = LocalDateTime(2026, 11, day, hour, 0)

    @Test
    fun ownDeadlineRemindsOnTheDayAndOnceMoreWhenMissed() {
        val r = planner.all(data(item("a", "nils")))
        assertEquals(listOf(ReminderKind.DUE_TODAY to at(27), ReminderKind.MISSED to at(28)), r.map { it.kind to it.at })
    }

    @Test
    fun someoneElsesDeadlineReachesMeOneDayAfterItWasMissed() {
        val r = planner.all(data(item("a", "lisa")))
        assertEquals(listOf(ReminderKind.ESCALATED to at(29)), r.map { it.kind to it.at })
    }

    @Test
    fun deadlineWithoutAnyoneInChargeReachesEveryoneRightAfterIt() {
        val r = planner.all(data(item("a", null, ItemStatus.INBOX)))
        assertEquals(listOf(ReminderKind.ESCALATED to at(28)), r.map { it.kind to it.at })
    }

    @Test
    fun finishedDismissedAndUndatedItemsNeverRemind() {
        val d = data(item("a", "nils", ItemStatus.DONE), item("b", "nils", ItemStatus.IRRELEVANT), item("c", "nils", due = null))
        assertTrue(planner.all(d).isEmpty())
    }

    @Test
    fun remindersAtTheSameMomentAreBundledIntoOne() {
        val d = data(item("a", "nils"), item("b", "nils"), item("c", "lisa"))
        val bundles = planner.dueBetween(d, after = at(26, 20), now = at(27, 9))
        assertEquals(1, bundles.size)
        assertEquals("2 Fristen brauchen einen Blick", bundles.single().title)
    }

    @Test
    fun nothingIsShownTwice() {
        val d = data(item("a", "nils"))
        assertEquals(1, planner.dueBetween(d, after = at(26, 20), now = at(27, 9)).size)
        assertTrue(planner.dueBetween(d, after = at(27, 9), now = at(27, 18)).isEmpty())
    }

    @Test
    fun nothingArrivesInTheQuietHours() {
        val d = data(item("a", "nils"), item("b", "lisa"), item("c", null, ItemStatus.INBOX))
        assertTrue(planner.all(d).all { it.at.hour in 7..20 })
    }

    @Test
    fun nextAlarmIsTheEarliestFutureReminder() {
        val d = data(item("a", "lisa"), item("b", "nils"))
        assertEquals(at(27), planner.next(d, at(26, 12)))
        assertEquals(at(29), planner.next(d, at(28, 12)))
        assertNull(planner.next(d, at(30)))
    }

    @Test
    fun singleReminderTextIsNeutral() {
        val bundle = planner.dueBetween(data(item("a", "lisa")), after = at(28), now = at(29)).single()
        assertEquals("Frist abgelaufen: Aufgabe a", bundle.title)
        assertEquals("Die Frist ist abgelaufen. Stand prüfen.", bundle.text)
    }

    @Test
    fun firstRunShowsNothingOldButPlansTheNextAlarm() {
        val d = data(item("a", "nils", due = LocalDate(2026, 11, 20)), item("b", "nils"))
        val run = planner.check(d, checked = null, now = at(26, 12))
        assertNull(run.show)
        assertEquals(at(27), run.next)
    }

    @Test
    fun runsMissedWhileThePhoneWasOffBecomeOneNotificationWithTheNewestStatePerItem() {
        val d = data(item("a", "nils"), item("b", "lisa"))
        val run = planner.check(d, checked = at(26, 20), now = at(29, 9))
        val shown = run.show!!.reminders
        assertEquals(listOf("a" to ReminderKind.MISSED, "b" to ReminderKind.ESCALATED), shown.map { it.itemId to it.kind })
        assertEquals("2 Fristen brauchen einen Blick", run.show!!.title)
        assertNull(run.next)
    }

    @Test
    fun switchedOffMeansNoNotificationAndNoAlarm() {
        val d = data(item("a", "nils")).let { it.copy(settings = it.settings.copy(remindersOn = false)) }
        assertEquals(ReminderCheck(null, null), planner.check(d, checked = at(26, 20), now = at(27, 9)))
    }
}
