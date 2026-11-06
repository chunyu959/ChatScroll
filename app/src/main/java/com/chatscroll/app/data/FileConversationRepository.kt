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
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.URI
import java.util.UUID
import java.nio.file.Files
import java.security.MessageDigest
import java.io.DataOutputStream
import java.security.DigestOutputStream

/** Android-independent repository; every operation shares a per-directory lock across instances. */
class FileConversationRepository(val directory: File) {
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = RepositoryFiles.lock(directory)
    private val folderStorage = ConversationFolders(directory, json)
    private val recordIssues = issuesByDirectory.getOrPut(directory.canonicalPath) { linkedMapOf() }

    internal data class Metadata(
        val summary: ConversationSummary,
        val revision: String? = null,
        val previousRevision: String? = null,
        val sourceHash: String? = null,
        val previousSourceHash: String? = null
    )

    @Serializable
    private data class SearchDocument(val contentHash: String, val messages: List<String>)

    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        RepositoryFiles.ensureDirectory(directory)
        action()
    }

    fun list(): List<ConversationSummary> = locked {
        val catalog = folderStorage.read()
        activeIds().mapNotNull { id ->
            readableRecord(id, "metadata") { metadata(id, catalog = catalog)?.summary }
        }.sortedWith(summaryOrder)
    }

    fun folders(): List<ConversationFolder> = locked { folderStorage.read().folders }

    fun createFolder(name: String): ConversationFolder = locked {
        val catalog = folderStorage.read()
        val trimmed = ConversationFolders.name(name)
        require(catalog.folders.none { it.name.equals(trimmed, ignoreCase = true) }) { "Folder name already exists" }
        val folder = ConversationFolder(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis())
        folderStorage.write(catalog.copy(folders = catalog.folders + folder))
        folder
    }

    fun renameFolder(id: String, name: String): Unit = locked {
        validateId(id)
        val catalog = folderStorage.read()
        require(catalog.folders.any { it.id == id }) { "Folder does not exist" }
        val trimmed = ConversationFolders.name(name)
        require(catalog.folders.none { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) { "Folder name already exists" }
        val updated = catalog.copy(folders = catalog.folders.map { if (it.id == id) it.copy(name = trimmed) else it })
        if (updated != catalog) folderStorage.write(updated)
    }

    fun deleteFolder(id: String): Unit = locked {
        validateId(id)
        val catalog = folderStorage.read()
        if (catalog.folders.none { it.id == id }) return@locked
        folderStorage.write(catalog.copy(folders = catalog.folders.filterNot { it.id == id },
            assignments = catalog.assignments.filterValues { it != id }))
    }

    fun moveToFolder(ids: List<String>, folderId: String?): Unit = locked {
        ids.forEach(::validateId)
        folderId?.let(::validateId)
        val catalog = folderStorage.read()
        require(folderId == null || catalog.folders.any { it.id == folderId }) { "Folder does not exist" }
        // Validate the complete batch before its single catalog commit. No bodies or sidecars are edited.
        ids.distinct().forEach { id ->
            if (metadata(id, repair = false, catalog = catalog) == null) throw ConversationStorageException("Conversation does not exist")
        }
        val assignments = catalog.assignments.toMutableMap()
        ids.forEach { id -> if (folderId == null) assignments.remove(id) else assignments[id] = folderId }
        if (assignments != catalog.assignments) folderStorage.write(catalog.copy(assignments = assignments))
    }

    internal fun folderCatalog(): FolderCatalog = folderStorage.read()
    internal fun folderCheckpoint(): Map<String, ByteArray> = folderStorage.checkpoint()
    internal fun restoreFolderCheckpoint(checkpoint: Map<String, ByteArray>) = folderStorage.rollback(checkpoint)
    internal fun writeFolderCatalog(catalog: FolderCatalog) = folderStorage.write(catalog)
    internal fun validateFolderCatalog(catalog: FolderCatalog) { folderStorage.bytes(catalog) }

    /** IDs only: never expose source text, filesystem paths, or exception details to the UI. */
    fun issues(): List<String> = locked {
        // Recheck known failures so restoring a good backup or deleting a broken record clears warnings.
        // This does not scan bodies of healthy records; list/search remain lightweight.
        list()
        recordIssues.mapValues { it.value.toSet() }.forEach { (id, kinds) ->
            kinds.forEach { kind ->
                when (kind) {
                    "search" -> readableRecord(id, kind) { metadata(id)?.let(::readSearch) }
                    "body" -> readableRecord(id, kind) { load(id) }
                    "source" -> readableRecord(id, kind) { readSource(id) }
                }
            }
        }
        recordIssues.keys.sorted()
    }

    private fun activeIds(): List<String> {
        val ids = knownIds().filterNot(::isDeleted)
        recordIssues.keys.retainAll(ids.toSet())
        return ids
    }

    private fun <T> trackedRecord(id: String, kind: String, action: () -> T): T {
        try {
            return action().also {
                recordIssues[id]?.let { kinds ->
                    kinds.remove(kind)
                    if (kinds.isEmpty()) recordIssues.remove(id)
                }
            }
        } catch (error: IOException) {
            if (error is InterruptedIOException) throw error
            recordIssues.getOrPut(id) { linkedSetOf() }.add(kind)
            throw error
        }
    }

    private fun <T> readableRecord(id: String, kind: String, action: () -> T): T? = try {
        trackedRecord(id, kind, action)
    } catch (error: IOException) {
        if (error is InterruptedIOException) throw error
        null
    }

    /** Each edit reads and writes under one shared lock, preserving current position/bookmarks. */
    private fun editMetadata(id: String, edit: (ConversationSummary) -> ConversationSummary): ConversationSummary? = locked {
        validateId(id)
        if (isDeleted(id)) return@locked null
        trackedRecord(id, "metadata") {
            metadata(id)?.let { old ->
                val updated = edit(old.summary)
                if (updated != old.summary) writeMetadata(old.copy(summary = updated))
                updated
            }
        }
    }

    fun setSeen(id: String, seen: Boolean = true): ConversationSummary? =
        editMetadata(id) { it.copy(seen = seen) }

    fun rename(id: String, title: String): ConversationSummary? =
        editMetadata(id) { it.copy(title = title) }

    fun setPinned(id: String, pinned: Boolean): ConversationSummary? = editMetadata(id) {
        it.copy(pinned = pinned, pinnedAt = when {
            !pinned -> null
            it.pinned && it.pinnedAt != null -> it.pinnedAt
            else -> System.currentTimeMillis()
        })
    }

    /** Null means absent/deleted. A present, unrecoverable record raises an IOException. */
    fun load(id: String): Conversation? = locked {
        validateId(id)
        if (isDeleted(id)) return@locked null
        trackedRecord(id, "body") {
            val meta = metadata(id) ?: return@trackedRecord null
            val body = readBody(id, meta.revision)
                ?: throw ConversationStorageException("Missing conversation body for $id")
            val merged = merge(body, meta.summary)
            merged.copy(messages = merged.messages.map { message ->
                message.copy(images = message.images.map { image ->
                    when {
                        ConversationFingerprint.assetPattern.matches(image) -> {
                            verifiedAsset(id, image)
                            assetFile(id, image).toURI().toString()
                        }
                        image.startsWith("file:", true) -> checkedLocalImage(id, image).toURI().toString()
                        else -> { validateExternalImage(image); image }
                    }
                })
            })
        }
    }

    fun findDuplicate(contentHash: String): ConversationSummary? = locked {
        if (contentHash.isBlank()) null else list().firstOrNull { it.contentHash == contentHash }
    }

    /** A revision is complete before its metadata pointer commits. Reparse keeps the previous body/source. */
    fun save(conversation: Conversation, source: String? = null): Unit = locked {
        val id = conversation.id
        validateId(id)
        if (isDeleted(id)) throw ConversationStorageException("Cannot replace a deleted conversation")
        val old = metadata(id)
        val previousSource = old?.takeIf { it.summary.hasSource }?.let { previous ->
            RepositoryFiles.read(sourceFile(id, previous.revision), MAX_SOURCE_BYTES, repair = false) { bytes ->
                if (previous.sourceHash != null && ConversationFingerprint.sha256(bytes) != previous.sourceHash) {
                    throw ConversationStorageException("Source checksum mismatch")
                }
                bytes.toString(Charsets.UTF_8)
            } ?: throw ConversationStorageException("Conversation source is missing")
        }
        val retainedSource = source ?: previousSource
        val retainedSourceBytes = retainedSource?.toByteArray(Charsets.UTF_8)
        if (retainedSource == null && conversation.hasSource) {
            throw ConversationStorageException("Conversation source is missing")
        }
        val revision = UUID.randomUUID().toString()
        val revisionDirectory = File(directory, "$id.revisions")
        RepositoryFiles.ensureDirectory(revisionDirectory)
        val messages = conversation.messages.map { message ->
            message.copy(images = message.images.map { storeImage(id, it) })
        }
        val stored = conversation.copy(
            contentHash = fingerprint(id, messages), messages = messages,
            hasSource = retainedSource != null,
            folderId = old?.summary?.folderId,
            sourceSizeBytes = retainedSourceBytes?.size?.toLong() ?: old?.summary?.sourceSizeBytes,
            bookmarks = conversation.bookmarks.filter { it in messages.indices }.distinct().sorted(),
            scrollIndex = conversation.scrollIndex?.coerceIn(0, (messages.size - 1).coerceAtLeast(0)),
            scrollOffset = conversation.scrollOffset?.coerceAtLeast(0),
            scrollBlock = conversation.scrollBlock.coerceAtLeast(0)
        )
        val bodyBytes = json.encodeToString(stored).toByteArray(Charsets.UTF_8)
        requireSize(bodyBytes, MAX_BODY_BYTES, "Conversation body")
        if (retainedSourceBytes != null) {
            requireSize(retainedSourceBytes, MAX_SOURCE_BYTES, "Source")
            RepositoryFiles.write(sourceFile(id, revision), retainedSourceBytes)
        }
        RepositoryFiles.write(bodyFile(id, revision), bodyBytes)
        // Search is derived and repairable; write before publishing metadata to avoid an indexless successful save.
        writeSearch(stored)
        writeMetadata(Metadata(stored.toSummary(), revision, old?.revision ?: old?.let { LEGACY_REVISION },
            retainedSourceBytes?.let(ConversationFingerprint::sha256),
            previousSource?.toByteArray(Charsets.UTF_8)?.let(ConversationFingerprint::sha256)))
        recordIssues.remove(id)
    }

    /** Authoritative metadata changes do not touch body/source/image files. */
    fun updateSummary(summary: ConversationSummary): Unit = locked {
        validateId(summary.id)
        if (isDeleted(summary.id)) throw ConversationStorageException("Conversation was deleted")
        val old = metadata(summary.id) ?: throw ConversationStorageException("Conversation does not exist")
        val structural = old.summary
        val updated = summary.copy(
            contentHash = structural.contentHash, messageCount = structural.messageCount,
            sourceFormat = structural.sourceFormat, parserVersion = structural.parserVersion,
            hasSource = structural.hasSource, folderId = structural.folderId, sourceSizeBytes = structural.sourceSizeBytes,
            bookmarks = summary.bookmarks.filter { it in 0 until structural.messageCount }.distinct().sorted(),
            scrollIndex = summary.scrollIndex?.coerceIn(0, (structural.messageCount - 1).coerceAtLeast(0)),
            scrollOffset = summary.scrollOffset?.coerceAtLeast(0), scrollBlock = summary.scrollBlock.coerceAtLeast(0)
        )
        writeMetadata(old.copy(summary = updated))
    }

    fun updatePosition(id: String, index: Int, offset: Int, blockIndex: Int = 0): ConversationSummary? = locked {
        validateId(id)
        if (isDeleted(id)) return@locked null
        val old = metadata(id)?.summary ?: return@locked null
        val updated = old.copy(scrollIndex = index.coerceIn(0, (old.messageCount - 1).coerceAtLeast(0)),
            scrollOffset = offset.coerceAtLeast(0), scrollBlock = blockIndex.coerceAtLeast(0), seen = true)
        updateSummary(updated)
        updated
    }

    fun toggleBookmark(id: String, messageIndex: Int): ConversationSummary? = locked {
        validateId(id)
        if (isDeleted(id)) return@locked null
        val old = metadata(id)?.summary ?: return@locked null
        require(messageIndex in 0 until old.messageCount) { "Bookmark message is out of range" }
        val bookmarks = if (messageIndex in old.bookmarks) old.bookmarks - messageIndex
            else (old.bookmarks + messageIndex).sorted()
        old.copy(bookmarks = bookmarks).also(::updateSummary)
    }

    fun readSource(id: String): String? = locked {
        validateId(id)
        if (isDeleted(id)) return@locked null
        trackedRecord(id, "source") {
            val meta = metadata(id) ?: return@trackedRecord null
            if (!meta.summary.hasSource) return@trackedRecord null
            val file = sourceFile(id, meta.revision)
            RepositoryFiles.read(file, MAX_SOURCE_BYTES) { bytes ->
                if (meta.sourceHash != null && ConversationFingerprint.sha256(bytes) != meta.sourceHash) {
                    throw ConversationStorageException("Source checksum mismatch")
                }
                bytes.toString(Charsets.UTF_8)
            } ?: throw ConversationStorageException("Conversation source is missing")
        }
    }

    /** Preview never repairs files, creates directories, or migrates metadata. */
    fun historyPreview(id: String): HistoryPreview? = synchronized(lock) {
        validateId(id)
        if (!directory.exists() || isDeleted(id)) return@synchronized null
        val meta = metadata(id, repair = false) ?: return@synchronized null
        val previous = meta.previousRevision ?: return@synchronized null
        val target = validateRevision(id, previous, meta.previousSourceHash ?: historicalSourceHash(id, previous))
        val current = validateRevision(id, meta.revision, meta.sourceHash)
        validateActiveMetadata(meta, current)
        HistoryPreview(meta.summary.title, target.body.messages.size, target.body.parserVersion,
            target.body.sourceFormat, snapshot(id).token)
    }

    /** The only commit is the metadata pointer; the just-current revision becomes the next restore. */
    fun restorePrevious(id: String, expectedToken: String): Conversation? = synchronized(lock) {
        validateId(id)
        if (snapshot(id).token != expectedToken) throw ConversationStorageConflictException()
        val meta = metadata(id, repair = false) ?: return@synchronized null
        val previous = meta.previousRevision ?: return@synchronized null
        val target = validateRevision(id, previous, meta.previousSourceHash ?: historicalSourceHash(id, previous))
        val current = validateRevision(id, meta.revision, meta.sourceHash)
        validateActiveMetadata(meta, current)
        // Only a unique, byte-equivalent message (all semantic fields included) keeps its location.
        val oldKeys = current.body.messages.map { fingerprint(id, listOf(it), repair = false) }
        val newKeys = target.body.messages.map { fingerprint(id, listOf(it), repair = false) }
        val oldCounts = oldKeys.groupingBy { it }.eachCount()
        val newPositions = newKeys.withIndex().groupBy({ it.value }, { it.index })
        fun mapped(index: Int?): Int? {
            val key = index?.let { oldKeys.getOrNull(it) } ?: return null
            return newPositions[key]?.singleOrNull()?.takeIf { oldCounts[key] == 1 }
        }
        val position = mapped(meta.summary.scrollIndex)
        val restored = target.body.copy(
            title = meta.summary.title, importedAt = meta.summary.importedAt,
            pinned = meta.summary.pinned, pinnedAt = meta.summary.pinnedAt, seen = meta.summary.seen,
            contentHash = target.contentHash, hasSource = target.sourceHash != null,
            folderId = meta.summary.folderId, sourceSizeBytes = target.sourceSizeBytes ?: target.body.sourceSizeBytes,
            bookmarks = meta.summary.bookmarks.mapNotNull(::mapped).distinct().sorted(),
            scrollIndex = position, scrollOffset = if (position == null) null else meta.summary.scrollOffset,
            scrollBlock = if (position == null) 0 else meta.summary.scrollBlock
        )
        // Recheck after validation as a guard against filesystem changes outside the shared lock.
        if (snapshot(id).token != expectedToken) throw ConversationStorageConflictException()
        writeMetadata(Metadata(restored.toSummary(), previous, meta.revision ?: LEGACY_REVISION,
            target.sourceHash, current.sourceHash), previous = meta)
        recordIssues.remove(id)
        // Search is derived: the next query rebuilds it after noticing the changed content hash.
        restored.copy(messages = restored.messages.map { message ->
            message.copy(images = message.images.map { image ->
                when {
                    ConversationFingerprint.assetPattern.matches(image) -> {
                        verifiedAsset(id, image)
                        assetFile(id, image).toURI().toString()
                    }
                    image.startsWith("file:", true) -> checkedLocalImage(id, image).toURI().toString()
                    else -> image
                }
            })
        })
    }

    private data class ValidatedRevision(val body: Conversation, val contentHash: String, val sourceHash: String?, val sourceSizeBytes: Long?)

    private fun validateActiveMetadata(meta: Metadata, revision: ValidatedRevision) {
        if (meta.summary.messageCount != revision.body.messages.size ||
            (meta.summary.contentHash.startsWith(ConversationFingerprint.PREFIX) && meta.summary.contentHash != revision.contentHash) ||
            meta.summary.hasSource != (revision.sourceHash != null)) {
            throw ConversationStorageException("Metadata does not match its revision")
        }
    }

    private fun validateRevision(id: String, revision: String?, expectedSourceHash: String?): ValidatedRevision {
        if (revision != null && revision != LEGACY_REVISION &&
            Files.isSymbolicLink(File(directory, "$id.revisions").toPath())) {
            throw ConversationStorageException("Invalid revision directory")
        }
        val body = readBody(id, revision, repair = false) ?: throw ConversationStorageException("History body is missing")
        val hash = fingerprint(id, body.messages, repair = false)
        var sourceSizeBytes: Long? = null
        val sourceHash = if (body.hasSource || expectedSourceHash != null) {
            if (expectedSourceHash == null) throw ConversationStorageException("History source cannot be verified")
            RepositoryFiles.read(sourceFile(id, revision), MAX_SOURCE_BYTES, repair = false) { bytes ->
                ConversationFingerprint.sha256(bytes).also {
                    if (it != expectedSourceHash) throw ConversationStorageException("History source checksum mismatch")
                    sourceSizeBytes = bytes.size.toLong()
                }
            } ?: throw ConversationStorageException("History source is missing")
        } else null
        return ValidatedRevision(body, hash, sourceHash, sourceSizeBytes)
    }

    private fun historicalSourceHash(id: String, revision: String): String? {
        // Old saves did not store _previousSourceHash; the atomic metadata backup may still prove it.
        val file = File(directory, "$id.meta.json.bak")
        if (!file.isFile) return null
        val backup = try { exactMetadata(id, file) } catch (_: IOException) { return null }
        return when (revision) {
            backup.revision ?: LEGACY_REVISION -> backup.sourceHash
            backup.previousRevision -> backup.previousSourceHash
            else -> null
        }
    }

    private fun exactMetadata(id: String, file: File): Metadata = try {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() > MAX_METADATA_BYTES) {
            throw ConversationStorageException("Invalid metadata checkpoint")
        }
        decodeMetadata(id, file.readBytes())
    } catch (error: kotlinx.serialization.SerializationException) {
        throw ConversationStorageException("Invalid metadata checkpoint", error)
    } catch (error: IllegalArgumentException) {
        throw ConversationStorageException("Invalid metadata checkpoint", error)
    }

    private data class DiskEntry(val file: File, val path: String, val bytes: Long, val regular: Boolean)
    private data class DiskSnapshot(val entries: List<DiskEntry>, val token: String)

    /** Full content digests invalidate confirmation after same-size edits as well as pointer changes. */
    private fun snapshot(id: String? = null): DiskSnapshot {
        val entries = mutableListOf<DiskEntry>()
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DataOutputStream(DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, digest))
        sink.writeUTF(directory.canonicalPath)
        fun visit(file: File, path: String) {
            sink.writeUTF(path)
            when {
                Files.isSymbolicLink(file.toPath()) -> {
                    sink.writeByte(0)
                    sink.writeUTF(Files.readSymbolicLink(file.toPath()).toString())
                    entries.add(DiskEntry(file, path, 0, false))
                }
                file.isDirectory -> {
                    sink.writeByte(1)
                    val children = file.listFiles() ?: throw ConversationStorageException("Cannot inspect storage")
                    children.sortedBy { it.name }.forEach { visit(it, "$path/${it.name}") }
                }
                file.isFile -> {
                    val size = file.length()
                    sink.writeByte(2)
                    sink.writeLong(size)
                    file.inputStream().use { it.copyTo(sink) }
                    entries.add(DiskEntry(file, path, size, true))
                }
                else -> throw ConversationStorageException("Storage changed during inspection")
            }
        }
        if (directory.exists()) {
            if (!directory.isDirectory) throw ConversationStorageException("Invalid storage directory")
            val children = directory.listFiles() ?: throw ConversationStorageException("Cannot inspect storage")
            children.filter { id == null || it.name.startsWith("$id.") || ConversationFolders.recognizes(it.name) }
                .sortedBy { it.name }.forEach { visit(it, it.name) }
        }
        sink.flush()
        return DiskSnapshot(entries, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Counting is read-only, including on an empty or legacy repository. */
    fun storageUsage(): StorageUsage = synchronized(lock) {
        val disk = snapshot()
        val metadata = if (!directory.exists()) emptyMap() else knownIds().mapNotNull { id ->
            try { metadata(id, repair = false)?.let { id to it } } catch (_: IOException) { null }
        }.toMap()
        val counts = LongArray(6)
        disk.entries.forEach { entry ->
            val id = entry.path.substringBefore('.')
            val meta = metadata[id]
            val currentBody = meta?.let { relative(bodyFile(id, it.revision)) }
            val currentSource = meta?.let { relative(sourceFile(id, it.revision)) }
            val base = entry.path.removeSuffix(".bak")
            val category = when {
                !entry.regular -> 5
                ConversationFolders.recognizes(entry.path) -> 0
                meta == null -> 5
                base == "$id.meta.json" || base == currentBody -> 0
                base == currentSource -> 1
                base.startsWith("$id.assets/") && ASSET_FILE_PATTERN.matches(base.substringAfter('/')) -> 2
                base == "$id.search.json" -> 4
                historyName(id, base) != null || base == "$id.json" || base == "$id.source.md" -> 3
                else -> 5
            }
            counts[category] += entry.bytes
        }
        StorageUsage(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5])
    }

    private data class CleanupPlan(val preview: CleanupPreview, val targets: List<DiskEntry>)

    fun cleanupPreview(): CleanupPreview = synchronized(lock) { cleanupPlan().preview }

    /** Recomputes the complete plan under the save/restore/bookmark lock before any deletion. */
    fun cleanStorage(previewToken: String): CleanupResult = synchronized(lock) {
        val plan = cleanupPlan()
        if (plan.preview.token != previewToken || snapshot().token != previewToken) throw ConversationStorageConflictException()
        plan.targets.forEach { entry ->
            if (!entry.file.delete()) throw ConversationStorageException("Cannot remove older history")
        }
        CleanupResult(plan.preview.revisionCount, plan.targets.size, plan.preview.bytes, plan.preview.warnings)
    }

    private fun relative(file: File): String = file.relativeTo(directory).invariantSeparatorsPath

    private fun historyName(id: String, path: String): String? {
        if (!path.startsWith("$id.revisions/")) return null
        val name = path.removePrefix("$id.revisions/").removeSuffix(".bak")
        val revision = when {
            name.endsWith(".source.md") -> name.removeSuffix(".source.md")
            name.endsWith(".json") -> name.removeSuffix(".json")
            else -> return null
        }
        return revision.takeIf { REVISION_PATTERN.matches(it) }
    }

    private fun cleanupPlan(): CleanupPlan {
        val disk = snapshot()
        val targets = mutableListOf<DiskEntry>()
        val recognized = disk.entries.filter { ConversationFolders.recognizes(it.path) }.map { it.path }.toMutableSet()
        var revisionCount = 0
        var protectedCount = 0
        var skippedCount = 0
        val ids = if (directory.exists()) knownIds() else emptyList()
        for (id in ids) {
            if (isDeleted(id)) { skippedCount++; continue }
            val recordEntries = disk.entries.filter { it.path.startsWith("$id.") }
            try {
                // Every checkpoint is independently checked; fallback must not hide an unknown pointer.
                val checkpoints = METADATA_SUFFIXES.map { File(directory, "$id.meta.json$it") }
                    .filter { it.exists() || Files.isSymbolicLink(it.toPath()) }.map { exactMetadata(id, it) }
                val meta = metadata(id, repair = false) ?: throw ConversationStorageException("Missing active pointer")
                val protected = linkedSetOf(meta.revision ?: LEGACY_REVISION)
                meta.previousRevision?.let(protected::add)
                checkpoints.forEach { checkpoint ->
                    protected.add(checkpoint.revision ?: LEGACY_REVISION)
                    checkpoint.previousRevision?.let(protected::add)
                }
                // Refuse cleanup for the entire record when any live/checkpoint body or source is invalid.
                (checkpoints + meta).forEach { checkpoint ->
                    validateActiveMetadata(checkpoint, validateRevision(id, checkpoint.revision, checkpoint.sourceHash))
                    checkpoint.previousRevision?.let { revision ->
                        val hash = checkpoint.previousSourceHash ?: (checkpoints + meta).firstOrNull {
                            (it.revision ?: LEGACY_REVISION) == revision
                        }?.sourceHash
                        validateRevision(id, revision, hash)
                    }
                }
                protectedCount += protected.size
                recordEntries.filter { entry ->
                    val base = entry.path.removeSuffix(".bak")
                    base in setOf("$id.meta.json", "$id.json", "$id.source.md", "$id.search.json") ||
                        (base.startsWith("$id.assets/") && ASSET_FILE_PATTERN.matches(base.substringAfter('/'))) ||
                        historyName(id, entry.path) in protected
                }.forEach { recognized.add(it.path) }
                val revisions = recordEntries.mapNotNull { historyName(id, it.path) }.distinct().filterNot { it in protected }
                for (revision in revisions) {
                    try {
                        val body = readBody(id, revision, repair = false) ?: continue
                        val expected = setOf(relative(bodyFile(id, revision)), relative(bodyFile(id, revision)) + ".bak") +
                            if (body.hasSource) setOf(relative(sourceFile(id, revision)), relative(sourceFile(id, revision)) + ".bak") else emptySet()
                        val files = recordEntries.filter { it.path in expected }
                        if (files.size != expected.size || files.any { !it.regular }) continue
                        // Immutable first-save backups must match. A mismatched pair remains untouched.
                        for (primary in expected.filterNot { it.endsWith(".bak") }) {
                            val file = File(directory, primary)
                            val maximum = if (primary.endsWith(".json")) MAX_BODY_BYTES else MAX_SOURCE_BYTES
                            if (file.length() > maximum || File(file.path + ".bak").length() > maximum ||
                                !file.readBytes().contentEquals(File(file.path + ".bak").readBytes())) {
                                throw ConversationStorageException("Unverified history pair")
                            }
                        }
                        files.forEach { recognized.add(it.path) }
                        targets.addAll(files)
                        revisionCount++
                    } catch (_: IOException) { /* Unverified/unknown revisions are never cleanup targets. */ }
                }
            } catch (_: IOException) {
                skippedCount++
            }
        }
        val warnings = if (skippedCount == 0) emptyList() else listOf("Some records could not be verified and were left untouched.")
        return CleanupPlan(CleanupPreview(disk.token, revisionCount, targets.size, targets.sumOf { it.bytes },
            protectedCount, skippedCount, disk.entries.count { it.path !in recognized }, warnings), targets)
    }

    /** The durable tombstone is written first, so interrupted cleanup cannot resurrect the body. */
    fun delete(id: String): Unit = locked {
        validateId(id)
        RepositoryFiles.write(File(directory, "$id.deleted"), "deleted".toByteArray())
        val files = directory.listFiles() ?: throw ConversationStorageException("Cannot list conversation storage")
        files.filter { it.name.startsWith("$id.") && !it.name.startsWith("$id.deleted") }
            .forEach(RepositoryFiles::deleteTree)
        recordIssues.remove(id)
    }

    fun search(query: String, cancellationCheck: () -> Unit = {}): List<SearchHit> = locked {
        val needle = query.trim()
        if (needle.isEmpty()) return@locked emptyList()
        val results = ArrayList<SearchHit>()
        // Check between records and messages; caller can supply a captured coroutine context's ensureActive.
        fun check() {
            cancellationCheck()
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Search interrupted")
        }
        check()
        val catalog = folderStorage.read()
        activeIds().forEach { id ->
            check()
            val meta = readableRecord(id, "metadata") { metadata(id, catalog = catalog) } ?: return@forEach
            val index = readableRecord(id, "search") { readSearch(meta) } ?: return@forEach
            var snippet: String? = null
            var messageIndex: Int? = null
            for ((position, content) in index.messages.withIndex()) {
                check()
                val excerpt = searchExcerpt(content, needle)
                if (excerpt != null) { snippet = excerpt; messageIndex = position; break }
            }
            if (snippet != null || meta.summary.title.contains(needle, ignoreCase = true)) {
                results.add(SearchHit(meta.summary, snippet, messageIndex))
            }
        }
        results.sortedWith(compareByDescending<SearchHit> { it.summary.pinned }
            .thenByDescending { it.summary.pinnedAt ?: 0L }.thenByDescending { it.summary.importedAt })
    }

    fun exportBackup(output: OutputStream): Int = locked { ConversationBackup(this).export(output) }
    fun restoreBackup(input: InputStream): Int = locked { ConversationBackup(this).restore(input) }

    internal fun hasId(id: String): Boolean {
        val files = directory.listFiles() ?: throw ConversationStorageException("Cannot enumerate existing conversations")
        return files.any { it.name.startsWith("$id.", ignoreCase = true) }
    }

    internal fun removeRestored(id: String) {
        // Only IDs reserved as absent by restore reach this path. Do not create permanent tombstones.
        val files = directory.listFiles() ?: throw ConversationStorageException("Cannot enumerate restore rollback files")
        files.filter { it.name.startsWith("$id.") }.forEach(RepositoryFiles::deleteTree)
    }

    internal fun portableConversation(id: String): Conversation {
        val meta = metadata(id) ?: throw ConversationStorageException("Missing conversation")
        val body = readBody(id, meta.revision) ?: throw ConversationStorageException("Missing conversation body")
        return merge(body, meta.summary).let { conversation ->
            conversation.copy(messages = conversation.messages.map { message ->
                message.copy(images = message.images.map { image ->
                    when {
                        image.startsWith("data:", true) || image.startsWith("file:", true) -> storeImage(id, image)
                        else -> { validateExternalImage(image); image }
                    }
                })
            })
        }
    }

    internal fun imageData(id: String, reference: String): ByteArray = verifiedAsset(id, reference)

    private fun knownIds(): List<String> {
        val files = directory.listFiles() ?: throw ConversationStorageException("Cannot list conversation storage")
        return files.mapNotNull { file ->
            when {
                file.name.endsWith(".meta.json.bak") -> file.name.removeSuffix(".meta.json.bak")
                file.name.endsWith(".meta.json") -> file.name.removeSuffix(".meta.json")
                file.name.endsWith(".json.bak") && '.' !in file.name.removeSuffix(".json.bak") -> file.name.removeSuffix(".json.bak")
                file.name.endsWith(".json") && '.' !in file.name.removeSuffix(".json") -> file.name.removeSuffix(".json")
                else -> null
            }
        }.filter { ID_PATTERN.matches(it) }.distinct().sorted()
    }

    private fun isDeleted(id: String): Boolean = File(directory, "$id.deleted").exists() || File(directory, "$id.deleted.bak").exists()

    private fun decodeMetadata(id: String, bytes: ByteArray): Metadata {
        val element = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        element["_checksum"]?.jsonPrimitive?.content?.let { checksum ->
            val payload = json.encodeToString(JsonObject(element.filterKeys { it != "_checksum" })).toByteArray(Charsets.UTF_8)
            if (ConversationFingerprint.sha256(payload) != checksum) throw ConversationStorageException("Metadata checksum mismatch")
        }
        val summary = json.decodeFromJsonElement<ConversationSummary>(element)
        if (summary.id != id || summary.messageCount < 0 || (summary.sourceSizeBytes ?: 0) < 0) {
            throw ConversationStorageException("Invalid metadata identity or size")
        }
        val revision = element["_revision"]?.jsonPrimitive?.content
        val previous = element["_previousRevision"]?.jsonPrimitive?.content
        if (revision != null && !ID_PATTERN.matches(revision)) throw ConversationStorageException("Invalid body revision")
        if (previous != null && !ID_PATTERN.matches(previous)) throw ConversationStorageException("Invalid previous revision")
        return Metadata(summary, revision, previous, element["_sourceHash"]?.jsonPrimitive?.content,
            element["_previousSourceHash"]?.jsonPrimitive?.content)
    }

    private fun metadata(id: String, repair: Boolean = true, catalog: FolderCatalog? = null): Metadata? {
        validateId(id)
        if (isDeleted(id)) return null
        val currentCatalog = catalog ?: folderStorage.read(repair)
        val file = File(directory, "$id.meta.json")
        var meta = RepositoryFiles.read(file, MAX_METADATA_BYTES, repair) { decodeMetadata(id, it) }
        if (meta == null) {
            val legacy = readBody(id, null, repair) ?: return null
            meta = Metadata(legacy.toSummary())
        }
        val original = meta
        // A missing catalog assignment always means unclassified, including for stale body/checkpoint fields.
        meta = meta.copy(summary = meta.summary.copy(folderId = currentCatalog.assignments[id]))
        if (meta.summary.sourceSizeBytes == null) {
            val current = meta
            val size = try {
                RepositoryFiles.read(sourceFile(id, current.revision), MAX_SOURCE_BYTES, repair) { bytes ->
                    if (current.sourceHash != null && ConversationFingerprint.sha256(bytes) != current.sourceHash) {
                        throw ConversationStorageException("Source checksum mismatch")
                    }
                    bytes.size.toLong()
                }
            } catch (error: IOException) {
                if (error is InterruptedIOException) throw error
                null // Legacy records without readable original source have an unknown size.
            }
            if (size != null) meta = meta.copy(summary = meta.summary.copy(sourceSizeBytes = size))
        }
        if (repair) {
            if (!meta.summary.contentHash.startsWith(ConversationFingerprint.PREFIX)) {
                val body = readBody(id, meta.revision) ?: throw ConversationStorageException("Missing body during hash migration")
                meta = meta.copy(summary = meta.summary.copy(contentHash = fingerprint(id, body.messages), messageCount = body.messages.size))
            }
            // Membership changes alone never rewrite sidecars: the catalog is the sole assignment commit.
            if (!file.exists() || meta.summary.contentHash != original.summary.contentHash ||
                meta.summary.sourceSizeBytes != original.summary.sourceSizeBytes) writeMetadata(meta)
        }
        return meta
    }

    private fun writeMetadata(meta: Metadata, previous: Metadata? = null) {
        // Older metadata keeps the previous source checksum only in .bak. Capture it before
        // rotating that backup, including in the rollback metadata if this commit is interrupted.
        fun retainPreviousHash(value: Metadata): Metadata {
            val revision = value.previousRevision ?: return value
            if (value.previousSourceHash != null) return value
            val hash = historicalSourceHash(value.summary.id, revision) ?: return value
            return value.copy(previousSourceHash = hash)
        }
        val updated = retainPreviousHash(meta)
        val rollback = if (previous != null) retainPreviousHash(previous) else {
            metadata(meta.summary.id, repair = false)?.let { old ->
                retainPreviousHash(old).takeIf { it != old }
            }
        }
        RepositoryFiles.write(File(directory, "${meta.summary.id}.meta.json"), metadataBytes(updated), rollback?.let(::metadataBytes))
    }

    private fun metadataBytes(meta: Metadata): ByteArray {
        val fields = json.encodeToJsonElement(meta.summary).jsonObject.toMutableMap()
        meta.revision?.let { fields["_revision"] = JsonPrimitive(it) }
        meta.previousRevision?.let { fields["_previousRevision"] = JsonPrimitive(it) }
        meta.sourceHash?.let { fields["_sourceHash"] = JsonPrimitive(it) }
        meta.previousSourceHash?.let { fields["_previousSourceHash"] = JsonPrimitive(it) }
        val payload = json.encodeToString(JsonObject(fields)).toByteArray(Charsets.UTF_8)
        fields["_checksum"] = JsonPrimitive(ConversationFingerprint.sha256(payload))
        val bytes = json.encodeToString(JsonObject(fields)).toByteArray(Charsets.UTF_8)
        requireSize(bytes, MAX_METADATA_BYTES, "Metadata")
        return bytes
    }

    private fun readBody(id: String, revision: String?, repair: Boolean = true): Conversation? =
        RepositoryFiles.read(bodyFile(id, revision), MAX_BODY_BYTES, repair) { bytes ->
            json.decodeFromString<Conversation>(bytes.toString(Charsets.UTF_8)).also {
                if (it.id != id) throw ConversationStorageException("Invalid conversation identity")
                if (revision != null && revision != LEGACY_REVISION &&
                    fingerprint(id, it.messages, repair) != it.contentHash) {
                    throw ConversationStorageException("Conversation body checksum mismatch")
                }
            }
        }

    private fun bodyFile(id: String, revision: String?): File = if (revision == null || revision == LEGACY_REVISION)
        File(directory, "$id.json") else File(directory, "$id.revisions/$revision.json")

    private fun sourceFile(id: String, revision: String?): File = if (revision == null || revision == LEGACY_REVISION)
        File(directory, "$id.source.md") else File(directory, "$id.revisions/$revision.source.md")

    private fun readSearch(meta: Metadata): SearchDocument {
        val file = File(directory, "${meta.summary.id}.search.json")
        val index = try {
            RepositoryFiles.read(file, MAX_BODY_BYTES) { bytes -> json.decodeFromString<SearchDocument>(bytes.toString(Charsets.UTF_8)) }
        } catch (_: ConversationStorageException) { null } // Derived data only: body/source failures still propagate.
        if (index != null && index.contentHash == meta.summary.contentHash && index.messages.size == meta.summary.messageCount) return index
        val body = readBody(meta.summary.id, meta.revision) ?: throw ConversationStorageException("Missing searchable conversation")
        return writeSearch(merge(body, meta.summary))
    }

    private fun writeSearch(conversation: Conversation): SearchDocument {
        val index = SearchDocument(conversation.contentHash, conversation.messages.map { message ->
            // Blocks can preserve prose omitted from content in older/parser-specific exports.
            (listOf(message.content) + message.blocks.filter { it.kind == "text" }.map { it.text }).distinct().joinToString("\n")
        })
        RepositoryFiles.write(File(directory, "${conversation.id}.search.json"), json.encodeToString(index).toByteArray(Charsets.UTF_8))
        return index
    }

    private fun fingerprint(id: String, messages: List<ChatMessage>, repair: Boolean = true): String = ConversationFingerprint.hash(messages) { image ->
        when {
            ConversationFingerprint.assetPattern.matches(image) -> verifiedAsset(id, image, repair)
            image.startsWith("file:", true) -> {
                val file = checkedLocalImage(id, image, repair)
                verifiedAsset(id, "asset:" + file.name.removeSuffix(".blob"), repair)
            }
            else -> { validateExternalImage(image); null }
        }
    }

    private fun storeImage(id: String, image: String): String {
        if (ConversationFingerprint.assetPattern.matches(image)) { verifiedAsset(id, image); return image }
        val bytes = when {
            image.startsWith("data:", true) -> ConversationFingerprint.decodeImage(image)
            image.startsWith("file:", true) -> ConversationFingerprint.readImage(checkedLocalImage(id, image))
            else -> { validateExternalImage(image); return image }
        }
        val reference = "asset:" + ConversationFingerprint.sha256(bytes)
        val file = assetFile(id, reference)
        if (file.exists() || File(file.path + ".bak").exists()) verifiedAsset(id, reference)
        else RepositoryFiles.write(file, bytes)
        return reference
    }

    private fun checkedLocalImage(id: String, reference: String, repair: Boolean = true): File {
        val uri = try { URI(reference) } catch (error: Exception) { throw ConversationStorageException("Invalid local image reference", error) }
        if (uri.scheme != "file" || !uri.authority.isNullOrEmpty() || uri.query != null || uri.fragment != null) {
            throw ConversationStorageException("Invalid local image reference")
        }
        val file = File(uri).canonicalFile
        val assets = File(directory, "$id.assets").canonicalFile
        if (file.parentFile != assets || !Regex("[0-9a-f]{64}\\.blob").matches(file.name)) {
            throw ConversationStorageException("Local image is outside this conversation's private assets")
        }
        // Verify bytes rather than trusting a filename supplied by an import.
        verifiedAsset(id, "asset:" + file.name.removeSuffix(".blob"), repair)
        return file
    }

    private fun assetFile(id: String, reference: String): File {
        val match = ConversationFingerprint.assetPattern.matchEntire(reference)
            ?: throw ConversationStorageException("Invalid image asset reference")
        val directory = File(directory, "$id.assets")
        val file = File(directory, match.groupValues[1] + ".blob")
        if (file.canonicalFile.parentFile != directory.canonicalFile || directory.canonicalFile.parentFile != this.directory.canonicalFile) {
            throw ConversationStorageException("Image asset escapes private storage")
        }
        return file
    }

    private fun verifiedAsset(id: String, reference: String, repair: Boolean = true): ByteArray {
        val expectedHash = ConversationFingerprint.assetPattern.matchEntire(reference)?.groupValues?.get(1)
            ?: throw ConversationStorageException("Invalid image asset reference")
        return RepositoryFiles.read(assetFile(id, reference), ConversationFingerprint.MAX_IMAGE_BYTES, repair) { bytes ->
            if (ConversationFingerprint.sha256(bytes) != expectedHash) throw ConversationStorageException("Image checksum mismatch")
            bytes
        } ?: throw ConversationStorageException("Conversation image is missing")
    }

    internal fun installAsset(id: String, reference: String, bytes: ByteArray) {
        validateId(id)
        val hash = ConversationFingerprint.assetPattern.matchEntire(reference)?.groupValues?.get(1)
        if (hash == null || ConversationFingerprint.sha256(bytes) != hash) throw ConversationStorageException("Image checksum mismatch")
        RepositoryFiles.write(assetFile(id, reference), bytes)
    }

    internal fun validateExternalImage(image: String) {
        if (ConversationFingerprint.assetPattern.matches(image) || image.startsWith("data:", true)) return
        val uri = try { URI(image) } catch (error: Exception) { throw ConversationStorageException("Invalid image reference", error) }
        if (uri.scheme?.lowercase() !in listOf("https", "http") || uri.host.isNullOrEmpty() || uri.userInfo != null) {
            throw ConversationStorageException("Unsupported image reference")
        }
    }

    private fun merge(body: Conversation, summary: ConversationSummary) = body.copy(
        title = summary.title, importedAt = summary.importedAt, pinned = summary.pinned,
        pinnedAt = summary.pinnedAt, seen = summary.seen, contentHash = summary.contentHash,
        scrollIndex = summary.scrollIndex, scrollOffset = summary.scrollOffset,
        sourceFormat = summary.sourceFormat, parserVersion = summary.parserVersion,
        hasSource = summary.hasSource, bookmarks = summary.bookmarks, scrollBlock = summary.scrollBlock,
        folderId = summary.folderId, sourceSizeBytes = summary.sourceSizeBytes
    )

    companion object {
        // Context wrappers create fresh instances; warnings must survive until the next UI refresh.
        private val issuesByDirectory = ConcurrentHashMap<String, MutableMap<String, MutableSet<String>>>()
        internal val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        internal const val MAX_BODY_BYTES = 64L * 1024 * 1024
        internal const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        internal const val MAX_METADATA_BYTES = 2L * 1024 * 1024
        private const val LEGACY_REVISION = "legacy"
        private val REVISION_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ASSET_FILE_PATTERN = Regex("[0-9a-f]{64}\\.blob")
        private val METADATA_SUFFIXES = listOf("", ".bak", ".tmp", ".bak.tmp", ".recover.tmp")
        private val summaryOrder = compareByDescending<ConversationSummary> { it.pinned }
            .thenByDescending { it.pinnedAt ?: 0L }.thenByDescending { it.importedAt }
        internal fun validateId(id: String) {
            require(ID_PATTERN.matches(id)) { "Invalid conversation ID" }
        }
        internal fun requireSize(bytes: ByteArray, maximum: Long, label: String) {
            if (bytes.size.toLong() > maximum) throw ConversationStorageException("$label exceeds size limit")
        }
    }
}
