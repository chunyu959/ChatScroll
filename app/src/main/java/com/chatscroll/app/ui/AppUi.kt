package com.chatscroll.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chatscroll.app.R
import com.chatscroll.app.data.*
import com.chatscroll.app.parser.ChatMarkdownParser
import com.chatscroll.app.ui.theme.ChatScrollTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Composable
fun Root() {
    val context = LocalContext.current
    var theme by remember { mutableStateOf(Settings.themeMode(context)) }
    var fontSize by remember { mutableStateOf(Settings.fontSize(context)) }
    var lineHeight by remember { mutableStateOf(Settings.lineHeight(context)) }
    ChatScrollTheme(themeMode = theme) {
        ProvideReaderRendering(ReaderPreferences(fontSize, lineHeight)) {
            AppContent(theme, fontSize, lineHeight,
                onTheme = { theme = it; Settings.setThemeMode(context, it) },
                onFont = { fontSize = it; Settings.setFontSize(context, it) },
                onLine = { lineHeight = it; Settings.setLineHeight(context, it) })
        }
    }
}

@Composable
private fun AppContent(
    theme: String, fontSize: Float, lineHeight: Float,
    onTheme: (String) -> Unit, onFont: (Float) -> Unit, onLine: (Float) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var conversations by remember { mutableStateOf<List<ConversationSummary>>(emptyList()) }
    var folders by remember { mutableStateOf<List<ConversationFolder>>(emptyList()) }
    var selectedFolder by rememberSaveable { mutableStateOf(Settings.libraryFolder(context)) }
    var sortOrder by rememberSaveable { mutableStateOf(Settings.libraryOrder(context)) }
    var moveIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var screen by rememberSaveable { mutableStateOf("list") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<Conversation?>(null) }
    var readerSession by remember { mutableIntStateOf(0) }
    val activeReaderSession = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var busy by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var storageIssues by remember { mutableStateOf<List<String>>(emptyList()) }
    var showStorageIssues by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchHits by remember { mutableStateOf<List<SearchHit>?>(null) }
    var initialMessage by rememberSaveable { mutableStateOf<Int?>(null) }
    var initialQuery by rememberSaveable { mutableStateOf("") }
    var rememberPosition by remember { mutableStateOf(Settings.rememberPosition(context)) }
    var rememberSeconds by remember { mutableStateOf(Settings.rememberSeconds(context)) }
    var pureReading by remember { mutableStateOf(Settings.pureReading(context)) }
    var storageUsage by remember { mutableStateOf<StorageUsage?>(null) }
    var cleanupPreview by remember { mutableStateOf<CleanupPreview?>(null) }
    var historyPreview by remember { mutableStateOf<Pair<String, HistoryPreview>?>(null) }
    val historyPaused = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var formatDialog by remember { mutableStateOf(false) }
    var reparseDialog by remember { mutableStateOf(false) }
    var importFormat by rememberSaveable { mutableStateOf("auto") }
    var source by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val importWork by AppState.importWork.collectAsState()
    val queued by AppState.queuedImports.collectAsState()
    val positionFailed by AppState.positionSaveFailed.collectAsState()

    LaunchedEffect(positionFailed) {
        if (positionFailed) { showError = true; AppState.clearPositionFailure() }
    }

    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            Triple(ConversationStore.list(context), ConversationStore.issues(context), ConversationStore.folders(context))
        }
        conversations = result.first
        folders = result.third
        if (selectedFolder != "all" && selectedFolder != "unfiled" && folders.none { it.id == selectedFolder }) {
            selectedFolder = "all"
            Settings.setLibraryFolder(context, "all")
        }
        if (result.second.isNotEmpty() && result.second != storageIssues) showStorageIssues = true
        storageIssues = result.second
    }
    suspend fun open(id: String, message: Int? = null, needle: String = "", markSeen: Boolean = true) {
        AppState.flushPositions()
        val value = withContext(Dispatchers.IO) {
            if (markSeen) ConversationStore.setSeen(context, id)
            ConversationStore.load(context, id) ?: error("Missing conversation")
        }
        initialMessage = message
        initialQuery = needle
        readerSession = activeReaderSession.incrementAndGet()
        opened = value
        openId = id
        screen = "chat"
        refresh()
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ConversationStorageConflictException) {
                Toast.makeText(context, R.string.polish_preview_stale, Toast.LENGTH_LONG).show()
            }
            catch (_: Exception) { showError = true }
            finally { busy = false }
        }
    }
    suspend fun refreshStorage() {
        val data = withContext(Dispatchers.IO) {
            ConversationStore.storageUsage(context) to ConversationStore.cleanupPreview(context)
        }
        storageUsage = data.first
        cleanupPreview = data.second
    }
    fun organize(action: suspend () -> Unit) {
        perform {
            try {
                withContext(Dispatchers.IO) { action() }
                refresh()
            } catch (_: IllegalArgumentException) {
                Toast.makeText(context, R.string.organization_invalid_folder, Toast.LENGTH_LONG).show()
            }
        }
    }
    fun persistUri(uri: Uri) {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Some providers grant this URI only for this process. */ }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistUri(it); AppState.enqueue(context, ImportPayload.FileUri(it), importFormat) }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform {
            val count = withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { ConversationStore.exportBackup(context, it) }
                    ?: error("Cannot create backup")
            }
            Toast.makeText(context, context.getString(R.string.library_backup_done, count), Toast.LENGTH_LONG).show()
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persistUri(it); restoreUri = it }
    }
    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = source
        if (uri != null && text != null) perform {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                    ?: error("Cannot export source")
            }
        }
    }
    val attachSourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = openId
        if (uri != null && id != null) perform {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("Cannot read source")
            }
            source = text
            reparseDialog = true
        }
    }

    LaunchedEffect(Unit) {
        try {
            refresh()
            if (screen == "storage") refreshStorage()
            if ((screen == "chat" || screen == "source") && openId != null) open(openId!!)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { screen = "list"; showError =true }
    }
    LaunchedEffect(screen) {
        if (screen == "list") {
            try { refresh() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError =true }
        }
    }
    LaunchedEffect(query, searching, conversations) {
        searchHits = null
        if (!searching || query.isBlank()) return@LaunchedEffect
        delay(250)
        try {
            val jobContext = coroutineContext
            searchHits = withContext(Dispatchers.IO) {
                ConversationStore.search(context, query) { jobContext.ensureActive() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { showError =true; searchHits = emptyList() }
    }
    LaunchedEffect(importWork?.token, importWork?.stage) {
        val work = importWork ?: return@LaunchedEffect
        if (work.stage == "complete") {
            try { work.conversationId?.let { open(it, markSeen = false) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showError =true }
            finally { AppState.choose(work.token, ImportChoice.ACKNOWLEDGE) }
        }
    }

    val ordering = remember(sortOrder, context.resources.configuration.locales[0]) {
        ConversationOrdering.comparator(sortOrder, context.resources.configuration.locales[0])
    }
    val visibleConversations = remember(conversations, selectedFolder, ordering) {
        conversations.filter { ConversationOrdering.inFolder(it.folderId, selectedFolder) }.sortedWith(ordering)
    }
    val visibleHits = remember(searchHits, selectedFolder, ordering) {
        searchHits?.filter { ConversationOrdering.inFolder(it.summary.folderId, selectedFolder) }
            ?.sortedWith(Comparator { a, b -> ordering.compare(a.summary, b.summary) })
    }

    var previousScreen by remember { mutableStateOf(screen) }
    val returning = screen == "list" || (screen == "settings" && previousScreen == "storage") ||
        (screen == "chat" && previousScreen == "source")
    LaunchedEffect(screen) { previousScreen = screen }
    val pageKey = if (screen == "chat") "chat:$openId:$readerSession" else screen
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val current = opened
        ReaderPage(pageKey, returning) {
        when {
            screen == "settings" -> SettingsScreen(
                rememberPosition, rememberSeconds,
                onRememberChange = { rememberPosition = it; Settings.setRememberPosition(context, it) },
                onSecondsChange = { rememberSeconds = it; Settings.setRememberSeconds(context, it) },
                onBack = { screen = "list" },
                themeMode = theme, fontSize = fontSize, lineHeight = lineHeight,
                onThemeChange = onTheme, onFontSizeChange = onFont, onLineHeightChange = onLine,
                onBackup = { backupPicker.launch("ChatScroll-backup.zip") },
                onRestore = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                backupBusy = busy,
                pureReading = pureReading,
                onPureReadingChange = { pureReading = it; Settings.setPureReading(context, it) },
                onStorage = { perform { refreshStorage(); screen = "storage" } }
            )
            screen == "storage" -> StorageScreen(storageUsage, cleanupPreview, busy,
                onBack = { screen = "settings" },
                onRefresh = { perform { refreshStorage() } },
                onClean = { token -> perform {
                    val result = withContext(Dispatchers.IO) { ConversationStore.cleanStorage(context, token) }
                    refreshStorage()
                    Toast.makeText(context, context.getString(R.string.polish_cleanup_done, result.deletedFileCount,
                        android.text.format.Formatter.formatFileSize(context, result.freedBytes)), Toast.LENGTH_LONG).show()
                } }
            )
            screen == "source" && source != null -> SourceScreen(
                source!!, onBack = { val id = openId; if (id != null) perform { open(id) } else screen = "list" },
                onExport = { sourcePicker.launch("${current?.title ?: "conversation"}.md") }
            )
            screen == "chat" && current != null -> key(readerSession) {
                val ownerSession = readerSession
                ChatScreen(
                conversation = current, rememberPosition = rememberPosition,
                rememberSeconds = rememberSeconds, initialMessageIndex = initialMessage, initialQuery = initialQuery,
                onBack = { screen = "list"; initialMessage = null; initialQuery = "" },
                onScroll = { index, offset, block ->
                    // Capture this reader's identity. Disposal must never write into another reader.
                    if (activeReaderSession.get() == ownerSession && !historyPaused.get()) {
                        AppState.savePosition(context, current.id, index, offset, block)
                    }
                },
                onBookmark = { index -> perform {
                    withContext(Dispatchers.IO) { ConversationStore.toggleBookmark(context, current.id, index) }
                    val updated = withContext(Dispatchers.IO) { ConversationStore.load(context, current.id) }
                    if (opened?.id == current.id) opened = updated
                    refresh()
                } },
                onViewSource = { perform {
                    source = withContext(Dispatchers.IO) { ConversationStore.readSource(context, current.id) }
                    if (source != null) screen = "source"
                    else attachSourcePicker.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream"))
                } },
                onReparse = { perform {
                    source = withContext(Dispatchers.IO) { ConversationStore.readSource(context, current.id) }
                    if (source != null) reparseDialog = true
                    else attachSourcePicker.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream"))
                } },
                pureReading = pureReading,
                onRestorePrevious = { perform {
                    historyPaused.set(true)
                    try {
                        AppState.flushPositions()
                        val preview = withContext(Dispatchers.IO) { ConversationStore.historyPreview(context, current.id) }
                        if (preview == null) Toast.makeText(context, R.string.polish_history_none, Toast.LENGTH_LONG).show()
                        else historyPreview = current.id to preview
                    } finally {
                        if (historyPreview == null) historyPaused.set(false)
                    }
                } }
                )
            }
            else -> {
                if (selecting) BackHandler { selecting = false; selectedIds = emptyList() }
                ListScreen(
                    visibleConversations, visibleHits, selecting, selectedIds.toSet(),
                    onOpen = { summary -> perform { open(summary.id) } },
                    onImport = { formatDialog = true }, onItemAction = { actionTarget = it },
                    onQueryChange = { query = it }, onSettings = { screen = "settings" },
                    onToggleSelected = { item -> selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id },
                    onExitSelection = { selecting = false; selectedIds = emptyList() },
                    onDeleteSelected = { deleteIds = selectedIds },
                    searching = searching, query = query, onSearchingChange = { searching = it },
                    onOpenSearch = { hit -> perform { open(hit.summary.id, hit.messageIndex, query) } },
                    folders = folders, selectedFolder = selectedFolder, sortOrder = sortOrder,
                    onFolderChange = { folder -> selectedFolder = folder; Settings.setLibraryFolder(context, folder)
                        selecting = false; selectedIds = emptyList() },
                    onSortChange = { order -> sortOrder = ConversationOrdering.normalized(order); Settings.setLibraryOrder(context, sortOrder) },
                    onCreateFolder = { name -> organize { ConversationStore.createFolder(context, name) } },
                    onRenameFolder = { id, name -> organize { ConversationStore.renameFolder(context, id, name) } },
                    onDeleteFolder = { id -> organize { ConversationStore.deleteFolder(context, id) } },
                    onMoveSelected = { if (selectedIds.isNotEmpty()) moveIds = selectedIds.toList() }
                )
            }
        }
        }
        if (busy || importWork?.stage in listOf("reading", "saving")) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        if (queued > 1) {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp), tonalElevation = 6.dp) {
                Text(stringResource(R.string.library_import_queue, queued), Modifier.padding(12.dp))
            }
        }
    }

    historyPreview?.let { (id, preview) -> HistoryRestoreDialog(preview,
        onDismiss = { historyPreview = null; historyPaused.set(false) },
        onConfirm = {
            if (!busy) {
                historyPreview = null
                perform {
                    val oldSession = activeReaderSession.getAndIncrement()
                    val invalidatedSession = oldSession + 1
                    try {
                        AppState.flushPositions()
                        withContext(Dispatchers.IO) {
                            ConversationStore.restorePrevious(context, id, preview.token) ?: error("Missing history")
                        }
                        open(id)
                        Toast.makeText(context, R.string.polish_history_done, Toast.LENGTH_LONG).show()
                    } finally {
                        activeReaderSession.compareAndSet(invalidatedSession, oldSession)
                        historyPaused.set(false)
                    }
                }
            }
        }
    ) }

    if (formatDialog) FormatDialog(
        reparse = false, onDismiss = { formatDialog = false },
        onConfirm = { format -> importFormat = format; formatDialog = false
            filePicker.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream")) }
    )
    if (reparseDialog) FormatDialog(
        reparse = true, onDismiss = { reparseDialog = false },
        onConfirm = { format ->
            val id = openId
            val text = source
            reparseDialog = false
            if (id != null && text != null) perform {
                val oldSession = activeReaderSession.getAndIncrement()
                val invalidatedSession = oldSession + 1
                try {
                AppState.flushPositions()
                withContext(Dispatchers.IO) {
                    val previous = ConversationStore.load(context, id) ?: error("Missing conversation")
                    val parsed = conversationFromSource(text, previous.title, format)
                    require(parsed.messages.isNotEmpty())
                    // Position is tied to the old segmentation. Rebuild safely from the top;
                    // only keep bookmark indices when the message count remains compatible.
                    val replacement = parsed.copy(
                        id = previous.id, title = previous.title, importedAt = previous.importedAt,
                        pinned = previous.pinned, pinnedAt = previous.pinnedAt, seen = previous.seen,
                        bookmarks = if (parsed.messages.size == previous.messages.size) previous.bookmarks else emptyList(),
                        scrollIndex = if (parsed.messages.size == previous.messages.size) previous.scrollIndex else null,
                        scrollOffset = 0, scrollBlock = 0
                    )
                    ConversationStore.save(context, replacement, text)
                }
                open(id)
                Toast.makeText(context, R.string.library_reparse_done, Toast.LENGTH_LONG).show()
                } finally {
                    // Failure keeps the existing reader active. A successful open owns a newer generation.
                    activeReaderSession.compareAndSet(invalidatedSession, oldSession)
                }
            }
        }
    )

    actionTarget?.let { target -> ConversationActionSheet(
        target, onDismiss = { actionTarget = null },
        onRename = { renameTarget = target; actionTarget = null },
        onTogglePin = { actionTarget = null; perform {
            withContext(Dispatchers.IO) {
                ConversationStore.setPinned(context, target.id, !target.pinned)
            }; refresh()
        } },
        onDelete = { deleteIds = listOf(target.id); actionTarget = null },
        onBatchDelete = { selecting = true; selectedIds = listOf(target.id); actionTarget = null },
        onMove = { moveIds = listOf(target.id); actionTarget = null }
    ) }
    if (moveIds.isNotEmpty()) {
        val currentFolder = moveIds.map { id -> conversations.firstOrNull { it.id == id }?.folderId }
            .distinct().singleOrNull()
        MoveToFolderDialog(folders, currentFolderId = currentFolder,
            onDismiss = { moveIds = emptyList() },
            onConfirm = { folder ->
                if (!busy) {
                    val ids = moveIds.toList()
                    moveIds = emptyList()
                    organize {
                        ConversationStore.moveToFolder(context, ids, folder)
                    }
                    selecting = false
                    selectedIds = emptyList()
                }
            }
        )
    }
    renameTarget?.let { target -> RenameDialog(target.title,
        onDismiss = { renameTarget = null }, onConfirm = { title -> renameTarget = null; perform {
            withContext(Dispatchers.IO) {
                ConversationStore.rename(context, target.id, title.take(100))
            }; refresh()
        } }) }
    if (deleteIds.isNotEmpty()) AlertDialog(
        onDismissRequest = { deleteIds = emptyList() },
        title = { Text(stringResource(R.string.batch_delete_title)) },
        text = { Text(stringResource(R.string.batch_delete_text, deleteIds.size)) },
        confirmButton = { TextButton(onClick = {
            val ids = deleteIds; deleteIds = emptyList(); perform {
                try { withContext(Dispatchers.IO) { ids.forEach { ConversationStore.delete(context, it) } } }
                finally { refresh() }
                if (openId in ids) { opened = null; openId = null; screen = "list" }
                selecting = false; selectedIds = emptyList()
            }
        }) { Text(stringResource(R.string.action_confirm_delete)) } },
        dismissButton = { TextButton(onClick = { deleteIds = emptyList() }) { Text(stringResource(R.string.action_cancel)) } }
    )
    restoreUri?.let { uri -> AlertDialog(
        onDismissRequest = { restoreUri = null }, title = { Text(stringResource(R.string.library_restore_title)) },
        text = { Text(stringResource(R.string.library_restore_message)) },
        confirmButton = { TextButton(onClick = { restoreUri = null; perform {
            val count = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { ConversationStore.restoreBackup(context, it) }
                    ?: error("Cannot open backup")
            }; refresh()
            Toast.makeText(context, context.getString(R.string.library_restore_done, count), Toast.LENGTH_LONG).show()
        } }) { Text(stringResource(R.string.library_restore_action)) } },
        dismissButton = { TextButton(onClick = { restoreUri = null }) { Text(stringResource(R.string.action_cancel)) } }
    ) }
    importWork?.let { work ->
        when (work.stage) {
            "duplicate" -> AlertDialog(
                onDismissRequest = { AppState.choose(work.token, ImportChoice.CANCEL) },
                title = { Text(stringResource(R.string.duplicate_dialog_title)) },
                text = { Text(stringResource(R.string.duplicate_dialog_text, work.title.orEmpty())) },
                confirmButton = { TextButton(onClick = { AppState.choose(work.token, ImportChoice.OPEN) }) {
                    Text(stringResource(R.string.duplicate_open_existing)) } },
                dismissButton = { Row {
                    TextButton(onClick = { AppState.choose(work.token, ImportChoice.CANCEL) }) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { AppState.choose(work.token, ImportChoice.SAVE) }) { Text(stringResource(R.string.duplicate_import_anyway)) }
                } }
            )
            "failed" -> AlertDialog(
                onDismissRequest = { AppState.choose(work.token, ImportChoice.CANCEL) },
                title = { Text(stringResource(R.string.import_failed)) },
                text = { Text(stringResource(R.string.library_failure_detail)) },
                confirmButton = { TextButton(onClick = { AppState.choose(work.token, ImportChoice.RETRY) }) { Text(stringResource(R.string.library_retry)) } },
                dismissButton = { TextButton(onClick = { AppState.choose(work.token, ImportChoice.CANCEL) }) { Text(stringResource(R.string.action_cancel)) } }
            )
        }
    }
    if (showStorageIssues) AlertDialog(
        onDismissRequest = { showStorageIssues = false },
        title = { Text(stringResource(R.string.library_storage_issue_title)) },
        text = { Text(stringResource(R.string.library_storage_issue, storageIssues.size)) },
        confirmButton = { TextButton(onClick = { showStorageIssues = false }) { Text(stringResource(R.string.library_close)) } }
    )
    if (showError) AlertDialog(
        onDismissRequest = { showError =false }, title = { Text(stringResource(R.string.library_operation_failed)) },
        text = { Text(stringResource(R.string.library_failure_detail)) },
        confirmButton = { TextButton(onClick = { showError =false }) { Text(stringResource(R.string.library_close)) } }
    )
}
