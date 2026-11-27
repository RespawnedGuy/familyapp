package io.github.respawnedguy.familyapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import io.github.respawnedguy.familyapp.data.SharedImage
import io.github.respawnedguy.familyapp.data.SharedInput
import io.github.respawnedguy.familyapp.presentation.KitchenRoute
import io.github.respawnedguy.familyapp.ui.AndroidPhotoLoader
import io.github.respawnedguy.familyapp.update.AndroidUpdatePlatform
import io.github.respawnedguy.familyapp.update.UpdateController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app get() = application as FamilyApplication
    private var updatePlatform: AndroidUpdatePlatform? = null
    private var updates: UpdateController? = null
    private var micCallback: ((Boolean) -> Unit)? = null

    val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> app.platform.onPhotoResult(ok) }
    val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> app.platform.onFolderPicked(uri) }
    val scanQr = registerForActivityResult(ScanContract()) { result -> app.pairing.onScanned(result.contents) }

    /** System contact picker for one phone number, no READ_CONTACTS (docs/kontakte-und-portfolio.md, E1). */
    val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        app.platform.onContactPicked(result.data?.data?.takeIf { result.resultCode == RESULT_OK })
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micCallback?.invoke(granted)
        micCallback = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 13+ only; older versions show notifications without asking. */
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestMicrophone(onResult: (Boolean) -> Unit) {
        micCallback = onResult
        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.platform.activity = this
        // After a rotation the new window must keep the screen on again while cooking.
        if (app.model.kitchen.route.value is KitchenRoute.Cook) app.platform.keepScreenOn(true)

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!app.model.handleBack()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        if (savedInstanceState == null) ColdStartProbe.attach(this, app, intent)
        // A capture intent is handled before the first frame so the microphone starts in parallel.
        if (savedInstanceState == null) handleAppIntent(intent)

        val reducedMotion = app.platform.reducedMotion()
        setContent {
            App(
                versionName = BuildConfig.VERSION_NAME,
                model = app.model,
                photos = AndroidPhotoLoader,
                reducedMotion = reducedMotion,
                updates = updates,
                sync = app.syncUi,
            )
        }
    }

    /** Direct sync only while the app is visible: Android does not let a background app keep listening. */
    override fun onStart() {
        super.onStart()
        app.sync.start()
        // Offers are read when the app is opened and they are old, never in the background.
        app.model.offers.refreshIfDue()
    }

    override fun onStop() {
        app.sync.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (app.platform.activity === this) app.platform.activity = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleInstallerIntent(intent)) handleAppIntent(intent)
    }

    private fun handleAppIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_CAPTURE -> {
                val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "Verknüpfung"
                // The tile passes its tap time; otherwise a cold start counts from process start.
                val started = intent.getLongExtra(EXTRA_TRIGGER_AT, -1L).takeIf { it > 0 }
                    ?: if (app.coldStart) ColdStartProbe.processStartElapsedMs() else SystemClock.elapsedRealtime()
                app.coldStart = false
                app.model.startCapture(trigger, started)
            }
            Intent.ACTION_SEND -> {
                app.coldStart = false
                if (SharedInput.isVCard(intent.type)) {
                    handleSharedContact(intent)
                    return
                }
                // Any app can send anything here (threat model TE-1 to TE-4): text is cut, images are checked.
                val text = SharedInput.text(
                    intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                )
                val stream = intent.streamUri()
                val type = intent.type
                lifecycleScope.launch {
                    val image = stream?.let { withContext(Dispatchers.IO) { app.platform.importSharedImage(it, type) } }
                    val accepted = (image as? SharedImage.Accepted)?.path
                    app.model.captureShared(text, accepted, (image as? SharedImage.Rejected)?.why)
                }
            }
            else -> app.coldStart = false
        }
        // Handled once: a rotation must not capture again.
        setIntent(Intent(this, MainActivity::class.java))
    }

    /** A contact from the contacts app: as a file (usual) or as text; the parser caps it (KT-1). */
    private fun handleSharedContact(intent: Intent) {
        val stream = intent.streamUri()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        setIntent(Intent(this, MainActivity::class.java))
        lifecycleScope.launch {
            val vcard = stream?.let { withContext(Dispatchers.IO) { app.platform.readSharedVCard(it) } } ?: text
            app.model.captureSharedContact(vcard)
        }
    }

    private fun handleInstallerIntent(intent: Intent?): Boolean {
        val platform = updatePlatform ?: return false
        return platform.handleInstallStatus(this, intent) { success, message ->
            updates?.onInstallResult(success, message)
        }
    }

    companion object {
        const val ACTION_CAPTURE = "io.github.respawnedguy.familyapp.CAPTURE"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_TRIGGER_AT = "triggerAtElapsedMs"
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }
