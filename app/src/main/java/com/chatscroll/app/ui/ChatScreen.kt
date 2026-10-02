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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.chatscroll.app.ui.theme.InkSoft
import com.chatscroll.app.ui.theme.Terracotta
import com.chatscroll.app.ui.theme.UserBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conversation: Conversation, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        conversation.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
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

/** Assistant message: collapsible thinking (if any) + rendered Markdown. */
@Composable
private fun AssistantMessage(message: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        message.blocks.forEach { block ->
            when (block.kind) {
                "tool" -> ToolBlock(block.name, block.text)
                else -> ThinkingBlock(block.text)
            }
            Spacer(Modifier.height(8.dp))
        }
        // Conversations imported before blocks existed keep their reasoning here.
        if (message.blocks.isEmpty() && !message.thinking.isNullOrBlank()) {
            ThinkingBlock(message.thinking)
            Spacer(Modifier.height(8.dp))
        }
        if (message.content.isNotBlank() || message.images.isNotEmpty()) {
            MessageBody(message)
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
