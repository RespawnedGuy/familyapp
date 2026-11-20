package familyapp.p2p

import familyapp.p2p.mailbox.FolderMailbox
import familyapp.p2p.mailbox.LocalFolderStore
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Shared cloud folder as second transport behind the same peer API.
 * A only uses the folder (e.g. an iPhone away from home), C only the LAN, B both.
 */
class MailboxTest {

    @Test
    fun `cloud folder and LAN mix, never all at once, same end state, folder holds no plaintext`() = runTest {
        val folder = Files.createTempDirectory("mailbox")
        val mailbox = FolderMailbox(LocalFolderStore(folder))
        val (a, b, c) = TestFamily(listOf("A", "B", "C")).peers
        val random = Random(2026)

        repeat(600) { i ->
            val peer = listOf(a, b, c)[random.nextInt(3)]
            peer.set("eintrag${random.nextInt(30)}", "title", "Geheimer Arzttermin $i")
            when (random.nextInt(30)) {
                0 -> mailbox.sync(a)
                1 -> mailbox.sync(b)
                2 -> encounter(b, c)
            }
        }
        mailbox.sync(a); mailbox.sync(b); encounter(b, c); mailbox.sync(b); mailbox.sync(a)

        assertEquals(a.state.snapshot(), b.state.snapshot())
        assertEquals(b.state.snapshot(), c.state.snapshot())
        assertEquals(600, a.log.size())

        val files = Files.walk(folder).use { s -> s.filter { it.isRegularFile() }.toList() }
        assertTrue(files.isNotEmpty())
        files.forEach { f -> assertFalse(f.readBytes().decodeToString().contains("Arzttermin"), "plaintext in $f") }
    }

    @Test
    fun `conflict copies and double publishing do no harm`() = runTest {
        val folder = Files.createTempDirectory("mailbox")
        val mailbox = FolderMailbox(LocalFolderStore(folder))
        val (a, b) = TestFamily(listOf("A", "B")).peers
        repeat(10) { a.set("r$it", "f", "v") }
        mailbox.publish(a)
        // A cloud client creates a conflict copy of a file.
        val original = Files.walk(folder.resolve("changes")).use { s -> s.filter { it.isRegularFile() }.findFirst().get() }
        original.resolveSibling(original.fileName.toString() + " (1)").writeBytes(original.readBytes())
        // B learned the same changes over the LAN and publishes too.
        encounter(a, b)
        mailbox.publish(b)
        val fresh = TestFamily(listOf("X")).peers.single() // different family: must not apply anything
        val c = b.let { familyapp.p2p.core.FamilyPeer(it.identity, it.exportFamilyKey(), it.membership.copy()) }
        assertEquals(0, c.log.size())
        assertEquals(10, mailbox.fetch(c))
        assertEquals(a.state.snapshot(), c.state.snapshot())
        assertTrue(runCatching { mailbox.fetch(fresh) }.isFailure, "foreign family's device rejects the folder content")
    }
}
