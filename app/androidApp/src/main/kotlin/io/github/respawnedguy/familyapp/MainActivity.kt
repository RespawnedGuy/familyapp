package io.github.respawnedguy.familyapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.respawnedguy.familyapp.update.AndroidUpdatePlatform
import io.github.respawnedguy.familyapp.update.UpdateController

class MainActivity : ComponentActivity() {

    private var updatePlatform: AndroidUpdatePlatform? = null
    private var updates: UpdateController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (BuildConfig.UPDATES_ENABLED) {
            val platform = AndroidUpdatePlatform(
                context = this,
                installedVersionCode = BuildConfig.VERSION_CODE,
                versionName = BuildConfig.VERSION_NAME,
                manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
                allowedApkUrlPrefix = BuildConfig.UPDATE_APK_URL_PREFIX,
                statusActivity = MainActivity::class.java,
            )
            updatePlatform = platform
            updates = UpdateController(platform, lifecycleScope).also { controller ->
                if (!handleInstallerIntent(intent)) controller.onAppStart()
            }
        }

        setContent {
            App(versionName = BuildConfig.VERSION_NAME, updates = updates)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallerIntent(intent)
    }

    private fun handleInstallerIntent(intent: Intent?): Boolean {
        val platform = updatePlatform ?: return false
        return platform.handleInstallStatus(this, intent) { success, message ->
            updates?.onInstallResult(success, message)
        }
    }
}
