package com.chatscroll.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chatscroll.app.R

/** MarkdownText keeps its native selectable text, renderer and initial-measure lifecycle. */
@Composable
internal fun ReaderMarkdownContent(markdown: String, searchQuery: String) {
    val sections = remember(markdown) { readerMarkdownSections(markdown) }
    Column(Modifier.fillMaxWidth()) {
        sections.forEach { section ->
            if (section.tableColumns > 0) ReaderScrollableTable(section, searchQuery)
            else if (section.markdown.isNotBlank()) MarkdownText(section.markdown, searchQuery = searchQuery)
        }
    }
}

@Composable
private fun ReaderScrollableTable(section: ReaderMarkdownSection, searchQuery: String) {
    var fullscreen by remember(section.markdown) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.reader2_table_swipe), Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { fullscreen = true }) { Text(stringResource(R.string.reader2_table_open)) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = readerTableWidthDp(section.tableColumns, maxWidth.value).dp
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                MarkdownText(section.markdown, Modifier.width(width), searchQuery = searchQuery)
            }
        }
    }
    if (fullscreen) ReaderTableDialog(section, searchQuery) { fullscreen = false }
}

@Composable
private fun ReaderTableDialog(section: ReaderMarkdownSection, searchQuery: String, onDismiss: () -> Unit) {
    // Local dialog state only: closing restores the reader's existing position and theme.
    ReaderMotionDialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { dismiss ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.reader2_table_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.reader2_table_pan), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ReaderMotionIconButton(onClick = dismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.reader_close))
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val width = readerTableWidthDp(section.tableColumns, (maxWidth - 32.dp).value).dp
                    val sections = remember(section.markdown) { readerMarkdownBlocks(section.markdown) }
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        // Vertical scrolling remains bounded by the dialog. Context-sensitive
                        // documents stay in one item; safe paragraphs can be measured lazily.
                        LazyColumn(Modifier.width(width + 32.dp).fillMaxSize(),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(sections) { _, markdown ->
                                MarkdownText(markdown, Modifier.fillMaxWidth(), searchQuery = searchQuery)
                            }
                        }
                    }
                }
            }
        }
    }
}
