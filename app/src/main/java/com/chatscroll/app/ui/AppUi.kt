package com.chatscroll.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.chatscroll.app.R
import com.chatscroll.app.data.ChatMessage
import com.chatscroll.app.data.Conversation
import com.chatscroll.app.data.MessageBlock
import com.chatscroll.app.data.ConversationStore
import com.chatscroll.app.data.ConversationSummary
import com.chatscroll.app.parser.ChatMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

sealed interface ImportPayload {
    data class FileUri(val uri: Uri) : ImportPayload
    data class RawText(val text: String) : ImportPayload
}

object AppState {
    val pendingImport = MutableStateFlow<ImportPayload?>(null)
}

private sealed interface ImportOutcome {
    data class Success(val conversation: Conversation) : ImportOutcome
    data class Duplicate(val existing: ConversationSummary, val fresh: Conversation) : ImportOutcome
    object Empty : ImportOutcome
    object Failure : ImportOutcome
}

private suspend fun importPayload(context: Context, payload: ImportPayload): ImportOutcome =
    withContext(Dispatchers.IO) {
        val text: String? = when (payload) {
            is ImportPayload.FileUri -> runCatching {
                context.contentResolver.openInputStream(payload.uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull()
            is ImportPayload.RawText -> payload.text
        }
        if (text.isNullOrEmpty()) return@withContext ImportOutcome.Failure

        val parsed = ChatMarkdownParser.parse(text)
        if (parsed.messages.isEmpty()) return@withContext ImportOutcome.Empty

        val fallbackTitle = when (payload) {
            is ImportPayload.FileUri -> displayName(context, payload)
            is ImportPayload.RawText -> context.getString(R.string.import_shared_text)
        } ?: context.getString(R.string.untitled_conversation)

        val messages = parsed.messages.map {
            ChatMessage(
                role = if (it.role == ChatMarkdownParser.Role.USER) "user" else "assistant",
                content = it.content,
                thinking = it.thinking,
                blocks = it.blocks.map { block ->
                    MessageBlock(kind = block.kind, text = block.text, name = block.name)
                },
                images = it.images
            )
        }
        val conversation = Conversation(
            title = (parsed.title ?: fallbackTitle).take(100),
            importedAt = System.currentTimeMillis(),
            seen = false,
            contentHash = ConversationStore.contentHash(messages),
            messages = messages
        )
        // Same words, regardless of file name: ask before saving a second copy.
        val existing = ConversationStore.findDuplicate(context, conversation.contentHash)
        if (existing != null) return@withContext ImportOutcome.Duplicate(existing, conversation)
        ConversationStore.save(context, conversation)
        ImportOutcome.Success(conversation)
    }

private fun displayName(context: Context, payload: ImportPayload.FileUri): String? =
    runCatching {
        context.contentResolver.query(
            payload.uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
            ?.removeSuffix(".markdown")
            ?.removeSuffix(".md")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

@Composable
fun Root() {
    val context = LocalContext.current
    val conversations = remember { mutableStateListOf<ConversationSummary>() }
    var screen by rememberSaveable { mutableStateOf("list") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Conversation?>(null) }

    // Long-press action sheet state
    var actionTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var duplicate by remember { mutableStateOf<ImportOutcome.Duplicate?>(null) }

    fun refresh() {
        conversations.clear()
        conversations.addAll(ConversationStore.list(context))
    }

    fun openConversation(conversation: Conversation, markSeen: Boolean) {
        val shown = if (markSeen && !conversation.seen) {
            val updated = conversation.copy(seen = true)
            ConversationStore.updateSummary(context, updated.toSummary())
            updated
        } else {
            conversation
        }
        opened = shown
        openId = conversation.id
        screen = "chat"
        refresh()
    }

    LaunchedEffect(Unit) { refresh() }

    // Handle imports from any entry point (file picker, share intent, shared text).
    // collect (not a keyed LaunchedEffect) so processing can never be cancelled
    // midway by the state reset below.
    LaunchedEffect(Unit) {
        AppState.pendingImport.collect { payload ->
            payload ?: return@collect
            AppState.pendingImport.value = null
            when (val outcome = importPayload(context, payload)) {
                is ImportOutcome.Success -> {
                    refresh()
                    // Opening straight after import does not count as read, so the
                    // "new" badge survives until the conversation is opened from the list.
                    openConversation(outcome.conversation, markSeen = false)
                    Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
                }
                is ImportOutcome.Duplicate -> duplicate = outcome
                ImportOutcome.Empty -> Toast.makeText(
                    context, R.string.import_empty, Toast.LENGTH_SHORT
                ).show()
                ImportOutcome.Failure -> Toast.makeText(
                    context, R.string.import_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) AppState.pendingImport.value = ImportPayload.FileUri(uri)
    }

    fun updateSummary(summary: ConversationSummary) {
        ConversationStore.updateSummary(context, summary)
        refresh()
        // The open screen holds its own copy, so a rename shows up immediately.
        opened?.takeIf { it.id == summary.id }?.let {
            opened = it.copy(title = summary.title, pinned = summary.pinned, pinnedAt = summary.pinnedAt)
        }
    }

    val current = opened
    if (screen == "chat" && current != null) {
        ChatScreen(conversation = current, onBack = { screen = "list" })
    } else {
        ListScreen(
            conversations = conversations.toList(),
            onOpen = { summary ->
                val conversation = ConversationStore.load(context, summary.id)
                if (conversation != null) {
                    // The list copy is what rename and pin update, so it wins over
                    // the copy stored alongside the message body.
                    openConversation(
                        conversation.copy(
                            title = summary.title,
                            pinned = summary.pinned,
                            pinnedAt = summary.pinnedAt
                        ),
                        markSeen = true
                    )
                } else {
                    Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            },
            onImport = {
                filePicker.launch(
                    arrayOf("text/markdown", "text/plain", "application/octet-stream")
                )
            },
            onItemAction = { actionTarget = it }
        )
    }

    actionTarget?.let { target ->
        ConversationActionSheet(
            conversation = target,
            onDismiss = { actionTarget = null },
            onRename = {
                renameTarget = target
                actionTarget = null
            },
            onTogglePin = {
                val pinned = !target.pinned
                updateSummary(
                    target.copy(
                        pinned = pinned,
                        pinnedAt = if (pinned) System.currentTimeMillis() else null
                    )
                )
                actionTarget = null
            },
            onDelete = {
                deleteTarget = target
                actionTarget = null
            }
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            currentTitle = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { newTitle ->
                updateSummary(target.copy(title = newTitle.take(100)))
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_text, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    ConversationStore.delete(context, target.id)
                    refresh()
                    if (openId == target.id) {
                        openId = null
                        screen = "list"
                    }
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.action_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    duplicate?.let { pending ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text(stringResource(R.string.duplicate_dialog_title)) },
            text = { Text(stringResource(R.string.duplicate_dialog_text, pending.existing.title)) },
            confirmButton = {
                TextButton(onClick = {
                    val existing = ConversationStore.load(context, pending.existing.id)
                    duplicate = null
                    if (existing != null) {
                        openConversation(existing, markSeen = false)
                    } else {
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.duplicate_open_existing))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    ConversationStore.save(context, pending.fresh)
                    refresh()
                    openConversation(pending.fresh, markSeen = false)
                    duplicate = null
                    Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.duplicate_import_anyway))
                }
            }
        )
    }
}

/** List-visible fields of a full conversation, derived without touching storage. */
private fun Conversation.toSummary() = ConversationSummary(
    id = id,
    title = title,
    importedAt = importedAt,
    pinned = pinned,
    pinnedAt = pinnedAt,
    seen = seen,
    contentHash = contentHash,
    messageCount = messages.size
)
