package io.github.respawnedguy.familyapp.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** The update channel exists but holds no release yet. Not an error. */
    data object NothingPublished : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class DeviceTooOld(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState
    data class NeedsPermission(val manifest: UpdateManifest) : UpdateState
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Presentation logic of the updater. Nothing is installed without a tap and the system dialog. */
class UpdateController(
    private val platform: UpdatePlatform,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    val autoCheckEnabled: Boolean get() = platform.autoCheckEnabled

    fun setAutoCheck(enabled: Boolean) {
        platform.autoCheckEnabled = enabled
    }

    fun onAppStart() {
        if (platform.autoCheckEnabled && UpdatePolicy.isAutoCheckDue(platform.lastCheckMs, platform.nowMs())) {
            check()
        }
    }

    fun check() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val text = platform.fetchManifest()
                platform.lastCheckMs = platform.nowMs()
                if (text == null) {
                    UpdateState.NothingPublished
                } else when (val parsed = UpdateManifestParser.parse(text, platform.allowedApkUrlPrefix)) {
                    is ManifestResult.Invalid -> UpdateState.Failed("Update-Info ungültig: ${parsed.reason}")
                    is ManifestResult.Valid ->
                        when (val d = UpdatePolicy.decide(platform.installedVersionCode, platform.deviceSdk, parsed.manifest)) {
                            UpdateDecision.UpToDate -> UpdateState.UpToDate
                            is UpdateDecision.Available -> UpdateState.Available(d.manifest)
                            is UpdateDecision.DeviceTooOld -> UpdateState.DeviceTooOld(d.manifest)
                        }
                }
            } catch (e: UpdateException) {
                UpdateState.Failed(e.message ?: "Update-Prüfung fehlgeschlagen")
            }
        }
    }

    fun install(manifest: UpdateManifest) {
        if (job?.isActive == true) return
        if (!platform.canInstallPackages()) {
            _state.value = UpdateState.NeedsPermission(manifest)
            return
        }
        job = scope.launch {
            _state.value = UpdateState.Downloading(manifest, 0f)
            _state.value = try {
                val download = platform.download(manifest) { p -> _state.value = UpdateState.Downloading(manifest, p) }
                val problem = platform.verifyPackage(download)
                if (problem != null) {
                    UpdateState.Failed(problem)
                } else {
                    when (val outcome = platform.install(download)) {
                        InstallOutcome.Started -> UpdateState.Installing(manifest)
                        is InstallOutcome.Failed -> UpdateState.Failed(outcome.reason)
                    }
                }
            } catch (e: UpdateException) {
                UpdateState.Failed(e.message ?: "Download fehlgeschlagen")
            }
        }
    }

    fun openPermissionSettings() = platform.openInstallPermissionSettings()

    /** Called by the platform when the system installer reports a result. */
    fun onInstallResult(success: Boolean, message: String?) {
        if (!success) _state.value = UpdateState.Failed(message ?: "Installation abgebrochen")
    }
}
