package io.github.respawnedguy.familyapp.data

import java.io.File

/**
 * Family data in the app's private storage (filesDir). Atomic replace: write a temp file, sync,
 * then rename, so a crash or a killed process never leaves a half-written file.
 */
class AndroidTextStore(private val file: File) : TextStore {

    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    @Synchronized
    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not replace ${file.name}" }
        }
    }

    override fun preserveBroken(text: String) {
        File(file.parentFile, "${file.nameWithoutExtension}-unlesbar-${System.currentTimeMillis()}.json").writeText(text)
    }
}
