package io.github.respawnedguy.familyapp.domain

import kotlinx.serialization.Serializable

/**
 * Hybrid logical clock stamp (same idea as spikes/sync and spikes/p2p-sync): wall time,
 * counter for equal wall times, and the device that made the change. Every change to a member or
 * item gets a new stamp, so later sync can merge records last-writer-wins without a server.
 */
@Serializable
data class Stamp(val wallMs: Long, val counter: Int, val device: String) : Comparable<Stamp> {
    override fun compareTo(other: Stamp): Int =
        compareValuesBy(this, other, Stamp::wallMs, Stamp::counter, Stamp::device)
}

class HybridClock(private val device: String, private val now: () -> Long) {
    private var last: Stamp? = null

    /** Makes the clock aware of stamps it has already seen (loaded from disk or received by sync). */
    fun observe(stamp: Stamp) {
        val l = last
        if (l == null || stamp > l) last = stamp.copy(device = device)
    }

    fun tick(): Stamp {
        val wall = now()
        val l = last
        val next = when {
            l == null || wall > l.wallMs -> Stamp(wall, 0, device)
            else -> Stamp(l.wallMs, l.counter + 1, device)
        }
        last = next
        return next
    }
}
