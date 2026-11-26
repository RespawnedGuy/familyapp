package io.github.respawnedguy.familyapp

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import io.github.respawnedguy.familyapp.data.AndroidTextStore
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.presentation.AppModel
import io.github.respawnedguy.familyapp.sync.DirPhotoFiles
import io.github.respawnedguy.familyapp.sync.FileSyncStore
import io.github.respawnedguy.familyapp.sync.JcaSyncCrypto
import io.github.respawnedguy.familyapp.sync.SyncController
import io.github.respawnedguy.familyapp.sync.SyncUi
import io.github.respawnedguy.familyapp.sync.TcpTransport
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
    lateinit var sync: SyncController
        private set
    lateinit var pairing: AndroidPairing
        private set
    val syncUi: SyncUi get() = SyncUi(sync, pairing)

    /** True until the first capture intent was handled: then the process start is the trigger time. */
    var coldStart = true

    override fun onCreate() {
        super.onCreate()
        val scope = MainScope()
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
        // Pairing and sync (ADR 0003, 0006). The family key lives in sync.json, never in the family data or backups.
        sync = SyncController(
            repo = repo,
            store = FileSyncStore(File(filesDir, "sync.json")),
            transport = TcpTransport(),
            crypto = JcaSyncCrypto(),
            photos = DirPhotoFiles(platform.photoDir),
            scope = scope,
            repoDispatcher = Dispatchers.Main,
            io = Dispatchers.IO,
            deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
            discovery = NsdDiscovery(this),
            folders = { ref -> runCatching { SafSyncFolder(contentResolver, Uri.parse(ref)) }.getOrNull() },
        )
        pairing = AndroidPairing(platform)
        runCatching { SyncWorker.schedule(this) }
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
