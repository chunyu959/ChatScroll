package com.chatscroll.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chatscroll.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceScreen(source: String, onBack: () -> Unit, onExport: () -> Unit) {
    BackHandler(onBack = onBack)
    // A source image may span hundreds of thousands of characters on one line.
    // The preview is bounded; exporting always writes the exact complete source.
    val lines = remember(source) { source.lineSequence().map { if (it.length > 2000) it.take(2000) + " …" else it }.toList() }
    Scaffold(topBar = { TopAppBar(
        title = { Text(stringResource(R.string.library_source_title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
        actions = { IconButton(onClick = onExport) { Icon(Icons.Filled.SaveAlt, stringResource(R.string.library_export_source)) } }
    ) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { Text(stringResource(R.string.library_source_preview_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
            itemsIndexed(lines) { index, text ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${index + 1}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(42.dp))
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
internal fun FormatDialog(reparse: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf("auto") }
    val choices = listOf("auto" to R.string.library_format_auto, "rikkahub" to R.string.library_format_rikka,
        "chatbox" to R.string.library_format_chatbox, "kelivo" to R.string.library_format_kelivo, "markdown" to R.string.library_format_markdown)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (reparse) R.string.library_reparse_title else R.string.library_format_title)) },
        text = { Column {
            Text(stringResource(if (reparse) R.string.library_reparse_note else R.string.library_format_note))
            choices.forEach { (key, label) -> Row(
                Modifier.fillMaxWidth().clickable { selected = key }.padding(vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) { RadioButton(selected == key, onClick = { selected = key }); Text(stringResource(label)) } }
        } },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(if (reparse) R.string.library_reparse_action else R.string.import_choose_file)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
