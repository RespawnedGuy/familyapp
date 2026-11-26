package io.github.respawnedguy.familyapp

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.TileService

/**
 * Quick-settings tile "Erfassen": the shortest way into capture without the app icon
 * (design doc: about 2.4 s overhead until recording, D8 target ≤ 2 s from unlock).
 */
class CaptureTileService : TileService() {

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        // Measured from the moment the phone is unlocked (D8 "≤ 2 s after unlocking"), not from the
        // tap on a locked phone: how long someone takes to enter the PIN is not the app's time.
        val open = {
            val intent = Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_CAPTURE)
                .putExtra(MainActivity.EXTRA_TRIGGER, "Kachel")
                .putExtra(MainActivity.EXTRA_TRIGGER_AT, SystemClock.elapsedRealtime())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { open() } else open()
    }
}
