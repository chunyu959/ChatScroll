package com.chatscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatscroll.app.R
import com.chatscroll.app.data.Conversation
import com.chatscroll.app.ui.theme.Ink
import com.chatscroll.app.ui.theme.UserBubble
import com.chatscroll.app.ui.theme.UserBubbleText

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
                key = { index, _ -> index }
            ) { _, message ->
                if (message.role == "user") {
                    UserMessage(message.content)
                } else {
                    AssistantMessage(message.content)
                }
            }
        }
    }
}

/** User message: right-aligned light apricot bubble. */
@Composable
private fun UserMessage(content: String) {
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
            Text(
                text = content,
                color = UserBubbleText,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/** Assistant message: plain text on the background, Claude web style. */
@Composable
private fun AssistantMessage(content: String) {
    MarkdownText(content, Modifier.fillMaxWidth())
}
