package io.github.respawnedguy.familyapp.sync

import io.github.respawnedguy.familyapp.data.BoundedRead
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private val posix: Boolean by lazy {
    runCatching { "posix" in FileSystems.getDefault().supportedFileAttributeViews() }.getOrDefault(false)
}

/**
 * Writes a file atomically: temp file, then rename, so a crash never leaves half a file. With
 * [ownerOnly] the file is readable by its owner only where the file system knows POSIX rights
 * (house device on Linux or a NAS: other accounts on the machine must not read the family key,
 * threat model HG-1). The temp file gets the rights before any byte is written.
 */
internal fun writeAtomically(target: File, data: ByteArray, ownerOnly: Boolean = false) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    if (ownerOnly && posix) {
        tmp.delete()
        val ownerOnlyRights = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.createFile(tmp.toPath(), ownerOnlyRights)
    }
    tmp.writeBytes(data)
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (@Suppress("SwallowedException") e: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** [SyncState] with the family key in a private file (never in the family data, never in backups), owner-only. */
class FileSyncStore(private val file: File) : SyncStore {
    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    @Synchronized
    override fun write(text: String) = writeAtomically(file, text.toByteArray(Charsets.UTF_8), ownerOnly = true)
}

/** Photos in the private photo folder; names are checked by [PhotoFiles.isSafeName] before use. */
class DirPhotoFiles(private val dir: File) : PhotoFiles {
    override fun pathFor(name: String): String = File(dir, name).absolutePath
    override fun exists(name: String): Boolean = PhotoFiles.isSafeName(name) && File(dir, name).isFile
    override fun read(name: String): ByteArray? =
        File(dir, name).takeIf { PhotoFiles.isSafeName(name) && it.isFile }?.let { BoundedRead.readAtMost(it) }
    override fun write(name: String, bytes: ByteArray) {
        require(PhotoFiles.isSafeName(name)) { "Ungültiger Fotoname" }
        writeAtomically(File(dir, name), bytes)
    }
}

/** A shared folder on the file system (Syncthing folder, mounted Synology share). Used by the house device. */
class LocalFolderFiles(private val root: File) : SyncFolderFiles {
    override fun list(sub: String): List<String> =
        File(root, sub).listFiles().orEmpty().filter { it.isFile }.map { it.name }

    override fun read(sub: String, name: String): ByteArray? =
        File(File(root, sub), name).takeIf { it.isFile }?.let { BoundedRead.readAtMost(it) }

    override fun write(sub: String, name: String, data: ByteArray) = writeAtomically(File(File(root, sub), name), data)
}
