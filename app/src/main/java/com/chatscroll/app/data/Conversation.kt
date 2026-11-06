package com.chatscroll.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@Serializable
data class MessageBlock(
    val kind: String,   // "thinking" | "tool" | "text"
    val text: String,
    val name: String? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val thinking: String? = null,
    val speaker: String? = null,
    val time: String? = null,
    val blocks: List<MessageBlock> = emptyList(),
    // Imports use data URIs; the repository returns app-private file URIs.
    val images: List<String> = emptyList()
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val importedAt: Long,
    val pinned: Boolean = false,
    val pinnedAt: Long? = null,
    val seen: Boolean = true,
    val contentHash: String = "",
    val scrollIndex: Int? = null,
    val scrollOffset: Int? = null,
    val messages: List<ChatMessage>,
    val sourceFormat: String = "auto",
    val parserVersion: Int = 1,
    val hasSource: Boolean = false,
    val bookmarks: List<Int> = emptyList(),
    val scrollBlock: Int = 0,
    val folderId: String? = null,
    /** UTF-8 bytes of the original import source; null when the size is unknown. */
    val sourceSizeBytes: Long? = null
)

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val importedAt: Long,
    val pinned: Boolean = false,
    val pinnedAt: Long? = null,
    val seen: Boolean = true,
    val contentHash: String = "",
    val messageCount: Int = 0,
    val scrollIndex: Int? = null,
    val scrollOffset: Int? = null,
    val sourceFormat: String = "auto",
    val parserVersion: Int = 1,
    val hasSource: Boolean = false,
    val bookmarks: List<Int> = emptyList(),
    val scrollBlock: Int = 0,
    val folderId: String? = null,
    /** UTF-8 bytes of the original import source; null when the size is unknown. */
    val sourceSizeBytes: Long? = null
)

@Serializable
data class ConversationFolder(val id: String, val name: String, val createdAt: Long)

data class SearchHit(
    val summary: ConversationSummary,
    val snippet: String?,
    val messageIndex: Int? = null
)

/** A validated retained revision. The token is opaque and must be returned unchanged on confirmation. */
data class HistoryPreview(
    val title: String,
    val messageCount: Int,
    val parserVersion: Int,
    val sourceFormat: String,
    val token: String
) {
    val fingerprint: String get() = token
}

/** Categories are disjoint; atomic backups belong to the same category as their primary. */
data class StorageUsage(
    val currentBytes: Long = 0,
    val sourceBytes: Long = 0,
    val assetsBytes: Long = 0,
    val historyBytes: Long = 0,
    val indexBytes: Long = 0,
    val otherBytes: Long = 0
) {
    val totalBytes: Long get() = currentBytes + sourceBytes + assetsBytes + historyBytes + indexBytes + otherBytes
}

/** Only verified older history is eligible. Assets and unrecognized files are always retained. */
data class CleanupPreview(
    val token: String,
    val revisionCount: Int,
    val fileCount: Int,
    val bytes: Long,
    val protectedRevisionCount: Int,
    val skippedRecordCount: Int,
    val unknownFileCount: Int,
    val warnings: List<String>
)

data class CleanupResult(
    val deletedRevisionCount: Int,
    val deletedFileCount: Int,
    val freedBytes: Long,
    val warnings: List<String>
)

/** Synchronous, app-private storage. Call from an IO dispatcher, not the UI thread. */
object ConversationStore {
    private fun repository(context: Context) =
        FileConversationRepository(File(context.filesDir, "conversations"))

    fun list(context: Context): List<ConversationSummary> = repository(context).list()
    fun folders(context: Context): List<ConversationFolder> = repository(context).folders()
    fun createFolder(context: Context, name: String): ConversationFolder = repository(context).createFolder(name)
    fun renameFolder(context: Context, id: String, name: String): Unit = repository(context).renameFolder(id, name)
    fun deleteFolder(context: Context, id: String): Unit = repository(context).deleteFolder(id)
    fun moveToFolder(context: Context, ids: List<String>, folderId: String?): Unit =
        repository(context).moveToFolder(ids, folderId)
    /** IDs of unreadable records; healthy records remain available through list/search. */
    fun issues(context: Context): List<String> = repository(context).issues()
    fun load(context: Context, id: String): Conversation? = repository(context).load(id)
    fun setSeen(context: Context, id: String, seen: Boolean = true): ConversationSummary? =
        repository(context).setSeen(id, seen)
    fun rename(context: Context, id: String, title: String): ConversationSummary? =
        repository(context).rename(id, title)
    fun setPinned(context: Context, id: String, pinned: Boolean): ConversationSummary? =
        repository(context).setPinned(id, pinned)
    fun findDuplicate(context: Context, contentHash: String): ConversationSummary? =
        repository(context).findDuplicate(contentHash)

    fun save(context: Context, conversation: Conversation, source: String? = null) =
        repository(context).save(conversation, source)

    fun updateSummary(context: Context, summary: ConversationSummary) =
        repository(context).updateSummary(summary)

    fun updatePosition(context: Context, id: String, index: Int, offset: Int, blockIndex: Int = 0): ConversationSummary? =
        repository(context).updatePosition(id, index, offset, blockIndex)

    fun toggleBookmark(context: Context, id: String, messageIndex: Int): ConversationSummary? =
        repository(context).toggleBookmark(id, messageIndex)

    /** Null means there is no retained previous revision; damaged history raises an IOException. */
    fun historyPreview(context: Context, id: String): HistoryPreview? = repository(context).historyPreview(id)
    fun restorePrevious(context: Context, id: String, expectedToken: String): Conversation? =
        repository(context).restorePrevious(id, expectedToken)
    fun storageUsage(context: Context): StorageUsage = repository(context).storageUsage()
    fun cleanupPreview(context: Context): CleanupPreview = repository(context).cleanupPreview()
    fun cleanStorage(context: Context, previewToken: String): CleanupResult = repository(context).cleanStorage(previewToken)

    fun readSource(context: Context, id: String): String? = repository(context).readSource(id)
    fun delete(context: Context, id: String) = repository(context).delete(id)

    /** Supply { coroutineContext.ensureActive() } using a captured coroutine context. */
    fun search(context: Context, query: String, cancellationCheck: () -> Unit = {}): List<SearchHit> =
        repository(context).search(query, cancellationCheck)

    fun contentHash(messages: List<ChatMessage>): String = ConversationFingerprint.hash(messages)
    internal fun excerpt(content: String, needle: String): String? = searchExcerpt(content, needle)
    fun exportBackup(context: Context, output: OutputStream): Int = repository(context).exportBackup(output)
    fun restoreBackup(context: Context, input: InputStream): Int = repository(context).restoreBackup(input)
}

fun Conversation.toSummary() = ConversationSummary(
    id = id, title = title, importedAt = importedAt, pinned = pinned, pinnedAt = pinnedAt,
    seen = seen, contentHash = contentHash, messageCount = messages.size,
    scrollIndex = scrollIndex, scrollOffset = scrollOffset, sourceFormat = sourceFormat,
    parserVersion = parserVersion, hasSource = hasSource, bookmarks = bookmarks, scrollBlock = scrollBlock,
    folderId = folderId, sourceSizeBytes = sourceSizeBytes
)
