package io.github.respawnedguy.familyapp.data

import io.github.respawnedguy.familyapp.presentation.BackupContent
import io.github.respawnedguy.familyapp.presentation.RestoreRead
import java.io.File

/** The few file operations a backup needs; Android implements them with the Storage Access Framework. */
interface BackupFiles {
    /** Names of the files in the folder ([sub] = null) or in one sub folder. */
    fun list(sub: String?): List<String>
    fun read(sub: String?, name: String): ByteArray?
    fun write(sub: String?, name: String, data: ByteArray)
    fun delete(sub: String?, name: String)
}

/**
 * Layout of an encrypted backup in the chosen folder (P0-3, P0-4):
 * - `familyapp-sicherung.fab`: all family data (JSON), encrypted
 * - `familyapp-fotos/<photo>.<code tag>.fab`: one encrypted file per photo, written once, removed
 *   when unused or when the code changed (then written again with the new code)
 * An older plain `familyapp-sicherung.json` is deleted after the first encrypted backup succeeded,
 * because it holds the same data unencrypted.
 */
class BackupFolder(private val files: BackupFiles, private val codec: BackupCrypto = BackupCrypto) {

    fun write(backup: BackupContent) {
        val tag = codec.codeTag(backup.code)
        val photos = backup.photoPaths.map(::File).filter { it.isFile && safeName(it.name) }
        val wanted = photos.map { "${it.name}.$tag$EXT" }.toSet()
        val present = files.list(PHOTO_DIR).toSet()
        photos.filter { "${it.name}.$tag$EXT" !in present }.forEach { photo ->
            files.write(PHOTO_DIR, "${photo.name}.$tag$EXT", codec.encrypt(photo.readBytes(), backup.code, photo.name))
        }
        files.write(null, DATA_FILE, codec.encrypt(backup.json.encodeToByteArray(), backup.code, DATA_FILE))
        (present - wanted).filter { it.endsWith(EXT) }.forEach { files.delete(PHOTO_DIR, it) }
        if (LEGACY_FILE in files.list(null)) files.delete(null, LEGACY_FILE)
    }

    /** Reads the data file and unpacks every photo into [photoDir]. */
    @Suppress("SwallowedException") // a wrong code is an expected answer, not an error to keep
    fun read(code: String?, photoDir: File): RestoreRead {
        val names = files.list(null)
        if (DATA_FILE !in names) {
            if (LEGACY_FILE in names) {
                val text = files.read(null, LEGACY_FILE)?.decodeToString() ?: return RestoreRead.Failed("Sicherung nicht lesbar")
                return RestoreRead.Ok(text, emptyMap())
            }
            return RestoreRead.Failed("In diesem Ordner liegt keine FamilyApp-Sicherung.")
        }
        if (code.isNullOrBlank()) return RestoreRead.NeedsCode
        val data = files.read(null, DATA_FILE) ?: return RestoreRead.Failed("Sicherung nicht lesbar")
        val json = try {
            codec.decrypt(data, code, DATA_FILE).decodeToString()
        } catch (e: WrongCodeException) {
            return RestoreRead.WrongCode
        }
        photoDir.mkdirs()
        val tag = codec.codeTag(code)
        val photos = files.list(PHOTO_DIR).filter { it.endsWith(".$tag$EXT") }.mapNotNull { stored ->
            val name = stored.removeSuffix(".$tag$EXT")
            if (!safeName(name)) return@mapNotNull null
            val bytes = files.read(PHOTO_DIR, stored) ?: return@mapNotNull null
            val plain = runCatching { codec.decrypt(bytes, code, name) }.getOrNull() ?: return@mapNotNull null
            val target = File(photoDir, name)
            target.writeBytes(plain)
            name to target.absolutePath
        }.toMap()
        return RestoreRead.Ok(json, photos)
    }

    companion object {
        const val DATA_FILE = "familyapp-sicherung.fab"
        const val LEGACY_FILE = "familyapp-sicherung.json"
        const val PHOTO_DIR = "familyapp-fotos"
        const val EXT = ".fab"

        /** Only plain file names: nothing from a backup may write outside the photo folder. */
        fun safeName(name: String): Boolean = name.isNotEmpty() && name.length <= 128 && name.all { it.isLetterOrDigit() || it in "._-" } && !name.startsWith(".")
    }
}
