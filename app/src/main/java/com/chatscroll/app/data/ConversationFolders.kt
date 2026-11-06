package com.chatscroll.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** One commit owns both folder identity and membership; bodies and sidecars are never authoritative. */
@Serializable
internal data class FolderCatalog(
    val format: Int = 1,
    val folders: List<ConversationFolder> = emptyList(),
    val assignments: Map<String, String> = emptyMap()
)

/** Called only under the repository's shared directory lock. */
internal class ConversationFolders(private val directory: File, private val json: Json) {
    private val file get() = File(directory, FILE_NAME)

    fun read(repair: Boolean = true): FolderCatalog {
        for (suffix in listOf("", ".bak")) {
            if (Files.isSymbolicLink(File(directory, FILE_NAME + suffix).toPath())) {
                throw ConversationStorageException("Invalid folder catalog")
            }
        }
        return RepositoryFiles.read(file, MAX_BYTES, repair) { bytes ->
            val fields = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val checksum = fields["_checksum"]?.jsonPrimitive?.content
                ?: throw ConversationStorageException("Folder catalog checksum is missing")
            val payload = JsonObject(fields.filterKeys { it != "_checksum" })
            if (ConversationFingerprint.sha256(json.encodeToString(payload).toByteArray(Charsets.UTF_8)) != checksum) {
                throw ConversationStorageException("Folder catalog checksum mismatch")
            }
            json.decodeFromJsonElement<FolderCatalog>(payload).also(::validate)
        } ?: FolderCatalog()
    }

    fun write(catalog: FolderCatalog) = RepositoryFiles.write(file, bytes(catalog))

    fun bytes(catalog: FolderCatalog): ByteArray {
        validate(catalog)
        val fields = json.encodeToJsonElement(catalog).jsonObject.toMutableMap()
        val payload = json.encodeToString(JsonObject(fields)).toByteArray(Charsets.UTF_8)
        fields["_checksum"] = JsonPrimitive(ConversationFingerprint.sha256(payload))
        val bytes = json.encodeToString(JsonObject(fields)).toByteArray(Charsets.UTF_8)
        FileConversationRepository.requireSize(bytes, MAX_BYTES, "Folder catalog")
        return bytes
    }

    /** Keep exact committed bytes so a failed backup restores both live state and its recovery state. */
    fun checkpoint(): Map<String, ByteArray> = listOf("", ".bak").mapNotNull { suffix ->
        val candidate = File(directory, FILE_NAME + suffix)
        if (!candidate.exists()) null else {
            if (!candidate.isFile || Files.isSymbolicLink(candidate.toPath()) || candidate.length() > MAX_BYTES) {
                throw ConversationStorageException("Invalid folder catalog checkpoint")
            }
            suffix to candidate.readBytes()
        }
    }.toMap()

    fun rollback(checkpoint: Map<String, ByteArray>) {
        val current = this.checkpoint()
        if (current.keys == checkpoint.keys && current.all { (suffix, bytes) -> bytes.contentEquals(checkpoint.getValue(suffix)) }) return
        val primary = checkpoint[""]
        val backup = checkpoint[".bak"]
        if (primary != null) RepositoryFiles.write(file, primary, backup ?: primary)
        else if (backup != null) RepositoryFiles.write(file, backup, backup)
        // Restore absence as well as bytes; never leave imported folders in a fallback checkpoint.
        listOf("", ".bak").forEach { suffix ->
            val candidate = File(directory, FILE_NAME + suffix)
            if (checkpoint[suffix] == null && candidate.exists() && !candidate.delete()) {
                throw ConversationStorageException("Cannot roll back folder catalog")
            }
        }
    }

    companion object {
        const val FILE_NAME = ".folders.catalog.json"
        const val MAX_BYTES = 2L * 1024 * 1024
        private val SUFFIXES = listOf("", ".bak", ".tmp", ".bak.tmp", ".recover.tmp")
        fun recognizes(path: String): Boolean = SUFFIXES.any { path == FILE_NAME + it }

        fun name(value: String): String {
            val trimmed = value.trim()
            require(trimmed.isNotBlank() && trimmed.length <= 60) { "Folder name must contain 1 to 60 characters" }
            return trimmed
        }

        fun validate(catalog: FolderCatalog) {
            require(catalog.format == 1) { "Unsupported folder catalog" }
            require(catalog.folders.map { it.id.lowercase(java.util.Locale.ROOT) }.distinct().size == catalog.folders.size) { "Duplicate folder IDs" }
            catalog.folders.forEach { folder ->
                FileConversationRepository.validateId(folder.id)
                require(name(folder.name) == folder.name) { "Folder name is not trimmed" }
            }
            require(catalog.folders.indices.none { index ->
                catalog.folders.take(index).any { it.name.equals(catalog.folders[index].name, ignoreCase = true) }
            }) { "Duplicate folder names" }
            val ids = catalog.folders.map { it.id }.toSet()
            catalog.assignments.forEach { (id, folder) ->
                FileConversationRepository.validateId(id)
                require(folder in ids) { "Unknown folder assignment" }
            }
        }

        /** Restore always keeps an incoming folder separate from an existing folder, even on name collision. */
        fun merge(existing: FolderCatalog, incoming: List<ConversationFolder>): Pair<List<ConversationFolder>, Map<String, String>> {
            val folders = existing.folders.toMutableList()
            val remap = linkedMapOf<String, String>()
            incoming.forEach { folder ->
                var id = folder.id
                while (folders.any { it.id.equals(id, ignoreCase = true) }) id = UUID.randomUUID().toString()
                var candidate = folder.name
                var counter = 2
                while (folders.any { it.name.equals(candidate, ignoreCase = true) }) {
                    val suffix = " ($counter)"
                    candidate = folder.name.take(60 - suffix.length).trimEnd() + suffix
                    counter++
                }
                folders.add(folder.copy(id = id, name = candidate))
                remap[folder.id] = id
            }
            return folders to remap
        }
    }
}
