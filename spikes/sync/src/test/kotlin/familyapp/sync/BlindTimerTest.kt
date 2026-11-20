package familyapp.sync

import familyapp.sync.client.RelayException
import familyapp.sync.relay.PushSender
import familyapp.sync.relay.RelayStore
import familyapp.sync.relay.TimerScheduler
import familyapp.sync.relay.relayModule
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlindTimerTest {

    private class RecordingPushSender(var failNext: Int = 0) : PushSender {
        val pushes = mutableListOf<Triple<String, String, String>>()
        override fun push(deviceId: String, timerId: String, ciphertext: String) {
            if (failNext > 0) { failNext--; error("push gateway unreachable") }
            pushes += Triple(deviceId, timerId, ciphertext)
        }
    }

    /** Acceptance criterion 2: 20 timers, 5 cancelled, relay restarts in between → exactly 15 fire. */
    @Test
    fun `exactly the not cancelled timers fire across a relay restart`() {
        val db = tempDb().toString()
        val clock = TestClock()
        lateinit var family: TestFamily
        val cancelledIds = mutableSetOf<String>()
        val expectedIds = mutableSetOf<String>()

        testApplication {
            val store = RelayStore(db)
            application { relayModule(store, clock::nowMs) }
            family = TestFamily(createClient { }, clock, size = 3)
            family.register()
            val (author, assignee, partner) = family.devices
            val client = family.client(author)

            repeat(20) { i ->
                // Escalation step 2 ("one day later everyone") for deadline i.
                val (timer, cancelToken) = author.escalationTimer(
                    fireAtMs = clock.nowMs + (i + 1) * 60_000L,
                    recipients = listOf(author.deviceId, assignee.deviceId, partner.deviceId),
                    message = "Frist $i verpasst",
                )
                client.scheduleTimer(timer)
                if (i % 4 == 0) {
                    // Someone completes the task: the cancel token travelled inside the encrypted record.
                    family.client(assignee).cancelTimer(timer.id, cancelToken)
                    cancelledIds += timer.id
                } else {
                    expectedIds += timer.id
                }
            }
            store.close() // relay goes down before anything is due
        }

        clock.advance(30 * 60_000L)
        val sender = RecordingPushSender(failNext = 1) // first push attempt fails, must be retried
        RelayStore(db).use { restarted ->
            val scheduler = TimerScheduler(restarted, sender, clock::nowMs)
            scheduler.tick()
            scheduler.tick()
            assertEquals(0, scheduler.tick(), "nothing left to fire")
        }

        val firedIds = sender.pushes.map { it.second }.toSet()
        assertEquals(15, expectedIds.size)
        assertEquals(expectedIds, firedIds)
        assertTrue(firedIds.intersect(cancelledIds).isEmpty())
        // Each fired timer reached all three devices at least once (at-least-once; devices dedupe by timer id).
        expectedIds.forEach { id -> assertEquals(3, sender.pushes.filter { it.second == id }.map { it.first }.toSet().size) }
        val anyPush = sender.pushes.first()
        val recipient = family.devices.first { it.deviceId == anyPush.first }
        assertTrue(recipient.decryptPush(anyPush.third).startsWith("Frist "))
    }

    @Test
    fun `cancel with wrong token or after firing is rejected`() = testApplication {
        val clock = TestClock()
        val store = RelayStore(tempDb().toString())
        application { relayModule(store, clock::nowMs) }
        val family = TestFamily(createClient { }, clock, size = 1)
        family.register()
        val device = family.devices.single()
        val client = family.client(device)
        val (timer, token) = device.escalationTimer(clock.nowMs + 1_000, listOf(device.deviceId), "Frist")
        client.scheduleTimer(timer)

        assertEquals(409, assertFailsWith<RelayException> { client.cancelTimer(timer.id, "wrong") }.status)
        clock.advance(2_000)
        TimerScheduler(store, { _, _, _ -> }, clock::nowMs).tick()
        assertEquals(409, assertFailsWith<RelayException> { client.cancelTimer(timer.id, token) }.status)
        store.close()
    }
}
