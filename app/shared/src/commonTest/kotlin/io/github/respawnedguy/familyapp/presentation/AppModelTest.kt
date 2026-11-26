package io.github.respawnedguy.familyapp.presentation

import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.data.ShareRejection
import io.github.respawnedguy.familyapp.domain.CaptureAction
import io.github.respawnedguy.familyapp.domain.ItemStatus
import io.github.respawnedguy.familyapp.domain.MemberKind
import io.github.respawnedguy.familyapp.domain.Metric
import io.github.respawnedguy.familyapp.domain.Source
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSpeech : SpeechInput {
    var listener: SpeechListener? = null
    var cancelled = 0
    override fun start(listener: SpeechListener) { this.listener = listener }
    override fun stop() {}
    override fun cancel() { cancelled++ }
}

private class FakePlatform(override val speech: SpeechInput?) : PlatformServices {
    var elapsed = 0L
    var photoCallback: ((String?) -> Unit)? = null
    val backups = mutableListOf<String>()
    val backupCodes = mutableListOf<String>()

    /** What the backup folder holds, keyed by the code that opens it. */
    var stored: Pair<String, String>? = null
    override fun elapsedMs() = elapsed
    override fun reducedMotion() = false
    override fun takePhoto(onResult: (String?) -> Unit) { photoCallback = onResult }
    override fun pickBackupFolder(onResult: (String?) -> Unit) = onResult("ordner")
    override fun writeBackup(folder: String, backup: BackupContent): String? {
        backups += backup.json
        backupCodes += backup.code
        stored = backup.code to backup.json
        return null
    }
    override fun readBackup(folder: String, code: String?): RestoreRead {
        val (key, json) = stored ?: return RestoreRead.Failed("leer")
        return when (code) {
            null -> RestoreRead.NeedsCode
            key -> RestoreRead.Ok(json, emptyMap())
            else -> RestoreRead.WrongCode
        }
    }
    override fun newRecoveryCode() = "ABCD-EFGH-JKMN-PQRS-TVWX"
    var notificationRequests = 0
    override fun requestNotificationPermission() { notificationRequests++ }
    override fun describeFolder(folder: String) = folder
    var shared: String? = null
    override fun shareText(subject: String, text: String): Boolean {
        shared = text
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {
    private val friday = LocalDate(2026, 11, 20)
    private var ids = 0

    private fun TestScope.model(speech: SpeechInput? = FakeSpeech(), platform: FakePlatform = FakePlatform(speech)): AppModel {
        val repo = FamilyRepository(InMemoryTextStore(), backgroundScope, UnconfinedTestDispatcher(testScheduler), { testScheduler.currentTime }, { friday }, { "id${ids++}" })
        return AppModel(repo, platform, backgroundScope, { friday }, UnconfinedTestDispatcher(testScheduler)).apply { createFamily("Nils") }
    }

    @Test
    fun voiceCaptureSavesWithoutTapAndRecordsLatency() = runTest {
        val speech = FakeSpeech()
        val platform = FakePlatform(speech)
        val m = model(speech, platform)
        platform.elapsed = 1_000
        m.startCapture("Kachel", startedAtMs = 200)
        assertIs<CaptureState.Listening>(m.capture.value)
        speech.listener!!.onReady()
        assertEquals(800L, m.repo.current.settings.latency.single().ms)
        assertEquals("Kachel", m.repo.current.settings.latency.single().trigger)
        speech.listener!!.onResult("Hausschuhe bis Donnerstag")
        assertEquals(CaptureState.Closed, m.capture.value)
        val item = m.repo.current.items.single()
        assertEquals(Source.VOICE, item.source)
        assertEquals(LocalDate(2026, 11, 26), item.due)
        assertEquals("Gespeichert. Liegt oben im Eingang.", m.feedback.value?.message)
    }

    @Test
    fun undoAfterCaptureRemovesTheItem() = runTest {
        val speech = FakeSpeech()
        val m = model(speech)
        m.startCapture("Knopf", 0)
        speech.listener!!.onResult("Milch")
        m.undo(m.feedback.value!!)
        assertTrue(m.repo.current.items.isEmpty())
    }

    @Test
    fun withoutRecognizerTheSheetOffersTyping() = runTest {
        val m = model(speech = null)
        m.startCapture("Knopf", 0)
        assertIs<CaptureState.Problem>(m.capture.value)
        m.switchCapture(CaptureAction.TEXT)
        assertEquals(CaptureState.Typing, m.capture.value)
        m.saveText("Oma anrufen")
        assertEquals("Oma anrufen", m.repo.current.items.single().title)
    }

    @Test
    fun emptyRecognitionIsAProblemNotAnItem() = runTest {
        val speech = FakeSpeech()
        val m = model(speech)
        m.startCapture("Knopf", 0)
        speech.listener!!.onResult(" ")
        assertIs<CaptureState.Problem>(m.capture.value)
        assertTrue(m.repo.current.items.isEmpty())
    }

    @Test
    fun lateResultAfterDismissIsIgnored() = runTest {
        val speech = FakeSpeech()
        val m = model(speech)
        m.startCapture("Knopf", 0)
        val listener = speech.listener!!
        m.dismissCapture()
        listener.onResult("zu spät")
        assertTrue(m.repo.current.items.isEmpty())
    }

    @Test
    fun photoCaptureStoresPathAndCancelKeepsNothing() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val m = model(platform = platform)
        m.startCapture("Knopf", 0, CaptureAction.PHOTO)
        platform.photoCallback!!(null)
        assertEquals(CaptureState.Closed, m.capture.value)
        m.startCapture("Knopf", 0, CaptureAction.PHOTO)
        platform.photoCallback!!("/photos/1.jpg")
        assertEquals("/photos/1.jpg", m.repo.current.items.single().photoPath)
        assertEquals("Foto", m.repo.current.items.single().title)
    }

    @Test
    fun sharedTextIsSavedDirectly() = runTest {
        val m = model()
        m.captureShared("Elternbrief: Ausflug am 4.12.", null)
        val item = m.repo.current.items.single()
        assertEquals(Source.SHARE, item.source)
        assertEquals(LocalDate(2026, 12, 4), item.due)
    }

    @Test
    fun refusedSharedImageIsNamedAndTheTextStillSaved() = runTest {
        val m = model()
        m.captureShared("Elternbrief", null, ShareRejection.TOO_LARGE)
        assertNull(m.repo.current.items.single().photoPath)
        assertEquals("Gespeichert ohne Bild. Das Bild ist größer als 20 MB.", m.feedback.value?.message)
    }

    @Test
    fun refusedSharedImageWithoutTextSavesNothingButSaysWhy() = runTest {
        val m = model()
        m.captureShared("", null, ShareRejection.NOT_FROM_OTHER_APP)
        assertTrue(m.repo.current.items.isEmpty())
        assertEquals(
            "Bild nicht übernommen. Es werden nur Bilder angenommen, die eine andere App teilt.",
            m.feedback.value?.message,
        )
    }

    @Test
    fun backClosesSheetThenSettingsThenTab() = runTest {
        val m = model()
        m.select(Tab.FAMILY)
        m.openSettings()
        m.startCapture("Knopf", 0, CaptureAction.TEXT)
        assertTrue(m.handleBack())
        assertEquals(CaptureState.Closed, m.capture.value)
        // startCapture closed settings already; back now returns to Heute.
        assertTrue(m.handleBack())
        assertEquals(Tab.TODAY, m.tab.value)
        assertFalse(m.handleBack())
    }

    @Test
    fun choosingPersonOnCardWithSuggestedDateConfirmsWithFeedback() = runTest {
        val m = model()
        m.addMember("Lisa", MemberKind.ADULT)
        m.saveText("Elternabend am Dienstag")
        val item = m.repo.current.items.single()
        val lisa = m.repo.current.members.first { it.name == "Lisa" }.id
        m.setAssignee(item, lisa)
        assertEquals(ItemStatus.OPEN, m.repo.current.items.single().status)
        assertEquals("Bestätigt: Lisa kümmert sich.", m.feedback.value?.message)
    }

    @Test
    fun autoBackupIsDebouncedAndDoesNotLoop() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val m = model(platform = platform)
        runCurrent()
        m.chooseBackupFolder()
        runCurrent()
        assertEquals(1, platform.backups.size) // immediate backup after choosing
        m.saveText("A")
        m.saveText("B")
        advanceTimeBy(AppModel.AUTO_BACKUP_DELAY_MS + 100)
        assertEquals(2, platform.backups.size)
        assertTrue(platform.backups.last().contains("\"B\""))
        advanceTimeBy(AppModel.AUTO_BACKUP_DELAY_MS * 5)
        assertEquals(2, platform.backups.size)
        assertNull(m.repo.current.settings.lastBackupError)
    }

    @Test
    fun oneTapShareCountsOnlyDecidedCards() = runTest {
        val m = model()
        m.saveText("A")
        m.saveText("B")
        m.saveText("C")
        val (a, b) = m.repo.current.items
        m.confirm(a)
        m.setAssignee(b, m.repo.current.selfId!!)
        m.setDue(b, null)
        assertEquals(1 to 2, m.oneTapShare())
    }

    @Test
    fun choosingABackupFolderCreatesTheRecoveryCodeOnce() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val m = model(platform = platform)
        runCurrent()
        m.chooseBackupFolder()
        m.chooseBackupFolder()
        runCurrent()
        assertEquals("ABCD-EFGH-JKMN-PQRS-TVWX", m.repo.current.settings.backupCode)
        assertEquals(setOf("ABCD-EFGH-JKMN-PQRS-TVWX"), platform.backupCodes.toSet())
    }

    @Test
    fun restoreOnANewPhoneAsksForTheCodeAndRetriesAfterAWrongOne() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val old = model(platform = platform)
        old.chooseBackupFolder()
        old.saveText("Turnschuhe")
        old.backupNow()
        runCurrent()

        val fresh = model(platform = platform)
        fresh.repo.setBackupCode("FALSCH")
        fresh.restoreFromFolder()
        runCurrent()
        assertEquals(RestoreCodeRequest("ordner", wrong = true), fresh.restoreCode.value)
        fresh.restoreWithCode("ABCD-EFGH-JKMN-PQRS-TVWX")
        runCurrent()
        assertNull(fresh.restoreCode.value)
        assertTrue(fresh.repo.current.items.any { it.title == "Turnschuhe" })
        assertEquals("ABCD-EFGH-JKMN-PQRS-TVWX", fresh.repo.current.settings.backupCode)
        assertTrue(fresh.feedback.value!!.message.startsWith("Sicherung wiederhergestellt."))
    }

    @Test
    fun notificationPermissionIsAskedOnceWhenTheFirstDeadlineIsConfirmed() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val m = model(platform = platform)
        m.addMember("Lisa", MemberKind.ADULT)
        val lisa = m.repo.current.members.first { it.name == "Lisa" }.id
        m.saveText("Einkaufen")
        m.setAssignee(m.repo.current.items.single(), lisa)
        assertEquals(0, platform.notificationRequests, "no deadline, no reason to ask")

        m.saveText("Elternabend am Dienstag")
        m.setAssignee(m.repo.current.items.first { it.title.startsWith("Elternabend") }, lisa)
        m.saveText("Ausflug am 4.12.")
        m.setAssignee(m.repo.current.items.first { it.title.startsWith("Ausflug") }, lisa)
        assertEquals(1, platform.notificationRequests)
        assertTrue(m.repo.current.settings.notificationsAsked)
    }

    @Test
    fun switchedOffRemindersNeverAskForThePermission() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val m = model(platform = platform)
        m.setRemindersOn(false)
        m.saveText("Elternabend am Dienstag")
        m.setAssignee(m.repo.current.items.single(), m.repo.current.selfId!!)
        assertEquals(0, platform.notificationRequests)
    }

    // ---- P1-10: measurements on real devices ---------------------------------------------------

    @Test
    fun savingAfterTheSpeakerStopsIsMeasuredFromTheEndOfSpeech() = runTest {
        val speech = FakeSpeech()
        val platform = FakePlatform(speech)
        val m = model(speech, platform)
        m.startCapture("Kachel", startedAtMs = 0)
        platform.elapsed = 700
        speech.listener!!.onReady()
        platform.elapsed = 4_000
        speech.listener!!.onEndOfSpeech()
        platform.elapsed = 4_600
        speech.listener!!.onResult("Milch kaufen")
        val save = m.repo.current.settings.latency.single { it.metric == Metric.SAVE_AFTER_SPEECH }
        assertEquals(600L, save.ms)
        assertEquals(AppModel.SPEECH_END_AUTO, save.trigger)
        assertEquals(700L, m.repo.current.settings.latency.single { it.metric == Metric.CAPTURE_READY }.ms)
    }

    @Test
    fun tappingFertigCountsAsTheEndOfSpeechAndIsLabelledSo() = runTest {
        val speech = FakeSpeech()
        val platform = FakePlatform(speech)
        val m = model(speech, platform)
        m.startCapture("Knopf", 0)
        platform.elapsed = 3_000
        m.stopListening()
        platform.elapsed = 3_200
        speech.listener!!.onEndOfSpeech() // arrives after the tap: the tap stays the start
        platform.elapsed = 3_900
        speech.listener!!.onResult("Oma anrufen")
        val save = m.repo.current.settings.latency.single { it.metric == Metric.SAVE_AFTER_SPEECH }
        assertEquals(900L, save.ms)
        assertEquals(AppModel.SPEECH_END_TAP, save.trigger)
    }

    @Test
    fun nothingUnderstoodRecordsNoSaveTime() = runTest {
        val speech = FakeSpeech()
        val m = model(speech)
        m.startCapture("Knopf", 0)
        speech.listener!!.onEndOfSpeech()
        speech.listener!!.onResult(" ")
        assertTrue(m.repo.current.settings.latency.none { it.metric == Metric.SAVE_AFTER_SPEECH })
    }

    @Test
    fun coldStartsAreKeptOnlyWhenPlausible() = runTest {
        val m = model()
        m.recordColdStart("Symbol", 640)
        m.recordColdStart("Symbol", 45_000) // process ran in the background before
        m.recordColdStart("Symbol", -1)
        assertEquals(listOf(640L), m.repo.current.settings.latency.map { it.ms })
        assertEquals(Metric.COLD_START, m.repo.current.settings.latency.single().metric)
        assertNull(m.latencyMedianMs(), "a cold start is not a capture measurement")
    }

    @Test
    fun measurementsAreSharedAsCsvAndCanStartOver() = runTest {
        val platform = FakePlatform(FakeSpeech())
        val m = model(platform = platform)
        m.shareMeasurements()
        assertNull(platform.shared)
        assertEquals("Noch keine Messwerte zum Teilen.", m.feedback.value?.message)
        m.recordColdStart("Symbol", 640)
        m.shareMeasurements()
        val csv = platform.shared!!
        assertTrue(csv.startsWith("metric,trigger,ms,"), csv)
        assertTrue(csv.contains("COLD_START,Symbol,640,1000,"), csv)
        assertFalse(csv.contains("Nils"), "no family data in the CSV")
        m.clearMeasurements()
        assertTrue(m.repo.current.settings.latency.isEmpty())
        assertTrue(m.measurementSummary().isEmpty())
    }
}
