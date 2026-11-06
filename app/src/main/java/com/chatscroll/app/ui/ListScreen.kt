package com.chatscroll.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.chatscroll.app.R
import com.chatscroll.app.data.ConversationFolder
import com.chatscroll.app.data.ConversationSummary
import com.chatscroll.app.data.SearchHit
import com.chatscroll.app.ui.theme.Ink
import com.chatscroll.app.ui.theme.InkSoft
import com.chatscroll.app.ui.theme.Terracotta
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
    onDeleteSelected: () -> Unit,
    searching: Boolean,
    query: String,
    onSearchingChange: (Boolean) -> Unit,
    onOpenSearch: (SearchHit) -> Unit,
    folders: List<ConversationFolder> = emptyList(),
    selectedFolder: String = "all",
    sortOrder: String = "time_desc",
    onFolderChange: (String) -> Unit = {},
    onSortChange: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    onMoveSelected: () -> Unit = {}
) {
    var managingFolders by remember { mutableStateOf(false) }
    val folderName = when (selectedFolder) {
        "all" -> stringResource(R.string.organization_all)
        "unfiled" -> stringResource(R.string.organization_unfiled)
        else -> folders.firstOrNull { it.id == selectedFolder }?.name
            ?: stringResource(R.string.organization_unfiled)
    }
    androidx.activity.compose.BackHandler(enabled = searching && !selecting) {
        onSearchingChange(false)
        onQueryChange("")
    }

    Scaffold(
        topBar = {
            Column {
            TopAppBar(
                title = {
                    val titleMode = when { selecting -> "selecting"; searching -> "searching"; else -> "library" }
                    Crossfade(titleMode, animationSpec = tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing),
                        label = "libraryTitle") { mode ->
                    if (mode == "selecting") {
                        Text(
                            stringResource(R.string.batch_selected, selectedIds.size),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (mode == "searching") {
                        TextField(
                            value = query,
                            onValueChange = onQueryChange,
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
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    }
                },
                navigationIcon = {
                    LibraryControlVisibility(visible = selecting) {
                        IconButton(
                            onClick = onExitSelection,
                            enabled = selecting,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                },
                actions = {
                    // Keep both modes in the same two touch targets while the controls fade.
                    Box(Modifier.size(width = 96.dp, height = 48.dp)) {
                        LibraryControlVisibility(visible = selecting) {
                            Row {
                                Box(Modifier.size(48.dp)) {
                                    LibraryControlVisibility(visible = selectedIds.isNotEmpty()) {
                                        IconButton(
                                            onClick = onMoveSelected,
                                            enabled = selecting && selectedIds.isNotEmpty(),
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.DriveFileMove,
                                                contentDescription = stringResource(R.string.organization_move_selected)
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = onDeleteSelected,
                                    enabled = selecting && selectedIds.isNotEmpty(),
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = if (selectedIds.isEmpty()) InkSoft else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        LibraryControlVisibility(visible = !selecting) {
                            Row {
                                if (searching) {
                                    IconButton(
                                        onClick = {
                                            onSearchingChange(false)
                                            onQueryChange("")
                                        },
                                        enabled = !selecting,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                                    }
                                } else {
                                    IconButton(
                                        onClick = { onSearchingChange(true) },
                                        enabled = !selecting,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                                    }
                                }
                                IconButton(
                                    onClick = onSettings,
                                    enabled = !selecting,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.action_settings))
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            if (!selecting) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                OrganizationToolbar(
                    folders = folders,
                    selectedFolder = selectedFolder,
                    folderName = folderName,
                    sortOrder = sortOrder,
                    onFolderChange = onFolderChange,
                    onSortChange = onSortChange,
                    onManageFolders = { managingFolders = true }
                )
                if (searching) {
                    Text(
                        stringResource(R.string.organization_search_scope, folderName),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                    )
                }
                }
            }
            }
        },
        floatingActionButton = {
            LibraryControlVisibility(visible = !selecting) {
                ExtendedFloatingActionButton(
                    onClick = { if (!selecting) onImport() },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_import)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (searching && query.isNotBlank()) {
            SearchResults(searchHits, onOpenSearch, Modifier.padding(padding))
        } else if (conversations.isEmpty()) {
            EmptyState(Modifier.padding(padding), scoped = selectedFolder != "all")
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (selecting) 12.dp else 96.dp
                ),
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
                        onLongClick = { if (!selecting) onItemAction(conversation) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = tween(
                                durationMillis = ReaderMotion.Reorder,
                                easing = ReaderMotion.Easing
                            )
                        )
                    )
                }
            }
        }
    }
    if (managingFolders) {
        FolderManagerDialog(
            folders = folders,
            onDismiss = { managingFolders = false },
            onCreateFolder = onCreateFolder,
            onRenameFolder = onRenameFolder,
            onDeleteFolder = onDeleteFolder
        )
    }
}

/** Changes only control opacity/position; content keeps its natural size. */
@Composable
private fun LibraryControlVisibility(visible: Boolean, content: @Composable () -> Unit) {
    val rise = with(LocalDensity.current) { 4.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.zIndex(if (visible) 1f else 0f),
        enter = fadeIn(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)) +
            slideInVertically(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)) { rise } +
            scaleIn(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing), initialScale = 0.96f),
        exit = fadeOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing)) +
            scaleOut(tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing), targetScale = 0.98f)
    ) {
        // Outgoing controls may still be drawn, but should leave accessibility immediately.
        Box(if (visible) Modifier else Modifier.clearAndSetSemantics {}) {
            content()
        }
    }
}

@Composable
private fun OrganizationToolbar(
    folders: List<ConversationFolder>,
    selectedFolder: String,
    folderName: String,
    sortOrder: String,
    onFolderChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onManageFolders: () -> Unit
) {
    var folderMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val folderDescription = stringResource(R.string.organization_folder_filter, folderName)
    val sortDescription = stringResource(R.string.organization_sort, stringResource(sortLabel(sortOrder)))
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val showSortLabel = maxWidth >= 320.dp * fontScale
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { folderMenuOpen = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = folderDescription }
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        folderName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(
                    expanded = folderMenuOpen,
                    onDismissRequest = { folderMenuOpen = false },
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    FolderFilterItem(
                        stringResource(R.string.organization_all), selectedFolder == "all"
                    ) {
                        folderMenuOpen = false
                        onFolderChange("all")
                    }
                    FolderFilterItem(
                        stringResource(R.string.organization_unfiled), selectedFolder == "unfiled"
                    ) {
                        folderMenuOpen = false
                        onFolderChange("unfiled")
                    }
                    folders.forEach { folder ->
                        FolderFilterItem(folder.name, selectedFolder == folder.id) {
                            folderMenuOpen = false
                            onFolderChange(folder.id)
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.organization_manage_folders)) },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                        onClick = {
                            folderMenuOpen = false
                            onManageFolders()
                        }
                    )
                }
            }
            Box(if (showSortLabel) Modifier.weight(0.85f) else Modifier) {
                if (showSortLabel) {
                    TextButton(
                        onClick = { sortMenuOpen = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = sortDescription }
                    ) {
                        Icon(Icons.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(compactSortLabel(sortOrder)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    IconButton(
                        onClick = { sortMenuOpen = true },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = sortDescription }
                    ) {
                        Icon(Icons.Filled.Sort, contentDescription = null)
                    }
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    SortOrders.forEach { order ->
                        DropdownMenuItem(
                            text = { Text(stringResource(sortLabel(order))) },
                            trailingIcon = {
                                if (sortOrder == order) Icon(Icons.Filled.Check, contentDescription = null)
                            },
                            modifier = Modifier.semantics { selected = sortOrder == order },
                            onClick = {
                                sortMenuOpen = false
                                onSortChange(order)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderFilterItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingIcon = {
            if (isSelected) Icon(Icons.Filled.Check, contentDescription = null)
        },
        modifier = Modifier.semantics { selected = isSelected },
        onClick = onClick
    )
}

private val SortOrders = listOf("time_desc", "time_asc", "name_asc", "name_desc", "size_desc", "size_asc")

private fun sortLabel(order: String): Int = when (order) {
    "time_asc" -> R.string.organization_sort_time_asc
    "name_asc" -> R.string.organization_sort_name_asc
    "name_desc" -> R.string.organization_sort_name_desc
    "size_desc" -> R.string.organization_sort_size_desc
    "size_asc" -> R.string.organization_sort_size_asc
    else -> R.string.organization_sort_time_desc
}

private fun compactSortLabel(order: String): Int = when (order) {
    "time_asc" -> R.string.library_sort_oldest
    "name_asc" -> R.string.library_sort_name_asc
    "name_desc" -> R.string.library_sort_name_desc
    "size_desc" -> R.string.library_sort_largest
    "size_asc" -> R.string.library_sort_smallest
    else -> R.string.library_sort_newest
}

/** Parent-supplied matches within the currently selected folder scope. */
@Composable
private fun SearchResults(
    hits: List<SearchHit>?,
    onOpen: (SearchHit) -> Unit,
    modifier: Modifier = Modifier
) {
    if (hits == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_search_loading), color = InkSoft)
        }
        return
    }
    if (hits.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.search_empty), color = InkSoft, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(hits, key = { it.summary.id }) { hit ->
            SearchHitCard(
                hit,
                onClick = { onOpen(hit) },
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec = tween(
                        durationMillis = ReaderMotion.Reorder,
                        easing = ReaderMotion.Easing
                    )
                )
            )
        }
    }
}

@Composable
private fun SearchHitCard(hit: SearchHit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick)
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
            Spacer(Modifier.height(5.dp))
            SourceSizeLabel(hit.summary.sourceSizeBytes)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, scoped: Boolean = false) {
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
            stringResource(if (scoped) R.string.organization_folder_empty_title else R.string.list_empty_title),
            fontFamily = FontFamily.Serif,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (scoped) R.string.organization_folder_empty_subtitle else R.string.list_empty_subtitle),
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
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
    val isSelected = selecting && selected
    val colors = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) colors.primaryContainer else colors.surfaceContainerLow,
        animationSpec = tween(durationMillis = ReaderMotion.Feedback, easing = ReaderMotion.Easing),
        label = "conversationSelectionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary.copy(alpha = 0.65f) else colors.primary.copy(alpha = 0f),
        animationSpec = tween(durationMillis = ReaderMotion.Feedback, easing = ReaderMotion.Easing),
        label = "conversationSelectionBorder"
    )
    val selectionColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.onSurfaceVariant,
        animationSpec = tween(durationMillis = ReaderMotion.Feedback, easing = ReaderMotion.Easing),
        label = "conversationSelectionIndicator"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { if (selecting) { this.selected = selected; role = Role.Checkbox } }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            LibraryControlVisibility(visible = selecting) {
                // Reserve the complete marker slot immediately; never animate title width.
                Box(Modifier.width(36.dp)) {
                    Crossfade(
                        targetState = selected,
                        modifier = Modifier.size(24.dp),
                        animationSpec = tween(ReaderMotion.Feedback, easing = ReaderMotion.Easing),
                        label = "conversationSelectionCheck"
                    ) { checked ->
                        Icon(
                            if (checked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = null,
                            tint = selectionColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = stringResource(R.string.action_pin),
                            tint = colors.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        conversation.title,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!conversation.seen) {
                        Spacer(Modifier.width(8.dp))
                        NewBadge()
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(countText, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(
                        sourceSizeText(conversation.sourceSizeBytes, compact = true),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    Text(dateText, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun sourceSizeText(sourceSizeBytes: Long?, compact: Boolean = false): String {
    val context = LocalContext.current
    return if (sourceSizeBytes == null || sourceSizeBytes < 0) {
        stringResource(
            if (compact) R.string.library_original_size_unknown else R.string.organization_original_size_unknown
        )
    } else {
        stringResource(
            if (compact) R.string.library_original_size else R.string.organization_original_size,
            android.text.format.Formatter.formatFileSize(context, sourceSizeBytes)
        )
    }
}

@Composable
private fun SourceSizeLabel(sourceSizeBytes: Long?) {
    Text(
        text = sourceSizeText(sourceSizeBytes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
            color = MaterialTheme.colorScheme.surface,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
