package io.github.respawnedguy.familyapp

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import io.github.respawnedguy.familyapp.data.AndroidTextStore
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.presentation.AppModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinLocalDate
import java.io.File
import java.util.UUID

/**
 * Holds data and presentation for the whole process, so the quick-settings tile, the share
 * target and a screen rotation all reuse the same state instead of loading it again.
 */
class FamilyApplication : Application() {
    lateinit var platform: AndroidPlatform
        private set
    lateinit var model: AppModel
        private set
    lateinit var reminders: AndroidReminders
        private set
    val scope = MainScope()

    /** True until the first capture intent was handled: then the process start is the trigger time. */
    var coldStart = true

    override fun onCreate() {
        super.onCreate()
        platform = AndroidPlatform(this)
        val repo = FamilyRepository(
            store = AndroidTextStore(File(filesDir, "familyapp.json")),
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            now = System::currentTimeMillis,
            today = ::today,
            newId = { UUID.randomUUID().toString() },
        )
        platform.allowOnlineSpeech = { repo.current.settings.allowOnlineSpeech }
        // Off the main thread: must not delay the capture start (D8).
        scope.launch(Dispatchers.IO) { platform.cleanOrphanPhotos(repo.current) }
        model = AppModel(repo, platform, scope, ::today, Dispatchers.IO)
        reminders = AndroidReminders(this, repo).also { it.follow(scope) }
        publishShortcut()
    }

    /** Long-press on the app icon → "Erfassen" (dynamic, so debug builds with another package work too). */
    private fun publishShortcut() {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CAPTURE)
                .putExtra(MainActivity.EXTRA_TRIGGER, "Verknüpfung")
            val shortcut = ShortcutInfo.Builder(this, "capture")
                .setShortLabel("Erfassen")
                .setLongLabel("Etwas erfassen")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_shortcut_capture))
                .setIntent(intent)
                .build()
            getSystemService(ShortcutManager::class.java)?.dynamicShortcuts = listOf(shortcut)
        }
    }

    private fun today(): LocalDate = java.time.LocalDate.now().toKotlinLocalDate()
}
