package com.chatscroll.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatscroll.app.R
import com.chatscroll.app.data.ConversationSummary
import com.chatscroll.app.data.SearchHit
import com.chatscroll.app.ui.theme.Ink
import com.chatscroll.app.ui.theme.InkSoft
import com.chatscroll.app.ui.theme.Terracotta
import com.chatscroll.app.ui.theme.WarmBorder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    conversations: List<ConversationSummary>,
    searchHits: List<SearchHit>?,
    selecting: Boolean,
    selectedIds: Set<String>,
    onOpen: (ConversationSummary) -> Unit,
    onImport: () -> Unit,
    onItemAction: (ConversationSummary) -> Unit,
    onQueryChange: (String) -> Unit,
    onSettings: () -> Unit,
    onToggleSelected: (ConversationSummary) -> Unit,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showFormats by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) {
                        Text(
                            stringResource(R.string.batch_selected, selectedIds.size),
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (searching) {
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                onQueryChange(it)
                            },
                            placeholder = { Text(stringResource(R.string.search_hint)) },
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
                            stringResource(R.string.app_name),
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = onExitSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = onDeleteSelected, enabled = selectedIds.isNotEmpty()) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = if (selectedIds.isEmpty()) InkSoft else Terracotta
                            )
                        }
                    } else if (searching) {
                        IconButton(onClick = {
                            searching = false
                            query = ""
                            onQueryChange("")
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
            if (selecting) return@Scaffold
            FloatingActionButton(
                onClick = { showFormats = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_import))
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        if (searching && query.isNotBlank()) {
            SearchResults(searchHits, onOpen, Modifier.padding(padding))
        } else if (conversations.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationCard(
                        conversation = conversation,
                        selected = conversation.id in selectedIds,
                        selecting = selecting,
                        onClick = {
                            if (selecting) onToggleSelected(conversation) else onOpen(conversation)
                        },
                        onLongClick = { if (!selecting) onItemAction(conversation) }
                    )
                }
            }
        }
            if (!selecting) SmallFloatingActionButton(
                onClick = onSettings,
                containerColor = Color.White,
                contentColor = Terracotta,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
            }
        }
    }

    if (showFormats) {
        ImportFormatsDialog(
            onChooseFile = {
                showFormats = false
                onImport()
            },
            onDismiss = { showFormats = false }
        )
    }
}

/** Matches across every conversation, shown in place of the list while searching. */
@Composable
private fun SearchResults(
    hits: List<SearchHit>?,
    onOpen: (ConversationSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    if (hits == null) return
    if (hits.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.search_empty), color = InkSoft, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(hits, key = { it.summary.id }) { hit ->
            SearchHitCard(hit, onClick = { onOpen(hit.summary) })
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, WarmBorder),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                hit.summary.title,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Ink
            )
            Spacer(Modifier.height(4.dp))
            Text(
                hit.snippet ?: stringResource(R.string.search_title_match),
                fontSize = 13.sp,
                color = InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.app_icon_full),
            contentDescription = null,
            modifier = Modifier.size(112.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.list_empty_title),
            fontFamily = FontFamily.Serif,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.list_empty_subtitle),
            fontSize = 14.sp,
            color = InkSoft,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.list_import_hint),
            fontSize = 13.sp,
            color = InkSoft,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val dateText = remember(conversation.importedAt) {
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(conversation.importedAt),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    val countText = if (conversation.messageCount == 1) {
        stringResource(R.string.message_count_single)
    } else {
        stringResource(R.string.message_count, conversation.messageCount)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, WarmBorder),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = if (selecting) 6.dp else 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
        ) {
            if (selecting) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (selected) Terracotta else InkSoft,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.pinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.action_pin),
                        tint = Terracotta,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    conversation.title,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )
                if (!conversation.seen) {
                    Spacer(Modifier.width(8.dp))
                    NewBadge()
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "$countText · $dateText",
                fontSize = 13.sp,
                color = InkSoft
            )
            }
        }
    }
}

/**
 * Shown before the file picker so the supported apps are visible up front.
 * It only explains; the file is still recognized on its own, so nothing here
 * has to be chosen.
 */
@Composable
private fun ImportFormatsDialog(onChooseFile: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_formats_title), fontFamily = FontFamily.Serif) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.import_formats_intro),
                    fontSize = 14.sp,
                    color = InkSoft
                )
                FormatLine("RikkaHub", stringResource(R.string.import_format_rikkahub_desc))
                FormatLine("Kelivo", stringResource(R.string.import_format_kelivo_desc))
                FormatLine(
                    "Chatbox",
                    stringResource(R.string.import_format_chatbox_desc),
                    stringResource(R.string.import_format_chatbox_note)
                )
                Text(
                    stringResource(R.string.import_format_other_desc),
                    fontSize = 13.sp,
                    color = InkSoft
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onChooseFile) {
                Text(stringResource(R.string.import_choose_file))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun FormatLine(name: String, description: String, note: String? = null) {
    Column {
        Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Ink)
        Text(description, fontSize = 13.sp, color = InkSoft, lineHeight = 18.sp)
        if (note != null) {
            Text(note, fontSize = 12.sp, color = Terracotta, lineHeight = 17.sp)
        }
    }
}

/** Small "new" tag shown until a conversation has been opened once. */
@Composable
private fun NewBadge() {
    Surface(
        color = Terracotta,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            stringResource(R.string.badge_new),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
