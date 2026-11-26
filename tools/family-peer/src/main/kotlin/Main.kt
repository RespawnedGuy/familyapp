package io.github.respawnedguy.familyapp.peer

import io.github.respawnedguy.familyapp.data.AndroidTextStore
import io.github.respawnedguy.familyapp.data.FamilyJson
import io.github.respawnedguy.familyapp.data.FamilyRepository
import io.github.respawnedguy.familyapp.domain.Source
import io.github.respawnedguy.familyapp.sync.DirPhotoFiles
import io.github.respawnedguy.familyapp.sync.FileSyncStore
import io.github.respawnedguy.familyapp.sync.FrameChannel
import io.github.respawnedguy.familyapp.sync.JcaSyncCrypto
import io.github.respawnedguy.familyapp.sync.LocalFolderFiles
import io.github.respawnedguy.familyapp.sync.PairingState
import io.github.respawnedguy.familyapp.sync.SyncController
import io.github.respawnedguy.familyapp.sync.SyncTransport
import io.github.respawnedguy.familyapp.sync.TcpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toKotlinLocalDate
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private const val USAGE = """FamilyApp-Hausgerät (gleicher Sync-Code wie die App, ADR 0003/0006)

  family-peer join  <dir> <code> [--name Hausgerät] [--via host:port] [--yes]
      Tritt der Familie bei (Code = Text des QR-Codes, in der App unter „Code als Text zeigen“).
  family-peer serve <dir> [--folder <pfad>] [--port 47420]
      Bleibt erreichbar und gleicht jede Minute ab; optional zusätzlich über einen geteilten Ordner.
  family-peer sync  <dir> [--via host:port] [--folder <pfad>]
      Einmal abgleichen und beenden.
  family-peer init   <dir> <name>     Neue Familie anlegen (nur zum Ausprobieren ohne Handy)
  family-peer invite <dir>            Code zeigen und Kopplung bestätigen (zum Ausprobieren)
  family-peer add   <dir> <text>      Eintrag erfassen (zum Testen)
  family-peer show  <dir>             Personen und Einträge anzeigen

<dir> enthält familyapp.json, sync.json (mit Familienschlüssel!) und photos/. Nur für das Hausgerät,
nie in ein Repository oder einen geteilten Ordner legen."""

/** Sends every connection to one address, e.g. an adb port forward to the emulator. */
private class ViaTransport(private val tcp: TcpTransport, private val via: String) : SyncTransport by tcp {
    override suspend fun connect(address: String, connectTimeoutMs: Int, readTimeoutMs: Int): FrameChannel =
        tcp.connect(via, connectTimeoutMs, readTimeoutMs)
}

private class Peer(dir: File, options: Map<String, String>) {
    val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = AndroidTextStore(File(dir, "familyapp.json"))
    val repo = FamilyRepository(
        store = store,
        scope = scope,
        ioDispatcher = Dispatchers.IO,
        now = System::currentTimeMillis,
        today = { java.time.LocalDate.now().toKotlinLocalDate() },
        newId = { UUID.randomUUID().toString() },
    )
    private val tcp = TcpTransport()
    val sync = SyncController(
        repo = repo,
        store = FileSyncStore(File(dir, "sync.json")),
        transport = options["via"]?.let { ViaTransport(tcp, it) } ?: tcp,
        crypto = JcaSyncCrypto(),
        photos = DirPhotoFiles(File(dir, "photos").apply { mkdirs() }),
        scope = scope,
        repoDispatcher = thread,
        io = Dispatchers.IO,
        deviceName = options["name"] ?: "Hausgerät",
        folders = { LocalFolderFiles(File(it)) },
        preferredPort = options["port"]?.toIntOrNull() ?: SyncController.DEFAULT_PORT,
    )

    /** Writes the family data now (the repository saves in the background otherwise). */
    suspend fun save() = withContext(thread) { store.write(FamilyJson.encode(repo.current)) }
}

fun main(args: Array<String>) {
    val positional = mutableListOf<String>()
    val options = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--yes" -> options["yes"] = "true"
            a.startsWith("--") && i + 1 < args.size -> options[a.removePrefix("--")] = args[++i]
            else -> positional += a
        }
        i++
    }
    if (positional.size < 2) {
        println(USAGE)
        exitProcess(2)
    }
    val dir = File(positional[1]).apply { mkdirs() }
    val ok = runBlocking { run(positional[0], positional.drop(2), dir, options) }
    exitProcess(if (ok) 0 else 1)
}

private suspend fun run(command: String, rest: List<String>, dir: File, options: Map<String, String>): Boolean {
    val peer = Peer(dir, options)
    return when (command) {
        "join" -> join(peer, rest.firstOrNull() ?: return usage(), options["name"] ?: "Hausgerät", options.containsKey("yes"))
        "init" -> {
            withContext(peer.thread) { peer.repo.createFamily(rest.firstOrNull() ?: return@withContext) }
            peer.save()
            println("Familie angelegt.")
            true
        }
        "invite" -> invite(peer)
        "serve" -> serve(peer, options["folder"])
        "sync" -> {
            options["folder"]?.let { peer.sync.useFolder(it); delay(500) }
            peer.sync.syncAll(manual = true)
            val status = peer.sync.status.value
            println(status.lastResult ?: "Nichts abgeglichen.")
            status.problem?.let { println("Problem: $it") }
            peer.save()
            status.problem == null && status.lastResult?.contains("empfangen") == true
        }
        "add" -> {
            val text = rest.joinToString(" ").ifBlank { return usage() }
            withContext(peer.thread) { peer.repo.capture(text, Source.TEXT) }
            peer.save()
            println("Erfasst: $text")
            true
        }
        "show" -> {
            val d = withContext(peer.thread) { peer.repo.current }
            println("Personen: " + d.members.joinToString(", ") { it.name + if (it.hasDevice) " (Handy)" else "" })
            d.items.forEach { println("- [${it.status}] ${it.title}" + (it.assignee?.let { a -> " → " + (d.member(a)?.name ?: "?") } ?: "")) }
            println("Geräte: " + peer.sync.status.value.devices.joinToString(", ") { it.name })
            true
        }
        else -> usage()
    }
}

private fun usage(): Boolean {
    println(USAGE)
    return false
}

private suspend fun join(peer: Peer, code: String, name: String, yes: Boolean): Boolean {
    peer.sync.join(code, name, houseDevice = true)
    val result = withTimeout(SyncController.DECISION_TIMEOUT_MS) {
        peer.sync.pairing.first { it is PairingState.Confirm || it is PairingState.Failed }
    }
    if (result is PairingState.Failed) {
        println("Fehlgeschlagen: ${result.message}")
        return false
    }
    result as PairingState.Confirm
    println("Prüfzahl: ${result.sas.take(3)} ${result.sas.drop(3)}  (Familie von ${result.other.personName} · ${result.other.deviceName})")
    val accept = yes || run {
        print("Steht auf dem anderen Gerät dieselbe Zahl? [j/N] ")
        readlnOrNull()?.trim()?.lowercase() in setOf("j", "ja", "y", "yes")
    }
    peer.sync.answer(accept)
    val end = withTimeout(SyncController.DECISION_TIMEOUT_MS) {
        peer.sync.pairing.first { it is PairingState.Done || it is PairingState.Failed }
    }
    println(if (end is PairingState.Done) "Gekoppelt: ${end.message}" else "Fehlgeschlagen: ${(end as PairingState.Failed).message}")
    peer.sync.stop()
    peer.save()
    return end is PairingState.Done
}

/** For trying it out between two computers: shows the code, accepts the suggested owner. */
private suspend fun invite(peer: Peer): Boolean {
    peer.sync.invite()
    val shown = withTimeout(INVITE_WAIT_MS) { peer.sync.pairing.first { it is PairingState.Inviting || it is PairingState.Failed } }
    if (shown !is PairingState.Inviting) {
        println("Fehlgeschlagen: ${(shown as PairingState.Failed).message}")
        return false
    }
    println(shown.code)
    val confirm = withTimeout(SyncController.DECISION_TIMEOUT_MS) {
        peer.sync.pairing.first { it is PairingState.Confirm || it is PairingState.Failed }
    }
    if (confirm !is PairingState.Confirm) return false
    println("Prüfzahl: ${confirm.sas.take(3)} ${confirm.sas.drop(3)}  (${confirm.other.personName} · ${confirm.other.deviceName}) → ${confirm.suggested?.label}")
    print("Stimmt die Zahl? [j/N] ")
    val accept = readlnOrNull()?.trim()?.lowercase() in setOf("j", "ja", "y", "yes")
    peer.sync.answer(accept, confirm.suggested)
    val end = withTimeout(SyncController.DECISION_TIMEOUT_MS) {
        peer.sync.pairing.first { it is PairingState.Done || it is PairingState.Failed }
    }
    println(if (end is PairingState.Done) end.message else (end as PairingState.Failed).message)
    peer.save()
    return end is PairingState.Done
}

private suspend fun serve(peer: Peer, folder: String?): Boolean {
    if (!peer.sync.status.value.paired) {
        println("Noch nicht gekoppelt: zuerst „join“.")
        return false
    }
    folder?.let { peer.sync.useFolder(it) }
    peer.sync.start()
    println("Hausgerät läuft auf Port ${peer.sync.listeningPort}. Beenden mit Strg+C.")
    var last: String? = null
    while (true) {
        delay(SAVE_INTERVAL_MS)
        peer.save()
        val now = peer.sync.status.value.lastResult
        if (now != last) println(now)
        last = now
    }
}

private const val SAVE_INTERVAL_MS = 10_000L
private const val INVITE_WAIT_MS = 10_000L
