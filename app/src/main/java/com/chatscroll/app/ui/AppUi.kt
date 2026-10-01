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
import com.chatscroll.app.data.ConversationStore
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

        val conversation = Conversation(
            title = (parsed.title ?: fallbackTitle).take(100),
            importedAt = System.currentTimeMillis(),
            messages = parsed.messages.map {
                ChatMessage(
                    role = if (it.role == ChatMarkdownParser.Role.USER) "user" else "assistant",
                    content = it.content
                )
            }
        )
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
    val conversations = remember { mutableStateListOf<Conversation>() }
    var screen by rememberSaveable { mutableStateOf("list") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    fun refresh() {
        conversations.clear()
        conversations.addAll(ConversationStore.list(context))
    }

    LaunchedEffect(Unit) { refresh() }

    // Handle imports from any entry point (file picker, share intent, shared text)
    val pending by AppState.pendingImport.collectAsState()
    LaunchedEffect(pending) {
        val payload = pending ?: return@LaunchedEffect
        AppState.pendingImport.value = null
        when (val outcome = importPayload(context, payload)) {
            is ImportOutcome.Success -> {
                refresh()
                openId = outcome.conversation.id
                screen = "chat"
                Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
            }
            ImportOutcome.Empty -> Toast.makeText(
                context, R.string.import_empty, Toast.LENGTH_SHORT
            ).show()
            ImportOutcome.Failure -> Toast.makeText(
                context, R.string.import_failed, Toast.LENGTH_SHORT
            ).show()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) AppState.pendingImport.value = ImportPayload.FileUri(uri)
    }

    val openConversation = openId?.let { id -> conversations.firstOrNull { it.id == id } }

    if (screen == "chat" && openConversation != null) {
        ChatScreen(conversation = openConversation, onBack = { screen = "list" })
    } else {
        ListScreen(
            conversations = conversations.toList(),
            onOpen = { conversation ->
                openId = conversation.id
                screen = "chat"
            },
            onImport = {
                filePicker.launch(
                    arrayOf("text/markdown", "text/plain", "application/octet-stream")
                )
            },
            onDeleteRequest = { deleteTarget = it }
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
}
