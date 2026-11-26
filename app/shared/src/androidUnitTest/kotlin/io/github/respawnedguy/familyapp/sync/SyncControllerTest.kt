package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.BoundedRead
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.data.InMemoryTextStore
import io.github.respawnedguy.familyapp.domain.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pairing and sync end to end: real TCP on localhost, real crypto, real repositories. */
class SyncControllerTest {
    private val root: File = Files.createTempDirectory("familyapp-sync").toFile()
    private val devices = mutableListOf<Device>()
    private var clock = 1_800_000_000_000L

    /** Only loopback addresses, so the test does not depend on the machine's network. */
    private class LoopbackTransport : SyncTransport by TcpTransport() {
        private val tcp = TcpTransport()
        override fun startServer(port: Int, handler: suspend (FrameChannel) -> Unit) = tcp.startServer(port, handler)
        override fun stopServer() = tcp.stopServer()
        override fun localAddresses() = listOf("127.0.0.1")
    }

    inner class Device(val name: String) {
        val dir = File(root, name).apply { mkdirs() }
        val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val photos = DirPhotoFiles(File(dir, "photos").apply { mkdirs() })
        val store = InMemorySyncStore()
        val repo = FamilyRepository(
            store = InMemoryTextStore(),
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            now = { clock++ },
            today = { LocalDate(2026, 11, 26) },
            newId = { UUID.randomUUID().toString() },
        )
        val sync = SyncController(
            repo, store, LoopbackTransport(), JcaSyncCrypto(), photos, scope, thread, Dispatchers.IO, "$name-Handy",
            folders = { LocalFolderFiles(File(it)) }, now = { clock }, preferredPort = 0,
        )

        suspend fun <T> onRepo(block: FamilyRepository.() -> T): T = withContext(thread) { repo.block() }
    }

    private fun device(name: String) = Device(name).also { devices += it }

    @AfterTest
    fun cleanUp() {
        devices.forEach {
            it.sync.stop()
            it.scope.cancel()
            it.thread.close()
        }
        root.deleteRecursively()
    }

    private suspend fun <T> StateFlow<T>.until(predicate: (T) -> Boolean): T = withTimeout(15_000) { first { predicate(it) } }

    private suspend fun invite(inviter: Device): String {
        inviter.sync.invite()
        return assertIs<PairingState.Inviting>(inviter.sync.pairing.until { it is PairingState.Inviting }).code
    }

    /** Runs a complete pairing; returns the two confirm screens for checks. */
    private suspend fun pair(inviter: Device, joiner: Device, person: String): Pair<PairingState.Confirm, PairingState.Confirm> {
        val code = invite(inviter)
        joiner.sync.join(code, person)
        val atInviter = inviter.sync.pairing.until { it is PairingState.Confirm } as PairingState.Confirm
        val atJoiner = joiner.sync.pairing.until { it is PairingState.Confirm } as PairingState.Confirm
        inviter.sync.answer(true, atInviter.suggested)
        joiner.sync.answer(true)
        inviter.sync.pairing.until { it is PairingState.Done }
        joiner.sync.pairing.until { it is PairingState.Done }
        return atInviter to atJoiner
    }

    @Test
    fun pairingByQrCodeBringsFamilyPhotosAndThenSyncsBothWays(): Unit = runBlocking {
        val nils = device("nils")
        val lisa = device("lisa")
        val photoBytes = ByteArray(300_000) { (it % 251).toByte() }
        nils.photos.write("foto-1.jpg", photoBytes)
        nils.onRepo {
            createFamily("Nils")
            capture("Elternabend Freitag", Source.TEXT)
            capture("", Source.PHOTO, photoPath = nils.photos.pathFor("foto-1.jpg"))
        }

        val (atNils, atLisa) = pair(nils, lisa, "Lisa")

        // Same six digits on both screens; each sees who is on the other side.
        assertEquals(atNils.sas, atLisa.sas)
        assertEquals("Lisa", atNils.other.personName)
        assertEquals("Nils", atLisa.other.personName)
        assertEquals(JoinAs.NewPerson("Lisa"), atNils.suggested)
        assertTrue(atNils.choices.contains(JoinAs.HouseDevice))

        val lisaData = lisa.onRepo { current }
        assertEquals("Lisa", lisaData.self?.name)
        assertTrue(lisaData.self!!.hasDevice)
        assertEquals(setOf("Elternabend Freitag", "Foto"), lisaData.items.map { it.title }.toSet())
        // The photo arrives with the first sync right after pairing.
        withTimeout(15_000) { while (!lisa.photos.exists("foto-1.jpg")) kotlinx.coroutines.delay(50) }
        assertContentEquals(photoBytes, lisa.photos.read("foto-1.jpg"))
        assertEquals(lisa.photos.pathFor("foto-1.jpg"), lisa.onRepo { current.items.single { it.title == "Foto" }.photoPath })
        assertTrue(nils.store.text!!.contains(lisa.repo.current.deviceId))
        assertFalse(nils.repo.current.settings.toString().contains("familyKey"))

        // Lisa adds something; Nils gets it with the next sync.
        lisa.onRepo { capture("Turnbeutel waschen", Source.TEXT) }
        lisa.sync.syncAll(manual = true)
        assertTrue(nils.onRepo { current.items.any { it.title == "Turnbeutel waschen" } })
        assertTrue(lisa.sync.status.value.lastResult!!.contains("Abgeglichen mit nils-Handy"))
        assertEquals(1, nils.sync.status.value.devices.size)
        assertNotNull(nils.sync.status.value.devices.single().lastSyncMs)
    }

    @Test
    fun thirdDeviceKnowsTheSecondWithoutHavingMetIt(): Unit = runBlocking {
        val nils = device("nils")
        val lisa = device("lisa")
        val oma = device("oma")
        nils.onRepo { createFamily("Nils") }
        pair(nils, lisa, "Lisa")
        pair(nils, oma, "Oma")
        assertTrue(oma.sync.status.value.devices.any { it.deviceId == lisa.repo.current.deviceId })
        lisa.onRepo { capture("Von Lisa", Source.TEXT) }
        lisa.sync.syncAll()
        oma.sync.syncAll()
        assertTrue(oma.onRepo { current.items.any { it.title == "Von Lisa" } })
    }

    @Test
    fun declinedPairingTransfersNothing(): Unit = runBlocking {
        val nils = device("nils")
        val stranger = device("fremd")
        nils.onRepo { createFamily("Nils"); capture("Privat", Source.TEXT) }
        val code = invite(nils)
        stranger.sync.join(code, "Lisa")
        val confirm = nils.sync.pairing.until { it is PairingState.Confirm } as PairingState.Confirm
        stranger.sync.pairing.until { it is PairingState.Confirm }
        nils.sync.answer(false, confirm.suggested) // Nils sees an unknown device and says no
        stranger.sync.answer(true)
        assertIs<PairingState.Failed>(nils.sync.pairing.until { it is PairingState.Failed })
        assertIs<PairingState.Failed>(stranger.sync.pairing.until { it is PairingState.Failed })
        assertFalse(stranger.sync.status.value.paired)
        assertTrue(stranger.repo.current.items.isEmpty())
        assertEquals(listOf("Nils"), nils.repo.current.members.map { it.name })

        // The QR code is used up: a second try with the same code reaches nobody.
        stranger.sync.join(code, "Lisa")
        assertIs<PairingState.Failed>(stranger.sync.pairing.until { it is PairingState.Failed })
    }

    @Test
    fun wrongSecretExpiredAndForeignCodesFail(): Unit = runBlocking {
        val nils = device("nils")
        val other = device("other")
        nils.onRepo { createFamily("Nils") }
        val code = invite(nils)
        val inv = Invitation.decode(code)!!
        val forged = inv.copy(secret = java.util.Base64.getEncoder().encodeToString(ByteArray(32))).encode()
        other.sync.join(forged, "Mallory")
        assertIs<PairingState.Failed>(other.sync.pairing.until { it is PairingState.Failed })
        assertIs<PairingState.Inviting>(nils.sync.pairing.value)

        other.sync.join("https://example.org", "x")
        assertEquals("Das ist kein FamilyApp-Code.", (other.sync.pairing.until { it is PairingState.Failed && it.message.contains("kein") } as PairingState.Failed).message)

        clock += Invitation.VALIDITY_MS + 1
        other.sync.join(code, "Lisa")
        assertTrue((other.sync.pairing.until { it is PairingState.Failed && it.message.contains("abgelaufen") } as PairingState.Failed).message.isNotEmpty())
        nils.sync.cancelPairing()
        assertEquals(PairingState.Idle, nils.sync.pairing.value)
    }

    @Test
    fun foreignFamilyDeviceCannotSync(): Unit = runBlocking {
        val nils = device("nils")
        val lisa = device("lisa")
        val foreign = device("foreign")
        val other = device("other")
        nils.onRepo { createFamily("Nils"); capture("Privat", Source.TEXT) }
        pair(nils, lisa, "Lisa")
        foreign.onRepo { createFamily("Fremd") }
        pair(foreign, other, "Other")
        // The foreign device learns Nils' address somehow and tries to sync.
        val address = "127.0.0.1:${nils.sync.listeningPort}"
        foreign.store.text = foreign.store.text!!.replace("\"devices\":[", "\"devices\":[{\"deviceId\":\"x\",\"name\":\"x\",\"addresses\":[\"$address\"]},")
        val reloaded = SyncController(
            foreign.repo, foreign.store, LoopbackTransport(), JcaSyncCrypto(), foreign.photos, foreign.scope, foreign.thread,
            Dispatchers.IO, "fremd", now = { clock }, preferredPort = 0,
        )
        reloaded.syncAll(manual = true)
        assertTrue(foreign.repo.current.items.none { it.title == "Privat" })
        assertTrue(nils.repo.current.members.none { it.name == "Fremd" })
    }

    @Test
    fun sharedFolderSyncsWithoutBothBeingOnline(): Unit = runBlocking {
        val nils = device("nils")
        val lisa = device("lisa")
        nils.onRepo { createFamily("Nils") }
        pair(nils, lisa, "Lisa")
        nils.sync.stop()
        lisa.sync.stop() // from now on no direct connection
        val folder = File(root, "syncthing").apply { mkdirs() }
        nils.sync.useFolder(folder.absolutePath)
        lisa.sync.useFolder(folder.absolutePath)
        nils.sync.status.until { it.folder != null }
        lisa.sync.status.until { it.folder != null }

        nils.photos.write("brief.jpg", "JPEG-Inhalt".encodeToByteArray())
        nils.onRepo { capture("Zahnarzt Mia Dienstag", Source.PHOTO, photoPath = nils.photos.pathFor("brief.jpg")) }
        nils.sync.syncAll()
        lisa.sync.syncAll()
        val item = lisa.onRepo { current.items.single { it.title == "Zahnarzt Mia Dienstag" } }
        assertEquals(lisa.photos.pathFor("brief.jpg"), item.photoPath)
        assertEquals("JPEG-Inhalt", lisa.photos.read("brief.jpg")!!.decodeToString())
        assertTrue(lisa.sync.status.value.lastResult!!.contains("Ordner (nils-Handy)"))

        lisa.onRepo { complete(item.id) }
        lisa.sync.syncAll()
        nils.sync.syncAll()
        assertEquals(lisa.repo.current.selfId, nils.onRepo { current.items.single { it.id == item.id }.doneBy })

        // The folder only sees ciphertext.
        folder.walkTopDown().filter { it.isFile }.forEach { f ->
            val text = f.readBytes().decodeToString()
            assertFalse(text.contains("Zahnarzt") || text.contains("JPEG") || text.contains("Nils"), f.name)
        }
        // Conflict copies and foreign files are ignored.
        File(folder, "${FolderSync.STATE_DIR}/x.sync-conflict-2026.fas").writeText("Müll")
        File(folder, "${FolderSync.STATE_DIR}/0123456789ab.fas").writeText("Müll")
        nils.sync.syncAll(manual = true)
        assertNull(nils.sync.status.value.problem)
        // A file too large to be ours (threat model OR-3) is skipped; the other devices still sync.
        RandomAccessFile(File(folder, "${FolderSync.STATE_DIR}/fedcba9876543210.fas"), "rw").use {
            it.setLength(BoundedRead.MAX_FILE_BYTES + 1) // sparse: costs no disk space
        }
        lisa.onRepo { capture("Nach der Riesendatei", Source.TEXT) }
        lisa.sync.syncAll()
        nils.sync.syncAll(manual = true)
        assertTrue(nils.onRepo { current.items.any { it.title == "Nach der Riesendatei" } })
    }

    @Test
    fun choosingAFolderFirstFoundsTheFamily(): Unit = runBlocking {
        val nils = device("nils")
        nils.onRepo { createFamily("Nils") }
        assertFalse(nils.sync.status.value.paired)
        nils.sync.useFolder(File(root, "f").absolutePath)
        nils.sync.status.until { it.paired && it.folder != null }
        nils.sync.status.until { it.lastResult?.contains("noch keine Daten") == true }
        nils.sync.useFolder(null)
        nils.sync.status.until { it.folder == null }
    }
}
