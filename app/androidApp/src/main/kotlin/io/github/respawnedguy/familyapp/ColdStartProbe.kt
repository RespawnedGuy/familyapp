package io.github.respawnedguy.familyapp

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.ViewTreeObserver

/**
 * Cold start (quality plan P1-10): from process start until the first frame is drawn, the same
 * span Android reports as "Displayed …" and `am start -W` as TotalTime. The emulator job compares
 * both (app/scripts/emulator-perf.sh), so the in-app number on a real phone can be trusted.
 */
object ColdStartProbe {
    /** Activity created this soon after the process: the process was started for this launch. */
    private const val LAUNCH_WINDOW_MS = 5_000L

    /**
     * When Android was asked to start this process. From Android 13 on, `getStartElapsedRealtime`
     * is the moment the process was ready to run app code, which the CI measured about 1.1 s after
     * Android's own launch timestamp on API 36 (against 17 ms on API 26): the app would have looked
     * a second faster than it is. `getStartRequestedElapsedRealtime` matches what Android counts.
     */
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
        val processStart = processStartElapsedMs()
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                app.model.recordColdStart(trigger, SystemClock.elapsedRealtime() - processStart)
                // Tells Android the app is usable from here (shown as "Fully drawn" in logcat).
                activity.reportFullyDrawn()
                return true
            }
        })
    }
}
