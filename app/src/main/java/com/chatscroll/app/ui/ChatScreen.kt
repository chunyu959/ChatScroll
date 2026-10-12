package com.chatscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversation: Conversation,
    rememberPosition: Boolean,
    rememberSeconds: Int,
    onBack: () -> Unit,
    onScroll: (index: Int, offset: Int) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchCursor by rememberSaveable { mutableIntStateOf(0) }

    // Only the visible text is searchable; folded reasoning and tool calls are not.
    val matches = remember(conversation, query) {
        val needle = query.trim()
        if (needle.isEmpty()) emptyList()
        else conversation.messages.mapIndexedNotNull { index, message ->
            if (message.content.contains(needle, ignoreCase = true)) index else null
        }
    }

    // A conversation opened before it was ever read has no saved place, so it
    // starts at the top. Once it has been read, reopen it on the same message and
    // the same distance into that message. Both are known exactly, so nothing has
    // to be guessed from the height of messages that are off screen.
    val savedIndex = conversation.scrollIndex
    val savedOffset = conversation.scrollOffset
    LaunchedEffect(conversation.id) {
        if (savedIndex == null || savedIndex <= 0 && (savedOffset ?: 0) <= 0) return@LaunchedEffect
        listState.scrollToItem(savedIndex, savedOffset ?: 0)
    }

    // Where reading has reached: the first message on screen and how far into it.
    // Written on a timer so it survives the app being swiped away, which gives no
    // chance to save, and written again on leaving so coming straight back shows
    // the exact spot rather than the last tick.
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    var pendingOffset by remember { mutableStateOf(0) }
    if (rememberPosition) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (index, offset) ->
                    pendingIndex = index
                    pendingOffset = offset
                }
        }
        LaunchedEffect(rememberSeconds) {
            while (isActive) {
                delay(rememberSeconds * 1000L)
                pendingIndex?.let { onScroll(it, pendingOffset) }
            }
        }
    }
    DisposableEffect(conversation.id) {
        onDispose {
            if (rememberPosition) pendingIndex?.let { onScroll(it, pendingOffset) }
        }
    }

    val awayFromTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    BackHandler(enabled = !searching) {
        if (rememberPosition) pendingIndex?.let { onScroll(it, pendingOffset) }
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                matchCursor = 0
                            },
                            placeholder = { Text(stringResource(R.string.search_hint_chat)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            conversation.title,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (!searching) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    if (searching) {
                        if (matches.isNotEmpty()) {
                            Text(
                                stringResource(R.string.search_count, matchCursor + 1, matches.size),
                                color = InkSoft,
                                fontSize = 13.sp
                            )
                            IconButton(onClick = {
                                matchCursor = (matchCursor - 1 + matches.size) % matches.size
                            }) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = Ink)
                            }
                            IconButton(onClick = {
                                matchCursor = (matchCursor + 1) % matches.size
                            }) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Ink)
                            }
                        }
                        IconButton(onClick = {
                            searching = false
                            query = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            if (awayFromTop && !searching) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = Color.White,
                    contentColor = Terracotta
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_scroll_top))
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        // Jump to the current match whenever it changes.
        LaunchedEffect(matches, matchCursor) {
            matches.getOrNull(matchCursor)?.let { listState.animateScrollToItem(it) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            itemsIndexed(
                conversation.messages,
                key = { index, _ -> index },
                // Keeps only the rows near the viewport composed. Without it every
                // message, images included, stays laid out at once and a long
                // conversation with embedded screenshots runs out of memory.
                contentType = { _, _ -> "message" }
            ) { _, message ->
                if (message.role == "user") {
                    UserMessage(message)
                } else {
                    AssistantMessage(message)
                }
            }
        }
    }
}

/** User message: right-aligned light apricot bubble, rendered as Markdown. */
@Composable
private fun UserMessage(message: ChatMessage) {
    val bubbleMaxWidth = LocalConfiguration.current.screenWidthDp.dp * 0.84f
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        SpeakerLine(message, Alignment.End)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = UserBubble,
                shape = RoundedCornerShape(
                    topStart = 20.dp, topEnd = 20.dp,
                    bottomStart = 20.dp, bottomEnd = 4.dp
                ),
                modifier = Modifier.widthIn(max = bubbleMaxWidth)
            ) {
                MessageBody(message, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/**
 * Assistant message, shown in the order it was written: a stretch of the reply,
 * then a tool call or a thought, then the next stretch. Conversations imported
 * before that order was kept have no "text" parts, so their reply still follows
 * the folded blocks as one piece.
 */
@Composable
private fun AssistantMessage(message: ChatMessage) {
    val interleaved = message.blocks.any { it.kind == "text" }
    Column(Modifier.fillMaxWidth()) {
        SpeakerLine(message, Alignment.Start)
        message.blocks.forEach { block ->
            when (block.kind) {
                "tool" -> ToolBlock(block.name, block.text)
                "text" -> if (block.text.isNotBlank()) MessageBody(message.copy(content = block.text))
                else -> ThinkingBlock(block.text)
            }
            Spacer(Modifier.height(8.dp))
        }
        // Conversations imported before blocks existed keep their reasoning here.
        if (message.blocks.isEmpty() && !message.thinking.isNullOrBlank()) {
            ThinkingBlock(message.thinking)
            Spacer(Modifier.height(8.dp))
        }
        if (!interleaved && (message.content.isNotBlank() || message.images.isNotEmpty())) {
            MessageBody(message)
        }
    }
}

/**
 * The name and time of a message, shown above its bubble and aligned with it.
 * Exports that record neither say nothing here, so their layout is unchanged.
 */
@Composable
private fun SpeakerLine(message: ChatMessage, alignment: Alignment.Horizontal) {
    val name = message.speaker
    val time = message.time
    if (name.isNullOrBlank() && time.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = if (alignment == Alignment.End) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!name.isNullOrBlank()) {
            Text(name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (!name.isNullOrBlank() && !time.isNullOrBlank()) Spacer(Modifier.width(8.dp))
        if (!time.isNullOrBlank()) {
            Text(time, color = InkSoft, fontSize = 12.sp)
        }
    }
}

/**
 * Renders a message's text with its images put back where they belong.
 * The stored text only carries a short `[image:N]` marker, so the huge base64
 * payload is never part of what gets laid out.
 */
@Composable
private fun MessageBody(message: ChatMessage, modifier: Modifier = Modifier) {
    if (message.images.isEmpty()) {
        MarkdownText(message.content, modifier)
        return
    }
    Column(modifier) {
        message.content.split(imageMarker).forEachIndexed { index, part ->
            if (part.isNotBlank()) {
                MarkdownText(part.trim())
            }
            message.images.getOrNull(index)?.let { EmbeddedImage(it) }
        }
    }
}

/** Shows one embedded image, decoded down to reading size off the main thread. */
@Composable
private fun EmbeddedImage(dataUri: String) {
    val bitmap = produceState<android.graphics.Bitmap?>(
        initialValue = null,
        dataUri
    ) {
        value = withContext(Dispatchers.Default) {
            runCatching { decodeDataUriBitmap(dataUri) }.getOrNull()
        }
    }.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    }
}

/** Collapsible tool-call block, collapsed by default. The header names the tool. */
@Composable
private fun ToolBlock(name: String?, detail: String) {
    val label = if (name.isNullOrBlank()) {
        stringResource(R.string.tool_label_generic)
    } else {
        stringResource(R.string.tool_label, name)
    }
    CollapsibleBlock(label = label, mark = { "⚙" }, body = detail, renderMarkdown = true)
}

/**
 * Collapsible chain-of-thought block, collapsed by default.
 * The mark itself is the toggle: a right-pointing chevron while folded, turning
 * downward once open. Drawn as text rather than the sparkle glyph, which reads
 * as another product's signature.
 */
@Composable
private fun ThinkingBlock(thinking: String) {
    CollapsibleBlock(
        label = stringResource(R.string.thinking_label),
        mark = { expanded -> if (expanded) "⌄" else "›" },
        body = thinking,
        renderMarkdown = false
    )
}

/** Shared collapsed-by-default block used for both reasoning and tool calls. */
@Composable
private fun CollapsibleBlock(
    label: String,
    mark: @Composable (expanded: Boolean) -> String,
    body: String,
    renderMarkdown: Boolean
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = Color(0xFFF6F1E7),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp)
            ) {
                Text(mark(expanded), color = Terracotta, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    color = InkSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
            }
            AnimatedVisibility(visible = expanded) {
                if (body.isBlank()) {
                    Spacer(Modifier.height(0.dp))
                } else if (renderMarkdown) {
                    MarkdownText(body, Modifier.padding(top = 8.dp))
                } else {
                    Text(
                        body,
                        color = InkSoft,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
