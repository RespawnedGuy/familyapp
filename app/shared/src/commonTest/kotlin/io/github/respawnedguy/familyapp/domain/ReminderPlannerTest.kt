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
        assertEquals("2 Fristen brauchen heute einen Blick", bundles.single().title)
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
    fun notificationIsShortNeutralAndNamesNoItem() {
        val escalated = planner.dueBetween(data(item("a", "lisa")), after = at(28), now = at(29)).single()
        assertEquals("Eine Frist ist abgelaufen", escalated.title)
        assertEquals("Details siehst du in der App.", escalated.text)
        val dueToday = planner.dueBetween(data(item("a", "nils")), after = at(26, 20), now = at(27, 9)).single()
        assertEquals("Dringende Frist heute", dueToday.title)
        assertTrue("Aufgabe" !in escalated.title + escalated.text + dueToday.title, "no item title on the lock screen")
    }

    @Test
    fun atMostOneNotificationPerDayWhateverTheStages() {
        // Ten deadlines in all stages: each morning brings one notification, never more.
        val items = (1..10).map { i -> item("i$i", listOf("nils", "lisa", null)[i % 3], due = LocalDate(2026, 11, 20 + i % 4)) }
        val d = data(*items.toTypedArray())
        (21..30).forEach { day ->
            assertTrue(planner.dueBetween(d, after = at(day - 1, 9), now = at(day, 9)).size <= 1, "day $day")
        }
    }

    @Test
    fun appShowsTheLatestStagePerItemOwnThingsFirst() {
        val d = data(
            item("mine", "nils"),
            item("hers", "lisa", due = LocalDate(2026, 11, 25)),
            item("later", "nils", due = LocalDate(2026, 12, 3)),
        )
        val urgent = planner.urgent(d, LocalDate(2026, 11, 28))
        assertEquals(listOf("mine" to ReminderKind.MISSED, "hers" to ReminderKind.ESCALATED), urgent.map { it.itemId to it.kind })
        assertTrue(planner.urgent(d, LocalDate(2026, 11, 26)).isEmpty(), "nothing urgent before the first stage")
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
        assertEquals("2 Fristen brauchen heute einen Blick", run.show!!.title)
        assertNull(run.next)
    }

    @Test
    fun switchedOffMeansNoNotificationAndNoAlarm() {
        val d = data(item("a", "nils")).let { it.copy(settings = it.settings.copy(remindersOn = false)) }
        assertEquals(ReminderCheck(null, null), planner.check(d, checked = at(26, 20), now = at(27, 9)))
    }

    @Test
    fun appLinesSayWhyWithoutBlame() {
        val d = data(
            item("a", "nils"),
            item("b", "lisa", due = LocalDate(2026, 11, 25)),
            item("c", null, ItemStatus.INBOX, LocalDate(2026, 11, 26)),
        )
        val lines = planner.urgent(d, LocalDate(2026, 11, 27)).associate { it.itemId to planner.reason(it, d) }
        assertEquals("heute fällig, bei dir", lines["a"])
        assertEquals("Frist war Mi 25.11., Lisa kümmert sich", lines["b"])
        assertEquals("Frist war Do 26.11., noch niemand zuständig", lines["c"])
    }
}
