package familyapp.sync.relay

import org.slf4j.LoggerFactory

/**
 * Fires due blind timers. At-least-once: a timer is marked fired only after all pushes were
 * handed over, so a crash in between re-sends it after restart; devices drop duplicates by timer id.
 */
class TimerScheduler(
    private val store: RelayStore,
    private val pushSender: PushSender,
    private val clock: () -> Long,
) {
    private val log = LoggerFactory.getLogger(TimerScheduler::class.java)

    fun tick(): Int {
        val now = clock()
        val due = store.dueTimers(now)
        for (timer in due) {
            try {
                timer.recipients.forEach { pushSender.push(it, timer.id, timer.ciphertext) }
                store.markFired(timer.id)
            } catch (e: Exception) {
                log.warn("timer {} not delivered, retry on next tick", timer.id, e)
            }
        }
        store.deleteExpiredNonces(now)
        return due.size
    }
}
