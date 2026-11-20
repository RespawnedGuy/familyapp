package familyapp.sync.client

import kotlinx.serialization.Serializable

/**
 * Hybrid Logical Clock timestamp (Kulkarni et al. 2014). Orders events causally even when
 * device clocks drift; the device id breaks ties so every replica picks the same winner.
 */
@Serializable
data class Hlc(val wallMs: Long, val counter: Int, val deviceId: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wallMs, Hlc::counter, Hlc::deviceId)
}

class HybridLogicalClock(private val deviceId: String, private val wallClock: () -> Long) {
    private var last = Hlc(0, 0, deviceId)

    /** Timestamp for a local event. */
    fun now(): Hlc {
        val wall = wallClock()
        last = if (wall > last.wallMs) Hlc(wall, 0, deviceId) else last.copy(counter = last.counter + 1)
        return last
    }

    /** Merge a timestamp received from another device, so later local events sort after it. */
    fun observe(remote: Hlc) {
        val wall = wallClock()
        val maxWall = maxOf(wall, last.wallMs, remote.wallMs)
        val counter = when {
            maxWall == last.wallMs && maxWall == remote.wallMs -> maxOf(last.counter, remote.counter) + 1
            maxWall == last.wallMs -> last.counter + 1
            maxWall == remote.wallMs -> remote.counter + 1
            else -> 0
        }
        last = Hlc(maxWall, counter, deviceId)
    }
}
