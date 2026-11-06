package com.chatscroll.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chatscroll.app.R
import com.chatscroll.app.data.ConversationFolder

/** A null destination moves the selected conversations back to Unclassified. */
@Composable
fun MoveToFolderDialog(
    folders: List<ConversationFolder>,
    currentFolderId: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    val folderIds = remember(folders) { folders.mapTo(hashSetOf()) { it.id } }
    var selectedFolderId by remember(currentFolderId) {
        mutableStateOf(currentFolderId?.takeIf { it in folderIds })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(stringResource(R.string.organization_move), style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "description") {
                    Text(
                        stringResource(R.string.organization_move_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                item(key = "unfiled") {
                    FolderDestination(
                        name = stringResource(R.string.organization_unfiled),
                        selected = selectedFolderId == null,
                        onClick = { selectedFolderId = null }
                    )
                }
                items(folders, key = { "folder:${it.id}" }) { folder ->
                    FolderDestination(
                        name = folder.name,
                        selected = selectedFolderId == folder.id,
                        onClick = { selectedFolderId = folder.id }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedFolderId) },
                enabled = selectedFolderId == null || selectedFolderId in folderIds,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(stringResource(R.string.organization_move_confirm), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

@Composable
private fun FolderDestination(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun FolderManagerDialog(
    folders: List<ConversationFolder>,
    onDismiss: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ConversationFolder?>(null) }
    var deleting by remember { mutableStateOf<ConversationFolder?>(null) }
    val renameTarget = renaming
    val deleteTarget = deleting

    when {
        creating -> FolderNameDialog(
            currentName = "",
            creating = true,
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                onCreateFolder(name)
            }
        )
        renameTarget != null -> FolderNameDialog(
            currentName = renameTarget.name,
            creating = false,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRenameFolder(renameTarget.id, name)
            }
        )
        deleteTarget != null -> AlertDialog(
            onDismissRequest = { deleting = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    stringResource(R.string.organization_delete_folder_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    stringResource(R.string.organization_delete_folder_message, deleteTarget.name),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDeleteFolder(deleteTarget.id)
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.action_confirm_delete),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(stringResource(R.string.organization_manage_folders), style = MaterialTheme.typography.headlineSmall)
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "create") {
                        TextButton(
                            onClick = { creating = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(
                                stringResource(R.string.organization_create_folder),
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    if (folders.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.organization_no_folders),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(folders, key = { "folder:${it.id}" }) { folder ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                        .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        folder.name,
                                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(onClick = { renaming = folder }, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            Icons.Filled.DriveFileRenameOutline,
                                            contentDescription = stringResource(
                                                R.string.organization_rename_named_folder, folder.name
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { deleting = folder }, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(
                                                R.string.organization_delete_named_folder, folder.name
                                            ),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.organization_done), style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }
}

@Composable
private fun FolderNameDialog(
    currentName: String,
    creating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val trimmedName = name.trim()
    val tooLong = trimmedName.length > 60
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                stringResource(
                    if (creating) R.string.organization_create_folder else R.string.organization_rename_folder
                ),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.organization_folder_name)) },
                    supportingText = {
                        Text(stringResource(
                            if (tooLong) R.string.organization_folder_name_too_long
                            else R.string.organization_folder_name_hint
                        ))
                    },
                    isError = tooLong,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName) },
                enabled = trimmedName.isNotEmpty() && !tooLong,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    stringResource(if (creating) R.string.organization_create_folder else R.string.action_save),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}
