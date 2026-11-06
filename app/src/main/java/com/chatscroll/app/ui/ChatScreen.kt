package com.chatscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatscroll.app.R
import com.chatscroll.app.data.ChatMessage
import com.chatscroll.app.data.Conversation
import com.chatscroll.app.ui.theme.Ink
import com.chatscroll.app.ui.theme.InkSoft
import com.chatscroll.app.ui.theme.Terracotta
import com.chatscroll.app.ui.theme.UserBubble
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    conversation: Conversation,
    rememberPosition: Boolean,
    rememberSeconds: Int,
    onBack: () -> Unit,
    onScroll: (index: Int, offset: Int, blockIndex: Int) -> Unit,
    initialMessageIndex: Int? = null,
    initialQuery: String = "",
    onBookmark: (Int) -> Unit = {},
    onViewSource: () -> Unit = {},
    onReparse: () -> Unit = {},
    pureReading: Boolean = false,
    onRestorePrevious: () -> Unit = {}
) {
    // Every effect and callback belongs to this conversation, including disposal.
    key(conversation.id) {
        ConversationReader(conversation, rememberPosition, rememberSeconds, onBack, onScroll,
            initialMessageIndex, initialQuery, onBookmark, onViewSource, onReparse, pureReading, onRestorePrevious)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationReader(
    conversation: Conversation,
    rememberPosition: Boolean,
    rememberSeconds: Int,
    onBack: () -> Unit,
    onScroll: (Int, Int, Int) -> Unit,
    initialMessageIndex: Int?,
    initialQuery: String,
    onBookmark: (Int) -> Unit,
    onViewSource: () -> Unit,
    onReparse: () -> Unit,
    pureReading: Boolean,
    onRestorePrevious: () -> Unit
) {
    val allRows = remember(conversation.messages) { readerRows(conversation.messages) }
    val initialPosition = remember {
        when {
            initialMessageIndex != null -> ReaderPosition(initialMessageIndex, 0, 0)
            rememberPosition -> ReaderPosition(conversation.scrollIndex ?: 0,
                conversation.scrollOffset ?: 0, conversation.scrollBlock)
            else -> ReaderPosition(0, 0, 0)
        }
    }
    var expanded by remember {
        val target = allRows.getOrNull(readerRowIndex(allRows, initialPosition.messageIndex, initialPosition.blockIndex))
        mutableStateOf(setOfNotNull(target?.parentFold))
    }
    val rows = remember(allRows, expanded, pureReading) { readerVisibleRows(allRows, expanded, pureReading) }
    // Avoid automatic saved-list-state restoration when remembering is disabled.
    val listState = remember {
        LazyListState(readerRowIndex(rows, initialPosition.messageIndex, initialPosition.blockIndex),
            initialPosition.offset.coerceAtLeast(0))
    }
    val currentRows by rememberUpdatedState(rows)
    val saveEnabled by rememberUpdatedState(rememberPosition)
    val ownerScroll = remember { onScroll }
    fun savePosition() {
        if (!saveEnabled) return
        // The measured key remains authoritative during a fold/layout transition;
        // a new row list can briefly have different indices than the last layout.
        val measured = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == listState.firstVisibleItemIndex }
            ?.key?.toString()?.split(':')
        val measuredMessage = measured?.getOrNull(0)?.toIntOrNull()
        val measuredBlock = measured?.getOrNull(1)?.toIntOrNull()
        val position = if (measuredMessage != null && measuredBlock != null) {
            ReaderPosition(measuredMessage, listState.firstVisibleItemScrollOffset, measuredBlock)
        } else readerPosition(currentRows, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        position?.let { ownerScroll(it.messageIndex, it.offset, it.blockIndex) }
    }
    LaunchedEffect(rememberPosition, rememberSeconds) {
        if (rememberPosition) {
            while (isActive) {
                delay(rememberSeconds.coerceAtLeast(1) * 1000L)
                savePosition()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { savePosition() } }

    val scope = rememberCoroutineScope()
    // One animation per explicitly opened fold survives lazy-row recycling.
    val foldReveals = remember { mutableStateMapOf<ReaderBlockKey, Animatable<Float, AnimationVector1D>>() }
    fun toggleFold(key: ReaderBlockKey) {
        if (key in expanded) {
            expanded = expanded - key
            foldReveals.remove(key)
        } else {
            val reveal = Animatable(0f)
            foldReveals[key] = reveal
            expanded = expanded + key
            scope.launch { reveal.animateTo(1f, tween(ReaderMotion.Overlay, easing = ReaderMotion.Easing)) }
        }
    }
    var searching by rememberSaveable { mutableStateOf(initialQuery.isNotBlank()) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val matches = remember(allRows, query) { readerSearchMatches(allRows, query) }
    var matchCursor by rememberSaveable {
        mutableIntStateOf(matches.indexOfFirst { it.messageIndex == initialMessageIndex }.coerceAtLeast(0))
    }
    var firstSearch by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var directory by rememberSaveable { mutableStateOf(false) }
    var onlyBookmarks by rememberSaveable { mutableStateOf(false) }
    var imageSource by remember { mutableStateOf<String?>(null) }
    var messageActions by remember { mutableStateOf<ReaderBlockKey?>(null) }
    var codeMessageIndex by remember { mutableStateOf<Int?>(null) }
    var codeBlocks by remember { mutableStateOf<List<ReaderCodeBlock>>(emptyList()) }
    val clipboard = LocalClipboardManager.current
    val currentMessage by remember {
        derivedStateOf { currentRows.getOrNull(listState.firstVisibleItemIndex)?.key?.messageIndex ?: 0 }
    }
    val awayFromTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val canGoBottom by remember { derivedStateOf { listState.canScrollForward } }

    LaunchedEffect(query, matchCursor) {
        val match = matches.getOrNull(matchCursor.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
        val keepInitialTarget = firstSearch && initialMessageIndex != null && match?.messageIndex != initialMessageIndex
        firstSearch = false
        if (match != null && !keepInitialTarget) {
            listState.scrollToItem(readerRowIndex(currentRows, match.messageIndex, match.blockIndex))
        }
    }
    fun goBack() { savePosition(); onBack() }
    BackHandler(enabled = searching) { searching = false }
    BackHandler(enabled = !searching) { goBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Crossfade(searching, animationSpec = tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing),
                            label = "readerSearch") { showSearch ->
                        if (showSearch) {
                            TextField(value = query, onValueChange = { query = it; matchCursor = 0 },
                                placeholder = { Text(stringResource(R.string.search_hint_chat), maxLines = 1) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent))
                        } else {
                            Text(conversation.title, style = MaterialTheme.typography.titleLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        }
                    },
                    navigationIcon = {
                        ReaderMotionIconButton(onClick = { if (searching) searching = false else goBack() }) {
                            Icon(if (searching) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(if (searching) R.string.reader_close_search else R.string.action_back))
                        }
                    },
                    actions = {
                        if (!searching) {
                            ReaderMotionIconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.action_search))
                            }
                        }
                        Box {
                            ReaderMotionIconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.reader_actions))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_directory)) }, onClick = {
                                    menu = false; onlyBookmarks = false; directory = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_bookmarks)) }, onClick = {
                                    menu = false; onlyBookmarks = true; directory = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_expand_all)) }, onClick = {
                                    menu = false; foldReveals.clear()
                                    expanded = allRows.filter { it.kind == ReaderRowKind.FOLD }.map { it.key }.toSet()
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_collapse_all)) }, onClick = {
                                    menu = false; foldReveals.clear(); expanded = emptySet()
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_scroll_top)) }, onClick = {
                                    menu = false; scope.launch { listState.scrollToItem(0) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_scroll_bottom)) }, onClick = {
                                    menu = false; scope.launch { scrollReaderBottom(listState, currentRows.size) }
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_view_source)) },
                                    onClick = { menu = false; onViewSource() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader_reparse)) },
                                    onClick = { menu = false; onReparse() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.reader2_restore_previous)) },
                                    onClick = { menu = false; onRestorePrevious() })
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                AnimatedVisibility(searching,
                    enter = fadeIn(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)),
                    exit = fadeOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing))) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (matches.isEmpty()) stringResource(R.string.search_empty)
                            else stringResource(R.string.search_count, matchCursor.coerceAtMost(matches.lastIndex) + 1, matches.size),
                            modifier = Modifier.weight(1f), color = InkSoft, style = MaterialTheme.typography.labelLarge)
                        ReaderMotionIconButton(enabled = matches.isNotEmpty(), onClick = {
                            matchCursor = (matchCursor - 1 + matches.size) % matches.size
                        }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.reader_previous_match)) }
                        ReaderMotionIconButton(enabled = matches.isNotEmpty(), onClick = {
                            matchCursor = (matchCursor + 1) % matches.size
                        }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.reader_next_match)) }
                        TextButton(onClick = { query = ""; matchCursor = 0 }) { Text(stringResource(R.string.reader_clear_search)) }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!searching) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedVisibility(awayFromTop,
                        enter = fadeIn(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)),
                        exit = fadeOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing))) {
                        SmallFloatingActionButton(onClick = { scope.launch { listState.scrollToItem(0) } },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = InkSoft) {
                            Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.action_scroll_top))
                        }
                    }
                    AnimatedVisibility(canGoBottom,
                        enter = fadeIn(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)),
                        exit = fadeOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing))) {
                        SmallFloatingActionButton(onClick = {
                            scope.launch { scrollReaderBottom(listState, currentRows.size) }
                        }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = InkSoft) {
                            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.reader_scroll_bottom))
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { "${it.key.messageIndex}:${it.key.blockIndex}" }, contentType = { it.kind }) { row ->
                val message = conversation.messages[row.key.messageIndex]
                val role = stringResource(if (message.role == "user") R.string.reader_user else R.string.reader_assistant)
                val identity = message.speaker?.takeIf { it.isNotBlank() }?.let { "$role · $it" } ?: role
                val actionLabel = stringResource(R.string.reader_message_actions, row.key.messageIndex + 1)
                val bookmarked = row.key.messageIndex in conversation.bookmarks
                Box(Modifier.fillMaxWidth()) {
                    when (row.kind) {
                        ReaderRowKind.MESSAGE, ReaderRowKind.MESSAGE_ANCHOR -> ReaderMessageHeader(
                            message, row.key.messageIndex, bookmarked,
                            pureReading = row.kind == ReaderRowKind.MESSAGE_ANCHOR, identity = identity,
                            onBookmark = { onBookmark(row.key.messageIndex) },
                            onActions = { messageActions = row.key })
                        ReaderRowKind.FOLD -> ReaderFoldHeader(row, row.key in expanded,
                            onActions = { messageActions = row.key }) { toggleFold(row.key) }
                        else -> {
                            val reveal = row.parentFold?.let { foldReveals[it] }
                            Box(Modifier.graphicsLayer { alpha = reveal?.value ?: 1f }) {
                                ReaderContentRow(row, message.role == "user", query, identity, actionLabel,
                                    onActions = { messageActions = row.key }, onImage = { imageSource = it })
                            }
                        }
                    }
                    ReaderMessageMenu(expanded = messageActions == row.key, bookmarked = bookmarked,
                        onDismiss = { messageActions = null },
                        onCopy = { messageActions = null; clipboard.setText(AnnotatedString(readerVisibleMessageText(message))) },
                        onCopyAll = { messageActions = null; clipboard.setText(AnnotatedString(readerMessageText(message))) },
                        onCopyCode = {
                            messageActions = null
                            codeBlocks = readerCodeBlocks(message)
                            codeMessageIndex = row.key.messageIndex
                        },
                        onBookmark = { messageActions = null; onBookmark(row.key.messageIndex) })
                }
            }
        }
    }

    if (directory) ReaderDirectoryDialog(conversation, onlyBookmarks, currentMessage,
        onFilter = { onlyBookmarks = it }, onDismiss = { directory = false },
        onBookmark = onBookmark, onJump = { messageIndex ->
            directory = false
            scope.launch { listState.scrollToItem(readerRowIndex(currentRows, messageIndex)) }
        })
    if (codeMessageIndex != null) ReaderCodeDialog(codeBlocks, onDismiss = {
        codeMessageIndex = null; codeBlocks = emptyList()
    }, onCopy = { code ->
        clipboard.setText(AnnotatedString(code)); codeMessageIndex = null; codeBlocks = emptyList()
    })
    imageSource?.let { source -> ReaderImageViewer(source) { imageSource = null } }
}

private suspend fun scrollReaderBottom(state: LazyListState, rowCount: Int) {
    if (rowCount == 0) return
    state.scrollToItem(rowCount - 1)
    val last = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == rowCount - 1 }
    if (last != null) {
        val remaining = last.offset + last.size + state.layoutInfo.afterContentPadding - state.layoutInfo.viewportEndOffset
        if (remaining > 0) state.scrollBy(remaining.toFloat())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderMessageHeader(
    message: ChatMessage, index: Int, bookmarked: Boolean, pureReading: Boolean, identity: String,
    onBookmark: () -> Unit, onActions: () -> Unit
) {
    val actions = stringResource(R.string.reader_message_actions, index + 1)
    if (pureReading) {
        // Keep a small, discoverable fallback because selectable native text owns
        // long presses on words. The numbered toolbar and timestamp are hidden.
        Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(identity, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bookmarked) Icon(Icons.Default.Bookmark, stringResource(R.string.reader2_bookmarked),
                Modifier.size(14.dp), tint = Terracotta)
            Box(Modifier.size(48.dp).combinedClickable(role = Role.Button, onClickLabel = actions,
                onLongClickLabel = actions, onClick = onActions, onLongClick = onActions),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MoreVert, actions, Modifier.size(16.dp), tint = InkSoft)
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 16.dp)
        .combinedClickable(onClick = onActions, onLongClickLabel = actions, onLongClick = onActions),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.reader_message_heading, index + 1, identity),
                color = InkSoft, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!message.time.isNullOrBlank()) Text(message.time, color = InkSoft,
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val bookmarkAction = stringResource(if (bookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark)
        ReaderMotionIconButton(onClick = onBookmark, modifier = Modifier.semantics {
            selected = bookmarked; contentDescription = bookmarkAction
        }) {
            Crossfade(bookmarked, animationSpec = tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing),
                label = "messageBookmark") { marked ->
                Icon(if (marked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = null, tint = if (marked) Terracotta else InkSoft)
            }
        }
        ReaderMotionIconButton(onClick = onActions) { Icon(Icons.Default.MoreVert, actions, tint = InkSoft) }
    }
}

@Composable
private fun ReaderMessageMenu(
    expanded: Boolean, bookmarked: Boolean, onDismiss: () -> Unit,
    onCopy: () -> Unit, onCopyAll: () -> Unit, onCopyCode: () -> Unit, onBookmark: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.reader2_copy_body)) }, onClick = onCopy)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader2_copy_all)) }, onClick = onCopyAll)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_copy_code)) }, onClick = onCopyCode)
        DropdownMenuItem(text = { Text(stringResource(if (bookmarked) R.string.reader_remove_bookmark
            else R.string.reader_add_bookmark)) }, onClick = onBookmark)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderFoldHeader(row: ReaderRow, expanded: Boolean, onActions: () -> Unit, onToggle: () -> Unit) {
    val label = if (row.foldKind == "thinking") stringResource(R.string.thinking_label)
        else if (row.foldName.isNullOrBlank()) stringResource(R.string.tool_label_generic)
        else stringResource(R.string.tool_label, row.foldName)
    val state = stringResource(if (expanded) R.string.reader_expanded else R.string.reader_collapsed)
    val actions = stringResource(R.string.reader_message_actions, row.key.messageIndex + 1)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        animationSpec = tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing), label = "foldArrow")
    val background by animateColorAsState(
        if (expanded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing), label = "foldBackground")
    Surface(color = background, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().semantics { stateDescription = state }
            .combinedClickable(role = Role.Button, onClick = onToggle, onLongClickLabel = actions,
                onLongClick = onActions).heightIn(min = 48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.KeyboardArrowDown, modifier = Modifier.graphicsLayer { rotationZ = rotation },
                contentDescription = null, tint = Terracotta)
            Text(label, modifier = Modifier.padding(start = 8.dp), color = InkSoft,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ReaderContentRow(
    row: ReaderRow, user: Boolean, query: String, identity: String, actionLabel: String,
    onActions: () -> Unit, onImage: (String) -> Unit
) {
    val folded = row.parentFold != null
    val currentActions by rememberUpdatedState(onActions)
    Box(Modifier.fillMaxWidth().semantics {
        contentDescription = identity
        onLongClick(label = actionLabel) { currentActions(); true }
    }.pointerInput(row.key) {
        // Default-pass gestures yield to consumed native TextView selection/link
        // events. The per-message action target remains available in either mode.
        detectTapGestures(onLongPress = { currentActions() })
    }, contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        val rowModifier = if (user) Modifier.fillMaxWidth(0.9f) else Modifier.fillMaxWidth()
        Surface(modifier = rowModifier, shape = RoundedCornerShape(if (user) 16.dp else 8.dp),
            color = when { folded -> MaterialTheme.colorScheme.surfaceContainerLow; user -> UserBubble; else -> Color.Transparent }) {
            val padding = if (user || folded) Modifier.padding(horizontal = 12.dp, vertical = 8.dp) else Modifier
            Box(padding) {
                if (row.kind == ReaderRowKind.IMAGE) {
                    row.imageSource?.let { source -> ReaderEmbeddedImage(source) { onImage(source) } }
                } else if (row.foldKind == "thinking") {
                    val preferences = LocalReaderPreferences.current
                    Text(row.text, color = InkSoft, fontSize = preferences.fontSizeSp.sp,
                        lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp)
                } else {
                    // Images kept inside tables/reference contexts still get an
                    // explicit viewer action without breaking the Markdown block.
                    val inlineImages = remember(row.text) {
                        readerImageSegments(row.text, emptyList()).filterIsInstance<ImageMarkerSegment.Image>()
                            .map { it.dataUri }.distinct()
                    }
                    Column {
                        ReaderMarkdownContent(row.text, searchQuery = query.trim())
                        inlineImages.forEachIndexed { index, source ->
                            TextButton(onClick = { onImage(source) }) {
                                Text(stringResource(R.string.reader_view_image_number, index + 1))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderDirectoryDialog(
    conversation: Conversation, onlyBookmarks: Boolean, currentMessage: Int,
    onFilter: (Boolean) -> Unit, onDismiss: () -> Unit,
    onBookmark: (Int) -> Unit, onJump: (Int) -> Unit
) {
    val indices = remember(conversation.messages.size, conversation.bookmarks, onlyBookmarks) {
        readerDirectoryIndices(conversation.messages.size, conversation.bookmarks, onlyBookmarks)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reader_directory)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyBookmarks, onClick = { onFilter(false) },
                        label = { Text(stringResource(R.string.reader_all_messages)) })
                    FilterChip(selected = onlyBookmarks, onClick = { onFilter(true) },
                        label = { Text(stringResource(R.string.reader_bookmarks)) })
                }
                if (indices.isEmpty()) Text(stringResource(R.string.reader_no_bookmarks), Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(indices, key = { it }) { index ->
                        val message = conversation.messages[index]
                        val selected = currentMessage == index
                        val bookmarked = index in conversation.bookmarks
                        Row(Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                RoundedCornerShape(8.dp))
                            .semantics { this.selected = selected }
                            .clickable(role = Role.Button) { onJump(index) }
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                val role = stringResource(if (message.role == "user") R.string.reader_user else R.string.reader_assistant)
                                Text(stringResource(R.string.reader_message_heading, index + 1,
                                    message.speaker?.takeIf { it.isNotBlank() } ?: role),
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(message.content.take(180).replace('\n', ' '), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            ReaderMotionIconButton(onClick = { onBookmark(index) }, modifier = Modifier.semantics { this.selected = bookmarked }) {
                                Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    stringResource(if (bookmarked) R.string.reader_remove_bookmark else R.string.reader_add_bookmark))
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_close)) } })
}

@Composable
private fun ReaderCodeDialog(blocks: List<ReaderCodeBlock>, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reader_copy_code)) },
        text = {
            if (blocks.isEmpty()) Text(stringResource(R.string.reader_no_code))
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(blocks.indices.toList()) { index ->
                    val block = blocks[index]
                    val label = stringResource(R.string.reader_code_heading, index + 1,
                        block.language.ifBlank { stringResource(R.string.reader_code_plain) })
                    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = label) { onCopy(block.code) }
                        .padding(vertical = 12.dp)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(block.code.take(160), fontFamily = FontFamily.Monospace, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_close)) } })
}
