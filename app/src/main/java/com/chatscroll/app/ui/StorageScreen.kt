package com.chatscroll.app.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chatscroll.app.R
import com.chatscroll.app.data.CleanupPreview
import com.chatscroll.app.data.HistoryPreview
import com.chatscroll.app.data.StorageUsage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageScreen(
    usage: StorageUsage?, preview: CleanupPreview?, busy: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onClean: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    var confirmation by remember(preview?.token) { mutableStateOf(false) }
    val context = LocalContext.current
    fun bytes(value: Long) = Formatter.formatFileSize(context, value)
    Scaffold(topBar = { TopAppBar(
        title = { Text(stringResource(R.string.polish_storage_title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
    ) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.polish_storage_intro))
            if (usage == null) {
                CircularProgressIndicator()
            } else {
                val values = listOf(
                    R.string.polish_storage_current to usage.currentBytes,
                    R.string.polish_storage_source to usage.sourceBytes,
                    R.string.polish_storage_images to usage.assetsBytes,
                    R.string.polish_storage_history to usage.historyBytes,
                    R.string.polish_storage_indexes to usage.indexBytes,
                    R.string.polish_storage_other to usage.otherBytes,
                    R.string.polish_storage_total to usage.totalBytes
                )
                values.forEach { (label, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(label), Modifier.weight(1f)); Text(bytes(value))
                } }
            }
            HorizontalDivider()
            Text(stringResource(R.string.polish_cleanup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.polish_cleanup_safety))
            if (preview != null) {
                Text(stringResource(R.string.polish_cleanup_preview, preview.revisionCount, preview.fileCount, bytes(preview.bytes)))
                Text(stringResource(R.string.polish_cleanup_protected, preview.protectedRevisionCount), style = MaterialTheme.typography.bodySmall)
                if (preview.skippedRecordCount > 0 || preview.unknownFileCount > 0 || preview.warnings.isNotEmpty()) {
                    Text(stringResource(R.string.polish_cleanup_skipped, preview.skippedRecordCount, preview.unknownFileCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { confirmation = true }, enabled = !busy && preview.fileCount > 0,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.polish_cleanup_action)) }
                if (preview.fileCount == 0) Text(stringResource(R.string.polish_cleanup_none))
            }
            OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.polish_storage_refresh))
            }
        }
    }
    if (confirmation && preview != null) AlertDialog(
        onDismissRequest = { confirmation = false },
        title = { Text(stringResource(R.string.polish_cleanup_title)) },
        text = { Text(stringResource(R.string.polish_cleanup_confirm, preview.revisionCount, bytes(preview.bytes))) },
        confirmButton = { TextButton(onClick = { confirmation = false; onClean(preview.token) }) {
            Text(stringResource(R.string.polish_cleanup_action))
        } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
internal fun HistoryRestoreDialog(preview: HistoryPreview, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.polish_history_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(preview.title)
            Text(stringResource(R.string.polish_history_preview, preview.messageCount, preview.parserVersion, preview.sourceFormat))
            Text(stringResource(R.string.polish_history_notice))
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.polish_history_restore)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
