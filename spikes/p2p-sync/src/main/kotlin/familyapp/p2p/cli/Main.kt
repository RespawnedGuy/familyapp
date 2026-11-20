package familyapp.p2p.cli

import familyapp.p2p.core.Change
import familyapp.p2p.core.Endorsement
import familyapp.p2p.core.FamilyPeer
import familyapp.p2p.core.Membership
import familyapp.p2p.crypto.DeviceIdentity
import familyapp.p2p.crypto.FamilyCipher
import familyapp.p2p.crypto.b64
import familyapp.p2p.crypto.unb64
import familyapp.p2p.mailbox.FolderMailbox
import familyapp.p2p.mailbox.LocalFolderStore
import familyapp.p2p.net.PeerClient
import familyapp.p2p.net.PeerServer
import familyapp.p2p.pairing.Confirmation
import familyapp.p2p.pairing.Invitation
import familyapp.p2p.pairing.Inviter
import familyapp.p2p.pairing.Joiner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Spike CLI to try the sync on real machines (two laptops on one WLAN, or over Tailscale).
 * State lives in <dir>/peer.json, INCLUDING the private key and family key: test data only.
 */
fun main(args: Array<String>) = runBlocking {
    if (args.size < 2) usage()
    val dir = Paths.get(args[1])
    when (args[0]) {
        "init" -> init(dir, args.getOrElse(2) { usage() })
        "serve" -> serve(dir, args.getOrElse(2) { usage() }, args.drop(3))
        "join" -> join(dir, args.getOrElse(2) { usage() }, args.getOrElse(3) { usage() })
        "set" -> withPeer(dir) { peer, _ -> peer.set(args[2], args[3], args.getOrNull(4)); println("ok") }
        "sync" -> withPeer(dir) { peer, addresses ->
            val targets = args.getOrNull(2)?.let { listOf(it) } ?: addresses.filterKeys { it != peer.deviceId }.values.flatten()
            for (target in targets) {
                val result = runCatching { PeerClient.sync(target, peer) }
                println("$target: " + result.fold({ "sent ${it.sent}, received ${it.received}" }, { "unreachable (${it.message})" }))
            }
        }
        "mailbox" -> withPeer(dir) { peer, _ ->
            val stats = FolderMailbox(LocalFolderStore(Paths.get(args[2]))).sync(peer)
            println("mailbox: fetched ${stats.fetched}, published ${stats.published}")
        }
        "show" -> withPeer(dir) { peer, addresses ->
            println("device ${peer.deviceId} in family ${peer.familyId}")
            peer.membership.all().forEach { println("  member ${it.name} ${it.deviceId} ${addresses[it.deviceId] ?: emptyList()}") }
            println("version vector ${peer.versionVector()}")
            peer.state.snapshot().forEach { (k, v) -> println("  $k = $v") }
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    println(
        """
        usage:
          init <dir> <name>                    found a family on this device
          serve <dir> <port> [addr:port ...]   listen; with addresses also print a pairing code
          join <dir> <name> <code>             join via pairing code
          set <dir> <record> <field> [value]   local change
          sync <dir> [host:port]               sync with one peer or all known peers
          mailbox <dir> <folder>               sync via a shared folder
          show <dir>                           print state
        """.trimIndent(),
    )
    exitProcess(2)
}

@Serializable
private data class StateFile(
    val privateKey: String,
    val publicKey: String,
    val familyId: String,
    val familyKey: String,
    val members: List<Endorsement>,
    val changes: List<Change>,
    val addresses: Map<String, List<String>> = emptyMap(),
)

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

private fun stateFile(dir: Path) = dir.resolve("peer.json")

private fun load(dir: Path): Pair<FamilyPeer, MutableMap<String, List<String>>> {
    val s = json.decodeFromString<StateFile>(stateFile(dir).readText())
    val membership = Membership(s.familyId).also { it.merge(s.members) }
    val peer = FamilyPeer(DeviceIdentity.restore(s.privateKey, s.publicKey), unb64(s.familyKey), membership)
    s.changes.forEach { peer.receive(it) }
    return peer to s.addresses.toMutableMap()
}

private fun save(dir: Path, peer: FamilyPeer, addresses: Map<String, List<String>>) {
    Files.createDirectories(dir)
    val s = StateFile(
        peer.identity.exportPrivateKey(), peer.identity.publicKeyEncoded, peer.familyId,
        b64(peer.exportFamilyKey()), peer.membership.all(), peer.log.all(), addresses,
    )
    stateFile(dir).writeText(json.encodeToString(s))
}

private suspend fun withPeer(dir: Path, block: suspend (FamilyPeer, MutableMap<String, List<String>>) -> Unit) {
    val (peer, addresses) = load(dir)
    block(peer, addresses)
    save(dir, peer, addresses)
}

private fun init(dir: Path, name: String) {
    require(!stateFile(dir).exists()) { "$dir already holds a device" }
    val identity = DeviceIdentity.generate()
    val peer = FamilyPeer(identity, FamilyCipher.generateKey(), Membership.found(identity, name))
    save(dir, peer, emptyMap())
    println("family ${peer.familyId} founded by $name")
}

private val stdinConfirmation = Confirmation { prompt ->
    withContext(Dispatchers.IO) {
        println("Pairing with \"${prompt.otherDeviceName}\". Code: ${prompt.code}")
        print("Does the other screen show the same code? [y/N] ")
        readlnOrNull()?.trim()?.lowercase() == "y"
    }
}

private suspend fun serve(dir: Path, port: String, advertised: List<String>) {
    val (peer, addresses) = load(dir)
    val inviter = if (advertised.isNotEmpty()) Inviter(peer, advertised, { addresses.toMap() }) else null
    inviter?.createInvitation()?.let { println("pairing code (valid 10 min, once):\n${it.encode()}") }
    val server = PeerServer(peer, inviter, stdinConfirmation) { result ->
        println(result.fold({ it }, { "session failed: ${it.message}" }))
        save(dir, peer, addresses)
    }
    println("listening on port $port")
    coroutineScope { server.start(this, "0.0.0.0", port.toInt()).join() }
}

private suspend fun join(dir: Path, name: String, code: String) {
    require(!stateFile(dir).exists()) { "$dir already holds a device" }
    val invitation = Invitation.decode(code)
    val identity = DeviceIdentity.generate()
    for (address in invitation.addresses) {
        val raw = runCatching { PeerClient.connect(address) }.getOrNull() ?: continue.also { println("$address unreachable") }
        raw.send(PeerServer.MODE_PAIR.toByteArray())
        val joined = Joiner.join(raw, invitation, identity, name, stdinConfirmation)
        if (joined == null) { println("pairing declined"); return }
        save(dir, joined.peer, joined.knownAddresses)
        println("joined family ${joined.peer.familyId}; now run: sync $dir")
        return
    }
    println("no address of the inviting device was reachable")
}
