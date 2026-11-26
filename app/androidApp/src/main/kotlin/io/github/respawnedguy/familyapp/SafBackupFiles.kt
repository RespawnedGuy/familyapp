package io.github.respawnedguy.familyapp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.respawnedguy.familyapp.data.BackupFiles

/**
 * [BackupFiles] over a folder the person picked with the Storage Access Framework (Synology Drive,
 * Google Drive, local storage). Sub folders are created on first write.
 */
class SafBackupFiles(private val resolver: ContentResolver, private val tree: Uri) : BackupFiles {

    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    private fun children(parentId: String): List<Triple<String, String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = mutableListOf<Triple<String, String, String>>()
        resolver.query(uri, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { c -> while (c.moveToNext()) result += Triple(c.getString(0), c.getString(1), c.getString(2) ?: "") }
        return result
    }

    private fun dirId(sub: String?, create: Boolean): String? {
        if (sub == null) return rootId
        children(rootId).firstOrNull { it.second == sub && it.third == Document.MIME_TYPE_DIR }?.let { return it.first }
        if (!create) return null
        val created = DocumentsContract.createDocument(resolver, docUri(rootId), Document.MIME_TYPE_DIR, sub)
            ?: error("Ordner $sub ließ sich nicht anlegen")
        return DocumentsContract.getDocumentId(created)
    }

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun find(sub: String?, name: String): Uri? {
        val dir = dirId(sub, create = false) ?: return null
        return children(dir).firstOrNull { it.second == name && it.third != Document.MIME_TYPE_DIR }?.let { docUri(it.first) }
    }

    override fun list(sub: String?): List<String> {
        val dir = dirId(sub, create = false) ?: return emptyList()
        return children(dir).filter { it.third != Document.MIME_TYPE_DIR }.map { it.second }
    }

    override fun read(sub: String?, name: String): ByteArray? =
        find(sub, name)?.let { uri -> resolver.openInputStream(uri)?.use { it.readBytes() } }

    override fun write(sub: String?, name: String, data: ByteArray) {
        val uri = find(sub, name)
            ?: DocumentsContract.createDocument(resolver, docUri(dirId(sub, create = true)!!), "application/octet-stream", name)
            ?: error("Datei $name ließ sich nicht anlegen")
        resolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: error("Datei $name ließ sich nicht schreiben")
    }

    override fun delete(sub: String?, name: String) {
        find(sub, name)?.let { DocumentsContract.deleteDocument(resolver, it) }
    }
}
