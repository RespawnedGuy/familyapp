package io.github.respawnedguy.familyapp.data

import java.io.File

/**
 * Family data in the app's private storage (filesDir). Atomic replace: write a temp file, sync,
 * then rename, so a crash or a killed process never leaves a half-written file.
 */
class AndroidTextStore(private val file: File) : TextStore {

    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    private val snapshotFile = File(file.parentFile, "${file.nameWithoutExtension}-vor-wiederherstellung.json")

    @Synchronized
    override fun write(text: String) = writeAtomically(file, text)

    override fun readSnapshot(): String? = if (snapshotFile.exists()) snapshotFile.readText(Charsets.UTF_8) else null

    @Synchronized
    override fun writeSnapshot(text: String?) {
        if (text == null) snapshotFile.delete() else writeAtomically(snapshotFile, text)
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "Could not replace ${target.name}" }
            }
        } finally {
            // A half-written temp file (disk full) must not eat the remaining space.
            if (tmp.exists()) tmp.delete()
        }
    }

    override fun preserveBroken(text: String) {
        File(file.parentFile, "${file.nameWithoutExtension}-unlesbar-${System.currentTimeMillis()}.json").writeText(text)
    }
}
