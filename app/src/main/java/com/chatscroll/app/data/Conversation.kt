package com.chatscroll.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ChatMessage(
    val role: String,   // "user" | "assistant"
    val content: String
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val importedAt: Long,
    val messages: List<ChatMessage>
)

/**
 * Simple file-based storage: each conversation is one JSON document inside the
 * app-private files directory. No external dependencies, fully offline.
 */
object ConversationStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "conversations").apply { mkdirs() }

    fun list(context: Context): List<Conversation> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString<Conversation>(file.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()

    fun save(context: Context, conversation: Conversation) {
        File(dir(context), conversation.id + ".json")
            .writeText(json.encodeToString(conversation))
    }

    fun delete(context: Context, id: String) {
        File(dir(context), id + ".json").delete()
    }
}
