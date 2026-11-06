package com.chatscroll.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable ZIPs contain no local paths: body + authoritative metadata, original source, and referenced assets. */
internal class ConversationBackup(private val repository: FileConversationRepository) {
    @Serializable
    private data class Manifest(
        val format: Int = 1,
        val records: List<Record>,
        val folders: List<ConversationFolder> = emptyList(),
        val assignments: Map<String, String> = emptyMap()
    )
    @Serializable
    private data class Record(
        val id: String,
        val body: String,
        val bodyHash: String,
        val source: String? = null,
        val sourceHash: String? = null
    )
    private data class ValidatedRecord(val id: String, val body: File, val source: File?)

    fun export(output: OutputStream): Int {
        val summaries = repository.list()
        if (repository.issues().isNotEmpty()) throw ConversationStorageException("Repair unreadable conversations before exporting a complete backup")
        if (summaries.size > MAX_RECORDS) throw ConversationStorageException("Backup contains too many conversations")
        val catalog = repository.folderCatalog()
        val exportedIds = summaries.map { it.id }.toSet()
        val records = ArrayList<Record>()
        val exportedAssets = HashSet<String>()
        var total = 0L
        var entryCount = 0
        val zip = ZipOutputStream(object : FilterOutputStream(output) {
            override fun close() { flush() } // Stream ownership belongs to the caller.
        })
        zip.use {
            fun entry(path: String, bytes: ByteArray) {
                total += bytes.size
                entryCount++
                if (entryCount > MAX_ENTRIES || bytes.size.toLong() > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES ||
                    (path == MANIFEST && bytes.size.toLong() > FileConversationRepository.MAX_METADATA_BYTES)) {
                    throw ConversationStorageException("Backup exceeds size limit")
                }
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            summaries.forEach { summary ->
                val conversation = repository.portableConversation(summary.id)
                val bodyPath = "conversations/${summary.id}.json"
                val body = repository.json.encodeToString(conversation).toByteArray(Charsets.UTF_8)
                entry(bodyPath, body)
                val source = repository.readSource(summary.id)?.toByteArray(Charsets.UTF_8)
                val sourcePath = source?.let { "sources/${summary.id}.md" }
                if (source != null) entry(sourcePath!!, source)
                records.add(Record(summary.id, bodyPath, ConversationFingerprint.sha256(body), sourcePath,
                    source?.let(ConversationFingerprint::sha256)))
                conversation.messages.flatMap { it.images }.forEach { image ->
                    if (ConversationFingerprint.assetPattern.matches(image) && exportedAssets.add(image)) {
                        entry(assetPath(image), repository.imageData(summary.id, image))
                    }
                }
            }
            entry(MANIFEST, repository.json.encodeToString(Manifest(format = 2, records = records,
                folders = catalog.folders, assignments = catalog.assignments.filterKeys { it in exportedIds })).toByteArray(Charsets.UTF_8))
        }
        return summaries.size
    }

    fun restore(input: InputStream): Int {
        val staging = File(repository.directory, ".restore-${UUID.randomUUID()}")
        RepositoryFiles.ensureDirectory(staging)
        var failure: Throwable? = null
        val created = ArrayList<String>()
        var folderCheckpoint: Map<String, ByteArray>? = null
        var catalogCommitAttempted = false
        fun rollback(error: Throwable) {
            folderCheckpoint?.takeIf { catalogCommitAttempted }?.let { checkpoint ->
                try { repository.restoreFolderCheckpoint(checkpoint) }
                catch (rollback: Throwable) { error.addSuppressed(rollback) }
            }
            folderCheckpoint = null
            created.asReversed().forEach { id ->
                try { repository.removeRestored(id) } catch (rollback: Throwable) { error.addSuppressed(rollback) }
            }
            created.clear()
        }
        try {
            // ZipFile requires a central directory, rejecting truncated ZIPs that ZipInputStream can accept.
            val archive = File(staging, "archive.zip")
            archive.outputStream().use { copyBounded(input, it, MAX_TOTAL_BYTES) }
            val entriesDirectory = File(staging, "entries")
            RepositoryFiles.ensureDirectory(entriesDirectory)
            val entries = unpack(archive, entriesDirectory)
            val manifestFile = entries[MANIFEST] ?: throw ConversationStorageException("Backup manifest is missing")
            if (manifestFile.length() > FileConversationRepository.MAX_METADATA_BYTES) throw ConversationStorageException("Backup manifest is too large")
            val manifest = decode<Manifest>(manifestFile)
            if (manifest.format !in 1..2 || manifest.records.size > MAX_RECORDS) throw ConversationStorageException("Unsupported backup format")
            if (manifest.records.map { it.id.lowercase(java.util.Locale.ROOT) }.distinct().size != manifest.records.size) {
                throw ConversationStorageException("Duplicate conversation IDs in backup")
            }
            if (manifest.format == 1 && (manifest.folders.isNotEmpty() || manifest.assignments.isNotEmpty())) {
                throw ConversationStorageException("Folders require backup format 2")
            }
            val incomingCatalog = FolderCatalog(folders = manifest.folders, assignments = manifest.assignments)
            try { ConversationFolders.validate(incomingCatalog) }
            catch (error: IllegalArgumentException) { throw ConversationStorageException("Invalid backup folders", error) }
            val incomingIds = manifest.records.map { it.id }.toSet()
            if (manifest.assignments.keys.any { it !in incomingIds }) {
                throw ConversationStorageException("Assignment references an absent conversation")
            }
            val expected = hashSetOf(MANIFEST)
            val validated = manifest.records.map { record ->
                FileConversationRepository.validateId(record.id)
                if (record.body != "conversations/${record.id}.json") throw ConversationStorageException("Invalid conversation entry path")
                expected.add(record.body)
                val body = checkedEntry(entries, record.body, record.bodyHash)
                val conversation = decode<Conversation>(body)
                if (conversation.id != record.id || !conversation.contentHash.startsWith(ConversationFingerprint.PREFIX)) {
                    throw ConversationStorageException("Invalid conversation identity or fingerprint")
                }
                if (manifest.format == 2 && conversation.folderId != manifest.assignments[record.id]) {
                    throw ConversationStorageException("Conversation folder does not match backup assignment")
                }
                if ((conversation.sourceSizeBytes ?: 0) < 0) throw ConversationStorageException("Invalid original source size")
                val source = if (record.source != null) {
                    if (record.source != "sources/${record.id}.md" || record.sourceHash == null || !conversation.hasSource) {
                        throw ConversationStorageException("Invalid source entry")
                    }
                    expected.add(record.source)
                    checkedEntry(entries, record.source, record.sourceHash)
                } else {
                    if (record.sourceHash != null || conversation.hasSource) throw ConversationStorageException("Missing source entry")
                    null
                }
                if (source != null && conversation.sourceSizeBytes != null && conversation.sourceSizeBytes != source.length()) {
                    throw ConversationStorageException("Original source size does not match retained source")
                }
                val computed = ConversationFingerprint.hash(conversation.messages) { image ->
                    if (ConversationFingerprint.assetPattern.matches(image)) {
                        val path = assetPath(image)
                        expected.add(path)
                        val file = checkedEntry(entries, path, image.removePrefix("asset:"))
                        ConversationFingerprint.readImage(file)
                    } else {
                        // A backup must never smuggle host-local paths or embedded unbounded data.
                        if (image.startsWith("data:", true) || image.startsWith("file:", true)) {
                            throw ConversationStorageException("Non-portable image reference in backup")
                        }
                        repository.validateExternalImage(image)
                        null
                    }
                }
                if (computed != conversation.contentHash) throw ConversationStorageException("Conversation fingerprint mismatch")
                if (conversation.bookmarks.any { it !in conversation.messages.indices } ||
                    conversation.scrollBlock < 0 || (conversation.scrollOffset ?: 0) < 0 ||
                    conversation.scrollIndex?.let { it < 0 || it > (conversation.messages.size - 1).coerceAtLeast(0) } == true) {
                    throw ConversationStorageException("Invalid reading metadata")
                }
                ValidatedRecord(record.id, body, source)
            }
            if (entries.keys != expected) throw ConversationStorageException("Backup contains unexpected files")
            // Validate folder remapping and reserve the complete catalog before any live writes.
            val existingCatalog = repository.folderCatalog()
            val (mergedFolders, folderIds) = ConversationFolders.merge(existingCatalog, manifest.folders)
            val assignments = existingCatalog.assignments.toMutableMap()
            val restoredIds = linkedMapOf<String, String>()
            validated.forEach { record ->
                var id = record.id
                while (repository.hasId(id) || restoredIds.values.any { it.equals(id, ignoreCase = true) } || id in assignments) {
                    id = UUID.randomUUID().toString()
                }
                restoredIds[record.id] = id
                manifest.assignments[record.id]?.let { assignments[id] = folderIds.getValue(it) }
            }
            val restoredCatalog = FolderCatalog(folders = mergedFolders, assignments = assignments)
            repository.validateFolderCatalog(restoredCatalog)
            // No live conversation or catalog writes have occurred before this point.
            folderCheckpoint = repository.folderCheckpoint()
            try {
                validated.forEach { record ->
                    val id = restoredIds.getValue(record.id)
                    if (repository.hasId(id)) throw ConversationStorageException("Restore destination changed")
                    created.add(id) // Include the in-progress record in rollback.
                    val conversation = decode<Conversation>(record.body).copy(id = id, folderId = null)
                    conversation.messages.flatMap { it.images }.distinct().forEach { image ->
                        if (ConversationFingerprint.assetPattern.matches(image)) {
                            repository.installAsset(id, image, entries.getValue(assetPath(image)).readBytes())
                        }
                    }
                    repository.save(conversation, record.source?.readText(Charsets.UTF_8))
                }
                if (restoredCatalog != existingCatalog) {
                    catalogCommitAttempted = true
                    repository.writeFolderCatalog(restoredCatalog)
                }
            } catch (error: Throwable) {
                rollback(error)
                throw error
            }
            return validated.size
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { RepositoryFiles.deleteTree(staging) }
            catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup)
                else { rollback(cleanup); throw cleanup }
            }
        }
    }

    private fun unpack(archive: File, destination: File): Map<String, File> {
        val extracted = LinkedHashMap<String, File>()
        val caseInsensitiveNames = HashSet<String>()
        var total = 0L
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    val segments = name.split('/')
                    if (entry.isDirectory || name.isEmpty() || name.length > 240 || name.startsWith('/') ||
                        '\\' in name || ':' in name || segments.any { it.isEmpty() || it == "." || it == ".." } ||
                        !name.matches(Regex("[A-Za-z0-9_./-]+")) || !caseInsensitiveNames.add(name.lowercase())) {
                        throw ConversationStorageException("Unsafe or duplicate ZIP entry")
                    }
                    if (extracted.size >= MAX_ENTRIES || entry.size < 0 || entry.size > MAX_ENTRY_BYTES || total + entry.size > MAX_TOTAL_BYTES) {
                        throw ConversationStorageException("Backup exceeds size limit")
                    }
                    val file = File(destination, name)
                    if (!file.canonicalPath.startsWith(destination.canonicalPath + File.separator)) throw ConversationStorageException("Unsafe ZIP entry path")
                    RepositoryFiles.ensureDirectory(file.parentFile!!)
                    val crc = CRC32()
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                total += count
                                if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) throw ConversationStorageException("Backup exceeds size limit")
                                crc.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    if (size != entry.size || crc.value != entry.crc) throw ConversationStorageException("Corrupt ZIP entry")
                    extracted[name] = file
                }
            }
        } catch (error: java.io.IOException) {
            throw ConversationStorageException("Cannot read backup archive", error)
        }
        return extracted
    }

    private fun checkedEntry(entries: Map<String, File>, name: String, hash: String): File {
        val file = entries[name] ?: throw ConversationStorageException("Backup entry is missing")
        if (!hash.matches(Regex("[0-9a-f]{64}")) || ConversationFingerprint.sha256(file.readBytes()) != hash) {
            throw ConversationStorageException("Backup entry checksum mismatch")
        }
        return file
    }

    private inline fun <reified T> decode(file: File): T = try {
        repository.json.decodeFromString<T>(file.readText(Charsets.UTF_8))
    } catch (error: kotlinx.serialization.SerializationException) {
        throw ConversationStorageException("Invalid backup record", error)
    }

    private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long) {
        val buffer = ByteArray(32 * 1024)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            if (size > maximum) throw ConversationStorageException("Backup exceeds size limit")
            output.write(buffer, 0, count)
        }
    }

    private fun assetPath(reference: String) = "assets/${reference.removePrefix("asset:")}.blob"

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val MAX_RECORDS = 10_000
        private const val MAX_ENTRIES = 30_000
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
