package io.github.respawnedguy.familyapp.domain

import kotlinx.serialization.Serializable

private const val CAPTURE_TARGET_MS = 2_000L
private const val SAVE_TARGET_MS = 1_000L
private const val COLD_START_TARGET_MS = 1_000L
private const val PERCENT = 100
private const val P90 = 90
private const val TENTHS = 10
private const val KIB = 1_024L
private const val MS_PER_SECOND = 1_000L
private const val BYTES_PER_MB = 1_000_000L

/**
 * What a [LatencySample] measures (quality plan P1-10, design decision D8). All times are taken
 * with the monotonic clock on the device itself, so they include everything the person waits for.
 */
@Serializable
enum class Metric(val targetMs: Long) {
    /** Trigger (button, tile after unlocking, shortcut) until the microphone listens. D8 (a): ≤ 2 s. */
    CAPTURE_READY(CAPTURE_TARGET_MS),

    /** End of speech (the recognizer noticed it, or "Fertig" was tapped) until saved. D8 (b): ≤ 1 s. */
    SAVE_AFTER_SPEECH(SAVE_TARGET_MS),

    /**
     * Process start until the first frame (cold start). The design doc assumes about 0.8 s for the
     * app start in its click-path estimate; 1 s keeps the tile path inside the 2 s of D8.
     */
    COLD_START(COLD_START_TARGET_MS),
}

/** Median, 90th percentile and maximum of one metric, optionally for one trigger only. */
data class MetricSummary(
    val metric: Metric,
    val trigger: String?,
    val count: Int,
    val medianMs: Long,
    val p90Ms: Long,
    val maxMs: Long,
) {
    /** The acceptance criterion of P1-10 is the median, not every single run. */
    val meetsTarget: Boolean get() = medianMs <= metric.targetMs
}

/** The phone the numbers come from; written into the CSV so measurements stay comparable. */
data class DeviceInfo(val model: String, val osVersion: String, val appVersion: String)

/** Memory and storage of the app right now, for the settings screen and the CSV. Null where unknown. */
data class ResourceUsage(val memoryKb: Long?, val storageBytes: Long?)

object Measurements {
    /** Kept per metric, so frequent cold starts do not push out the rarer capture measurements. */
    const val MAX_PER_METRIC = 50

    /** Above this a "cold start" was not one the person saw: the process had been running for background work. */
    const val COLD_START_PLAUSIBLE_MS = 30_000L

    fun add(samples: List<LatencySample>, sample: LatencySample): List<LatencySample> {
        val same = samples.count { it.metric == sample.metric }
        if (same < MAX_PER_METRIC) return samples + sample
        val oldest = samples.indexOfFirst { it.metric == sample.metric }
        return samples.filterIndexed { i, _ -> i != oldest } + sample
    }

    /** Middle value; for an even count the mean of the two middle values. */
    fun median(values: List<Long>): Long? = values.takeIf { it.isNotEmpty() }?.sorted()?.let(::medianOfSorted)

    /** Nearest-rank percentile: the smallest value that [percent] % of the values do not exceed. */
    fun percentile(values: List<Long>, percent: Int): Long? {
        require(percent in 1..PERCENT) { "percent must be 1..100" }
        return values.takeIf { it.isNotEmpty() }?.sorted()?.let { percentileOfSorted(it, percent) }
    }

    private fun medianOfSorted(s: List<Long>): Long =
        if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2

    private fun percentileOfSorted(s: List<Long>, percent: Int): Long {
        val rank = (percent * s.size + PERCENT - 1) / PERCENT
        return s[rank - 1]
    }

    /** One line per metric that has samples, in the order of [Metric]. */
    fun summarize(samples: List<LatencySample>): List<MetricSummary> =
        Metric.entries.mapNotNull { metric -> summary(metric, null, samples.filter { it.metric == metric }) }

    /** One line per trigger of [metric] (Knopf, Kachel, Verknüpfung, Symbol …), most samples first. */
    fun byTrigger(samples: List<LatencySample>, metric: Metric): List<MetricSummary> =
        samples.filter { it.metric == metric }
            .groupBy { it.trigger }
            .mapNotNull { (trigger, list) -> summary(metric, trigger, list) }
            .sortedWith(compareByDescending<MetricSummary> { it.count }.thenBy { it.trigger })

    private fun summary(metric: Metric, trigger: String?, list: List<LatencySample>): MetricSummary? {
        val ms = list.map { it.ms }.sorted()
        if (ms.isEmpty()) return null
        return MetricSummary(metric, trigger, ms.size, medianOfSorted(ms), percentileOfSorted(ms, P90), ms.last())
    }

    /**
     * Measurements as CSV (RFC 4180, comma, UTF-8), for the reference table in the quality doc.
     * Contains no family data: only metric, trigger, duration, time and device. [formatTime] turns
     * epoch milliseconds into local ISO time on the platform.
     */
    fun csv(
        samples: List<LatencySample>,
        device: DeviceInfo,
        usage: ResourceUsage?,
        formatTime: (Long) -> String,
    ): String = buildString {
        append("metric,trigger,ms,target_ms,recorded_at,device,os,app_version,memory_kb,storage_bytes\r\n")
        val tail = listOf(
            device.model,
            device.osVersion,
            device.appVersion,
            usage?.memoryKb?.toString().orEmpty(),
            usage?.storageBytes?.toString().orEmpty(),
        )
        samples.sortedBy { it.atMs }.forEach { s ->
            val row = listOf(s.metric.name, s.trigger, "${s.ms}", "${s.metric.targetMs}", formatTime(s.atMs)) + tail
            append(row.joinToString(",") { field(it) }).append("\r\n")
        }
    }

    private fun field(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}

/** German texts for the settings screen; pure so the quality gate can check them. */
object MeasurementText {
    fun title(metric: Metric): String = when (metric) {
        Metric.CAPTURE_READY -> "Bis die Aufnahme läuft"
        Metric.SAVE_AFTER_SPEECH -> "Speichern nach Sprechende"
        Metric.COLD_START -> "Kaltstart bis erstes Bild"
    }

    /**
     * "Bis die Aufnahme läuft: Median 0,8 s, 9 von 10 bis 1,3 s (12 Messungen).
     * Ziel ≤ 2,0 s erreicht."
     */
    fun line(s: MetricSummary): String {
        val verdict = if (s.meetsTarget) "erreicht" else "verfehlt"
        val runs = if (s.count == 1) "1 Messung" else "${s.count} Messungen"
        return "${title(s.metric)}: Median ${duration(s.medianMs)}, 9 von 10 bis ${duration(s.p90Ms)} ($runs). " +
            "Ziel ≤ ${duration(s.metric.targetMs)} $verdict."
    }

    /** "Kachel: Median 0,7 s (8×)" */
    fun triggerLine(s: MetricSummary): String = "${s.trigger}: Median ${duration(s.medianMs)} (${s.count}×)"

    fun usage(u: ResourceUsage): String? {
        val parts = listOfNotNull(
            u.memoryKb?.let { "Arbeitsspeicher jetzt ${megabytes(it * KIB)}" },
            u.storageBytes?.let { "App-Daten ${megabytes(it)}" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Tenths of a second with a German decimal comma: 1 234 ms → "1,2 s". */
    fun duration(ms: Long): String = oneDecimal(ms, MS_PER_SECOND) + " s"

    /** Megabytes (10^6 bytes, as Android's app info shows them) with one decimal. */
    fun megabytes(bytes: Long): String = oneDecimal(bytes, BYTES_PER_MB) + " MB"

    /** [value] / [unit], rounded half up to one decimal, with a decimal comma. */
    private fun oneDecimal(value: Long, unit: Long): String {
        val tenths = (value * TENTHS + unit / 2) / unit
        return "${tenths / TENTHS},${tenths % TENTHS}"
    }
}
