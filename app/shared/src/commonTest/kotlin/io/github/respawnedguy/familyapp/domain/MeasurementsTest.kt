package io.github.respawnedguy.familyapp.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasurementsTest {
    private fun sample(ms: Long, metric: Metric = Metric.CAPTURE_READY, trigger: String = "Kachel", at: Long = ms) =
        LatencySample(trigger, ms, at, metric)

    @Test
    fun medianOfOddAndEvenCounts() {
        assertNull(Measurements.median(emptyList()))
        assertEquals(5L, Measurements.median(listOf(9, 1, 5)))
        assertEquals(4L, Measurements.median(listOf(1, 3, 5, 9)))
    }

    @Test
    fun percentileUsesNearestRank() {
        val ten = (1L..10L).map { it * 100 }
        assertEquals(900L, Measurements.percentile(ten, 90))
        assertEquals(1_000L, Measurements.percentile(ten, 100))
        assertEquals(100L, Measurements.percentile(ten, 1))
        // With few runs the 90th percentile is the slowest run: no optimistic interpolation.
        assertEquals(700L, Measurements.percentile(listOf(300, 700, 500), 90))
        assertNull(Measurements.percentile(emptyList(), 90))
        assertFailsWith<IllegalArgumentException> { Measurements.percentile(ten, 0) }
    }

    @Test
    fun eachMetricKeepsItsOwnLast50() {
        var list = emptyList<LatencySample>()
        list = Measurements.add(list, sample(1_500, Metric.CAPTURE_READY, at = 0))
        repeat(80) { list = Measurements.add(list, sample(600L + it, Metric.COLD_START, "Symbol", at = 1L + it)) }
        assertEquals(50, list.count { it.metric == Metric.COLD_START })
        assertEquals(630L, list.filter { it.metric == Metric.COLD_START }.minOf { it.ms })
        // Many cold starts must not push out the rarer capture measurement.
        assertEquals(1_500L, list.single { it.metric == Metric.CAPTURE_READY }.ms)
    }

    @Test
    fun summaryJudgesTheMedianAgainstTheD8Targets() {
        val samples = listOf(800L, 900L, 3_500L).map { sample(it) } +
            listOf(1_400L, 1_100L).map { sample(it, Metric.SAVE_AFTER_SPEECH, "Sprechpause") }
        val (capture, save) = Measurements.summarize(samples)
        assertEquals(Metric.CAPTURE_READY, capture.metric)
        assertEquals(900L, capture.medianMs)
        assertEquals(3_500L, capture.p90Ms)
        assertEquals(3, capture.count)
        assertTrue(capture.meetsTarget, "one slow run does not fail a median of 0.9 s against 2 s")
        assertEquals(1_250L, save.medianMs)
        assertFalse(save.meetsTarget, "1.25 s after speech end misses D8 (b) of 1 s")
    }

    @Test
    fun byTriggerListsTheMostUsedTriggerFirst() {
        val samples = listOf(sample(700, trigger = "Knopf"), sample(900), sample(1_100), sample(5_000, Metric.COLD_START, "Symbol"))
        val lines = Measurements.byTrigger(samples, Metric.CAPTURE_READY)
        assertEquals(listOf("Kachel", "Knopf"), lines.map { it.trigger })
        assertEquals(1_000L, lines.first().medianMs)
    }

    @Test
    fun csvHoldsTimesAndDeviceButNoFamilyData() {
        val samples = listOf(sample(1_234, at = 2_000), sample(612, Metric.COLD_START, "Symbol", at = 1_000))
        val csv = Measurements.csv(
            samples,
            DeviceInfo("Google Pixel 10", "Android 16 (API 36)", "0.2.99"),
            ResourceUsage(memoryKb = 81_920, storageBytes = 1_500_000),
        ) { "t$it" }
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        assertEquals("metric,trigger,ms,target_ms,recorded_at,device,os,app_version,memory_kb,storage_bytes", lines[0])
        assertEquals("COLD_START,Symbol,612,1000,t1000,Google Pixel 10,Android 16 (API 36),0.2.99,81920,1500000", lines[1])
        assertEquals("CAPTURE_READY,Kachel,1234,2000,t2000,Google Pixel 10,Android 16 (API 36),0.2.99,81920,1500000", lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun csvQuotesFieldsWithCommasAndQuotes() {
        val csv = Measurements.csv(listOf(sample(1, trigger = "A,\"B\"")), DeviceInfo("X", "Y", "Z"), null) { "t" }
        assertTrue(csv.contains("CAPTURE_READY,\"A,\"\"B\"\"\",1,2000,t,X,Y,Z,,\r\n"), csv)
    }

    @Test
    fun settingsTextsSayMedianSpreadAndVerdict() {
        val s = MetricSummary(Metric.CAPTURE_READY, null, count = 12, medianMs = 840, p90Ms = 1_260, maxMs = 1_900)
        assertEquals(
            "Bis die Aufnahme läuft: Median 0,8 s, 9 von 10 bis 1,3 s (12 Messungen). Ziel ≤ 2,0 s erreicht.",
            MeasurementText.line(s),
        )
        assertEquals(
            "Kaltstart bis erstes Bild: Median 1,5 s, 9 von 10 bis 1,5 s (1 Messung). Ziel ≤ 1,0 s verfehlt.",
            MeasurementText.line(MetricSummary(Metric.COLD_START, null, 1, 1_480, 1_480, 1_480)),
        )
        assertEquals("Kachel: Median 0,8 s (12×)", MeasurementText.triggerLine(s.copy(trigger = "Kachel")))
        assertEquals("Arbeitsspeicher jetzt 83,9 MB · App-Daten 1,5 MB", MeasurementText.usage(ResourceUsage(81_920, 1_500_000)))
        assertNull(MeasurementText.usage(ResourceUsage(null, null)))
        assertEquals("0,0 s", MeasurementText.duration(49))
        assertEquals("2,0 s", MeasurementText.duration(1_950))
    }
}
