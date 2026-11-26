package io.github.respawnedguy.familyapp

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window

/**
 * Cold start (quality plan P1-10): from the process start request until the first frame is
 * rendered, the span Android reports as "Displayed …" and `am start -W` as TotalTime. The
 * emulator job compares both (app/scripts/emulator-perf.sh), so the in-app number on a real
 * phone can be trusted.
 *
 * The end is taken from [FrameMetrics] of the first drawn frame (vsync plus total frame time),
 * not from the first `onPreDraw`: on Android 16 the CI saw the first pre-draw about 1 s before
 * Android counted the frame as drawn, while on Android 8 both were within 20 ms.
 */
object ColdStartProbe {
    /** Activity created this soon after the process: the process was started for this launch. */
    private const val LAUNCH_WINDOW_MS = 5_000L

    /** Without frame metrics by then (no hardware rendering), the pre-draw time is used. */
    private const val FRAME_METRICS_TIMEOUT_MS = 5_000L
    private const val NANOS_PER_MS = 1_000_000L

    /** When Android was asked to start this process (Android 13+), else when it started. */
    fun processStartElapsedMs(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Process.getStartRequestedElapsedRealtime()
        } else {
            Process.getStartElapsedRealtime()
        }

    fun attach(activity: Activity, app: FamilyApplication, intent: Intent?) {
        if (!app.coldStartUnmeasured) return
        app.coldStartUnmeasured = false
        // A process that had already been running for background work (alarm, sync): nobody waited for it.
        if (SystemClock.elapsedRealtime() - app.createdAtMs > LAUNCH_WINDOW_MS) return
        val trigger = when (intent?.action) {
            MainActivity.ACTION_CAPTURE -> intent.getStringExtra(MainActivity.EXTRA_TRIGGER) ?: "Verknüpfung"
            Intent.ACTION_SEND -> "Teilen"
            else -> "Symbol"
        }
        Measurement(activity, app, trigger, processStartElapsedMs()).start()
    }

    private class Measurement(
        private val activity: Activity,
        private val app: FamilyApplication,
        private val trigger: String,
        private val processStart: Long,
    ) : Window.OnFrameMetricsAvailableListener {
        private val handler = Handler(Looper.getMainLooper())
        private var preDrawAt = -1L
        private var done = false

        fun start() {
            val decor = activity.window.decorView
            decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    decor.viewTreeObserver.removeOnPreDrawListener(this)
                    preDrawAt = SystemClock.elapsedRealtime()
                    return true
                }
            })
            runCatching { activity.window.addOnFrameMetricsAvailableListener(this, handler) }
            handler.postDelayed({ finish(null) }, FRAME_METRICS_TIMEOUT_MS)
        }

        override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCount: Int) {
            if (done || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) return
            // Frame metrics use System.nanoTime (uptime clock); the process start uses elapsed realtime.
            val endNanos = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) +
                metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val toElapsed = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            finish((endNanos + toElapsed) / NANOS_PER_MS)
        }

        private fun finish(frameEnd: Long?) {
            if (done) return
            done = true
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(this) }
            handler.removeCallbacksAndMessages(null)
            val end = frameEnd ?: preDrawAt.takeIf { it > 0 } ?: return
            Log.i(
                FamilyApplication.PERF_TAG,
                "probe trigger=$trigger preDraw=${if (preDrawAt > 0) preDrawAt - processStart else -1} " +
                    "frame=${frameEnd?.let { it - processStart } ?: -1}",
            )
            app.model.recordColdStart(trigger, end - processStart)
            // Tells Android the app is usable from here (shown as "Fully drawn" in logcat).
            activity.reportFullyDrawn()
        }
    }
}
