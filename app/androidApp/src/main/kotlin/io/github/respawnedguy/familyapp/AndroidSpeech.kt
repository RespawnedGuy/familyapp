package io.github.respawnedguy.familyapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.respawnedguy.familyapp.presentation.SpeechInput
import io.github.respawnedguy.familyapp.presentation.SpeechListener

/**
 * On-device speech recognition (design doc "Erfassen", rule 5). Android 12+ uses the explicit
 * on-device recognizer; online recognition by Google is used only if the person allowed it in
 * the settings (Cloud services are optional, KONZEPT.md). The app never vibrates while listening.
 */
class AndroidSpeech(
    private val context: Context,
    private val activity: () -> MainActivity?,
    private val onlineAllowed: () -> Boolean,
) : SpeechInput {

    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechListener? = null
    private var triedOnline = false

    override fun start(listener: SpeechListener) {
        cancel()
        this.listener = listener
        triedOnline = false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            begin(listener)
            return
        }
        val a = activity()
        if (a == null) {
            listener.onError("Mikrofon nicht erlaubt.")
            return
        }
        a.requestMicrophone { granted ->
            if (this.listener !== listener) return@requestMicrophone
            if (granted) begin(listener) else listener.onError("Ohne Mikrofon-Erlaubnis geht nur Tippen oder Foto.")
        }
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun begin(listener: SpeechListener, forceOnline: Boolean = false) {
        val onDevice = !forceOnline && onDeviceAvailable()
        if (!onDevice && !onlineAllowed()) {
            listener.onError(
                "Auf diesem Handy gibt es keine Offline-Spracherkennung. Du kannst tippen oder in den Einstellungen die Online-Erkennung erlauben.",
            )
            return
        }
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
            listener.onError("Spracherkennung ist auf diesem Gerät nicht verfügbar. Tippen geht immer.")
            return
        }
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = r
        r.setRecognitionListener(Callbacks(listener, onDevice))
        r.startListening(intent(preferOffline = !forceOnline))
    }

    private fun intent(preferOffline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        listener = null
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    private inner class Callbacks(private val target: SpeechListener, private val onDevice: Boolean) : RecognitionListener {
        private fun current() = listener === target

        override fun onReadyForSpeech(params: Bundle?) {
            if (current()) target.onReady()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (current() && text.isNotBlank()) target.onPartial(text)
        }

        override fun onResults(results: Bundle?) {
            if (!current()) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            release()
            target.onResult(text)
        }

        override fun onError(error: Int) {
            if (!current()) return
            release()
            // German model missing on the device: start its download (Android 13+) and, if allowed, go online once.
            if (onDevice && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context).triggerModelDownload(intent(true)) }
                }
                if (onlineAllowed() && !triedOnline) {
                    triedOnline = true
                    begin(target, forceOnline = true)
                    return
                }
                target.onError("Das deutsche Sprachpaket fehlt noch auf dem Handy. Der Download ist angestoßen; bis dahin tippen.")
                return
            }
            target.onError(message(error))
        }

        private fun release() {
            recognizer?.let { runCatching { it.destroy() } }
            recognizer = null
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            if (current()) target.onEndOfSpeech()
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun message(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts verstanden. Nochmal sprechen oder tippen."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon nicht erlaubt."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erkennung braucht Internet, das gerade fehlt. Bitte tippen."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Die Spracherkennung ist gerade belegt. Gleich nochmal versuchen."
        SpeechRecognizer.ERROR_AUDIO -> "Das Mikrofon liefert keinen Ton."
        else -> "Spracherkennung fehlgeschlagen (Code $error). Bitte tippen."
    }

    private companion object {
        // SpeechRecognizer constants added in API 31; inlined so minSdk 26 compiles without lint noise.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
