package com.chatscroll.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class MessageBlock(
    val kind: String,   // "thinking" | "tool" | "text"
    val text: String,
    val name: String? = null
)

@Serializable
data class ChatMessage(
    val role: String,   // "user" | "assistant"
    val content: String,
    val thinking: String? = null,
    // Who wrote the message and when, when the export recorded both. Empty for
    // conversations imported before this was kept, and for exports with no names.
    val speaker: String? = null,
    val time: String? = null,
    // The reply in the order it was written: prose, a tool call, more prose.
    // "text" blocks are the visible reply; the rest fold away. Empty for
    // conversations imported before this was recorded; those still use [thinking]
    // and [content].
    val blocks: List<MessageBlock> = emptyList(),
    // Embedded images, kept apart from the text so a single screenshot does not
    // turn the message into half a million characters of layout. Empty for
    // conversations imported before images were split out.
    val images: List<String> = emptyList()
)

/**
 * Full conversation, including message bodies.
 *
 * [contentHash] is a fingerprint of the messages only (never the title), so two
 * files that say exactly the same thing can be recognized even when their names
 * differ. [seen] is false until the conversation has been opened once.
 * [pinnedAt] remembers when it was pinned, so the most recently pinned one
 * stays on top.
 */
@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val importedAt: Long,
    val pinned: Boolean = false,
    val pinnedAt: Long? = null,
    val seen: Boolean = true,
    val contentHash: String = "",
    // Which message was at the top of the screen when reading stopped, and how many
    // pixels into that message. Both are known exactly, unlike a pixel distance,
    // which has to guess the height of messages that are not on screen. Null until
    // the conversation has been opened from the list once, so a fresh import always
    // starts at the top.
    val scrollIndex: Int? = null,
    val scrollOffset: Int? = null,
    val messages: List<ChatMessage>
)

/**
 * The lightweight record shown in the list. Message bodies (which can hold
 * large images) live in a separate file and are only read when a conversation
 * is opened.
 */
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
    val scrollOffset: Int? = null
)

/** One conversation a search matched, with a short quote from its text when it matched there. */
data class SearchHit(
    val summary: ConversationSummary,
    val snippet: String?
)

/**
 * File storage inside the app-private directory. Each conversation is a pair of
 * JSON files: `<id>.meta.json` for the list, `<id>.json` for the full body.
 * Fully offline, no external dependencies.
 *
 * Files written by version 1.0.0 contain the whole conversation in `<id>.json`
 * and have no meta file; they are split apart the first time the list is read.
 */
object ConversationStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "conversations").apply { mkdirs() }

    /** Summaries for the list: pinned first (newest pin on top), then by import time. */
    fun list(context: Context): List<ConversationSummary> {
        migrateLegacy(context)
        return dir(context).listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<ConversationSummary>(file.readText()) }.getOrNull()
            }
            ?.sortedWith(
                compareByDescending<ConversationSummary> { it.pinned }
                    .thenByDescending { it.pinnedAt ?: 0L }
                    .thenByDescending { it.importedAt }
            )
            ?: emptyList()
    }

    /** Reads one full conversation. Returns null when the file is missing or unreadable. */
    fun load(context: Context, id: String): Conversation? =
        runCatching {
            json.decodeFromString<Conversation>(File(dir(context), "$id.json").readText())
        }.getOrNull()

    /**
     * Finds a conversation whose messages are byte-for-byte the same.
     * Titles are ignored on purpose: same name does not mean same chat.
     */
    fun findDuplicate(context: Context, contentHash: String): ConversationSummary? {
        if (contentHash.isEmpty()) return null
        migrateLegacy(context)
        return dir(context).listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }
            ?.firstNotNullOfOrNull { file ->
                runCatching { json.decodeFromString<ConversationSummary>(file.readText()) }
                    .getOrNull()
                    ?.takeIf { it.contentHash == contentHash }
            }
    }

    fun save(context: Context, conversation: Conversation) {
        val hash = conversation.contentHash.ifEmpty { contentHash(conversation.messages) }
        val stored = conversation.copy(contentHash = hash)
        val folder = dir(context)
        File(folder, stored.id + ".json").writeText(json.encodeToString(stored))
        File(folder, stored.id + ".meta.json").writeText(json.encodeToString(stored.toSummary()))
    }

    /** Updates list-visible fields without rewriting the (potentially large) body file. */
    fun updateSummary(context: Context, summary: ConversationSummary) {
        File(dir(context), summary.id + ".meta.json")
            .writeText(json.encodeToString(summary))
    }

    fun delete(context: Context, id: String) {
        val folder = dir(context)
        File(folder, "$id.json").delete()
        File(folder, "$id.meta.json").delete()
    }

    /**
     * Conversations whose title or message text contains [query], read one file at a
     * time so a long list is never held in memory all at once.
     *
     * Only the visible text counts: the title and what was said. Folded reasoning and
     * tool calls are skipped, since a match inside them could not be shown.
     */
    fun search(context: Context, query: String): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        migrateLegacy(context)
        val hits = ArrayList<SearchHit>()
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".meta.json") }
            ?.forEach { file ->
                val summary = runCatching {
                    json.decodeFromString<ConversationSummary>(file.readText())
                }.getOrNull() ?: return@forEach
                val titleMatch = summary.title.contains(needle, ignoreCase = true)
                val conversation = load(context, summary.id) ?: return@forEach
                val snippet = conversation.messages.firstNotNullOfOrNull { message ->
                    excerpt(message.content, needle)
                }
                if (titleMatch || snippet != null) {
                    hits.add(SearchHit(summary, snippet))
                }
            }
        return hits.sortedWith(
            compareByDescending<SearchHit> { it.summary.pinned }
                .thenByDescending { it.summary.pinnedAt ?: 0L }
                .thenByDescending { it.summary.importedAt }
        )
    }

    /** A short window of [content] around the first match of [needle], or null. */
    internal fun excerpt(content: String, needle: String): String? {
        val at = content.indexOf(needle, ignoreCase = true)
        if (at < 0) return null
        val start = (at - 20).coerceAtLeast(0)
        val end = (at + needle.length + 20).coerceAtMost(content.length)
        val core = content.substring(start, end).replace('\n', ' ').trim()
        return (if (start > 0) "…" else "") + core + (if (end < content.length) "…" else "")
    }

    /** Fingerprint of the messages. The title is deliberately not part of it. */
    fun contentHash(messages: List<ChatMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        messages.forEach { message ->
            digest.update(message.role.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(message.content.toByteArray(Charsets.UTF_8))
            digest.update(0)
            message.thinking?.let { digest.update(it.toByteArray(Charsets.UTF_8)) }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Version 1.0.0 stored everything in `<id>.json`. Split those into a meta
     * file plus a body file so the list no longer has to read every message.
     */
    private fun migrateLegacy(context: Context) {
        val folder = dir(context)
        folder.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(".meta.json") }
            ?.forEach { file ->
                val meta = File(folder, file.nameWithoutExtension + ".meta.json")
                if (meta.exists()) return@forEach
                val conversation = runCatching {
                    json.decodeFromString<Conversation>(file.readText())
                }.getOrNull() ?: return@forEach
                save(context, conversation)
            }
    }
}

private fun Conversation.toSummary() = ConversationSummary(
    id = id,
    title = title,
    importedAt = importedAt,
    pinned = pinned,
    pinnedAt = pinnedAt,
    seen = seen,
    contentHash = contentHash,
    messageCount = messages.size,
    scrollIndex = scrollIndex,
    scrollOffset = scrollOffset
)
