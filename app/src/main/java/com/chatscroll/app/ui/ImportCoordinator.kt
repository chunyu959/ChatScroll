package com.chatscroll.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.chatscroll.app.R
import com.chatscroll.app.data.ChatMessage
import com.chatscroll.app.data.Conversation
import com.chatscroll.app.data.ConversationStore
import com.chatscroll.app.data.MessageBlock
import com.chatscroll.app.parser.ChatMarkdownParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

sealed interface ImportPayload {
    data class FileUri(val uri: Uri) : ImportPayload
    data class RawText(val text: String) : ImportPayload
}

internal data class ImportRequest(val payload: ImportPayload, val format: String = "auto")
internal enum class ImportChoice { OPEN, SAVE, RETRY, CANCEL, ACKNOWLEDGE }
internal data class ImportWork(
    val token: String,
    val stage: String,
    val title: String? = null,
    val conversationId: String? = null
)

/** Process-owned queue: a new share cannot overwrite one that is still importing. */
object AppState {
    private val queue = Channel<ImportRequest>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val work = MutableStateFlow<ImportWork?>(null)
    private val queued = MutableStateFlow(0)
    internal val importWork = work.asStateFlow()
    val queuedImports = queued.asStateFlow()
    private var decision: CompletableDeferred<ImportChoice>? = null
    private var started = false
    private data class PositionWrite(val context: Context, val id: String, val index: Int, val offset: Int, val block: Int)
    private data class PositionCommand(val write: PositionWrite? = null, val barrier: CompletableDeferred<Unit>? = null)
    private val positionQueue = Channel<PositionCommand>(Channel.UNLIMITED)
    private val positionFailure = MutableStateFlow(false)
    internal val positionSaveFailed = positionFailure.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (command in positionQueue) {
                command.write?.let {
                    try { ConversationStore.updatePosition(it.context, it.id, it.index, it.offset, it.block) }
                    catch (_: Exception) { positionFailure.value = true }
                }
                command.barrier?.complete(Unit)
            }
        }
    }

    internal fun clearPositionFailure() { positionFailure.value = false }

    internal suspend fun flushPositions() {
        val barrier = CompletableDeferred<Unit>()
        positionQueue.send(PositionCommand(barrier = barrier))
        barrier.await()
    }

    fun enqueue(context: Context, payload: ImportPayload, format: String = "auto") {
        start(context.applicationContext)
        queued.value += 1
        if (queue.trySend(ImportRequest(payload, format)).isFailure) queued.value -= 1
    }

    internal fun choose(token: String, choice: ImportChoice) {
        if (work.value?.token == token) decision?.complete(choice)
    }

    private fun start(context: Context) {
        if (started) return
        started = true
        scope.launch {
            for (request in queue) {
                val token = UUID.randomUUID().toString()
                var raw: String? = null
                var finished = false
                while (!finished) {
                    work.value = ImportWork(token, "reading")
                    try {
                        val source = raw ?: withContext(Dispatchers.IO) { readImport(context, request.payload) }.also { raw = it }
                        val fresh = withContext(Dispatchers.Default) {
                            val title = if (request.payload is ImportPayload.FileUri) {
                                withContext(Dispatchers.IO) { importName(context, request.payload.uri) }
                            } else null
                            conversationFromSource(source, title ?: context.getString(R.string.untitled_conversation), request.format)
                        }
                        val existing = withContext(Dispatchers.IO) {
                            ConversationStore.findDuplicate(context, fresh.contentHash)
                        }
                        val action = if (existing == null) ImportChoice.SAVE else {
                            awaitDecision(ImportWork(token, "duplicate", existing.title, existing.id))
                        }
                        val id = when (action) {
                            ImportChoice.SAVE -> {
                                work.value = ImportWork(token, "saving")
                                withContext(Dispatchers.IO) { ConversationStore.save(context, fresh, source) }
                                fresh.id
                            }
                            ImportChoice.OPEN -> existing?.id
                            else -> null
                        }
                        if (id != null) awaitDecision(ImportWork(token, "complete", conversationId = id))
                        finished = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The input remains queued so a failed write can be retried.
                        finished = awaitDecision(ImportWork(token, "failed")) != ImportChoice.RETRY
                    }
                }
                work.value = null
                queued.value = (queued.value - 1).coerceAtLeast(0)
            }
        }
    }

    private suspend fun awaitDecision(state: ImportWork): ImportChoice {
        val response = CompletableDeferred<ImportChoice>()
        decision = response
        work.value = state
        return try { response.await() } finally { decision = null }
    }

    /** Position writes must survive a reader leaving composition. */
    fun savePosition(context: Context, id: String, index: Int, offset: Int, block: Int) {
        positionQueue.trySend(PositionCommand(PositionWrite(context.applicationContext, id, index, offset, block)))
    }
}

internal fun conversationFromSource(source: String, fallbackTitle: String, format: String): Conversation {
    val parsed = ChatMarkdownParser.parse(source, format)
    require(parsed.messages.isNotEmpty()) { "Empty conversation" }
    val messages = parsed.messages.map {
        ChatMessage(
            role = if (it.role == ChatMarkdownParser.Role.USER) "user" else "assistant",
            content = it.content, thinking = it.thinking, speaker = it.speaker, time = it.time,
            blocks = it.blocks.map { block -> MessageBlock(block.kind, block.text, block.name) }, images = it.images
        )
    }
    return Conversation(
        title = (parsed.title ?: fallbackTitle).take(100), importedAt = System.currentTimeMillis(), seen = false,
        contentHash = ConversationStore.contentHash(messages), messages = messages,
        sourceFormat = if (format == "auto") ChatMarkdownParser.detectFormat(source) else format,
        parserVersion = ChatMarkdownParser.VERSION, hasSource = true
    )
}

private fun readImport(context: Context, payload: ImportPayload): String = when (payload) {
    is ImportPayload.RawText -> payload.text
    is ImportPayload.FileUri -> context.contentResolver.openInputStream(payload.uri)
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw IOException("Cannot read import")
}.also { require(it.isNotBlank()) { "Empty import" } }

private fun importName(context: Context, uri: Uri): String? = context.contentResolver.query(
    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0).removeSuffix(".markdown").removeSuffix(".md") else null
}
