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
 * The end is the first real draw (`OnDrawListener`, then the front of the main queue, as
 * Square's Papa library does), not the first `onPreDraw`: on Android 16 the CI saw the first
 * pre-draw about 1 s before Android logged "Displayed", on Android 8 within 20 ms. The end of the
 * first frame from [FrameMetrics] came 0.3–0.5 s after "Displayed" in the emulator (software
 * rendering). Both are logged as `probe` lines so the CI shows how the three relate.
 */
object ColdStartProbe {
    /** Activity created this soon after the process: the process was started for this launch. */
    private const val LAUNCH_WINDOW_MS = 5_000L

    /** Without frame metrics by then (no hardware rendering), the measurement ends anyway. */
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
        private var drawAt = -1L
        private var drawSeen = false
        private var frameEnd = -1L
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
            decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (drawSeen) return
                    drawSeen = true
                    // The frame is handed to the render thread right after onDraw returns.
                    handler.postAtFrontOfQueue {
                        drawAt = SystemClock.elapsedRealtime()
                        // Removing a draw listener inside onDraw is not allowed, here it is.
                        decor.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this)
                    }
                }
            })
            runCatching { activity.window.addOnFrameMetricsAvailableListener(this, handler) }
            handler.postDelayed({ finish() }, FRAME_METRICS_TIMEOUT_MS)
        }

        override fun onFrameMetricsAvailable(window: Window, metrics: FrameMetrics, dropCount: Int) {
            if (done || metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) return
            // Frame metrics use System.nanoTime (uptime clock); the process start uses elapsed realtime.
            val endNanos = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP) +
                metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val toElapsed = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
            frameEnd = (endNanos + toElapsed) / NANOS_PER_MS
            finish()
        }

        /** After the first frame's metrics (they arrive after its draw), or at the latest after the timeout. */
        private fun finish() {
            if (done) return
            done = true
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(this) }
            handler.removeCallbacksAndMessages(null)
            fun since(t: Long) = if (t > 0) t - processStart else -1
            Log.i(
                FamilyApplication.PERF_TAG,
                "probe trigger=$trigger preDraw=${since(preDrawAt)} draw=${since(drawAt)} frame=${since(frameEnd)}",
            )
            val end = listOf(drawAt, preDrawAt).firstOrNull { it > 0 } ?: return
            app.model.recordColdStart(trigger, end - processStart)
            // Tells Android the app is usable from here (shown as "Fully drawn" in logcat).
            activity.reportFullyDrawn()
        }
    }
}
