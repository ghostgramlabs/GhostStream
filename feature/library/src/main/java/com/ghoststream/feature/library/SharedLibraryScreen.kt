package com.ghoststream.feature.library

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghoststream.core.media.CompatibilityJob
import com.ghoststream.core.media.CompatibilityStatus
import com.ghoststream.core.model.LibraryState
import com.ghoststream.core.model.MediaCategory
import com.ghoststream.core.model.PlaybackMode
import com.ghoststream.core.model.SharedFolder
import com.ghoststream.core.model.SharedItem
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SharedLibraryScreen(
    libraryState: LibraryState,
    compatibilityJobs: Map<String, CompatibilityJob>,
    showThumbnails: Boolean,
    onPrepareItem: (String) -> Unit,
    onSavePresetSelection: (String, Collection<String>) -> Unit,
    onRemoveItem: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onOpenAddFiles: () -> Unit,
    onOpenAddFolder: () -> Unit,
    onOpenBatchSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var sortOption by rememberSaveable { mutableStateOf("Newest") }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var showPresetDialog by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    val selectedItemIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(libraryState.items, libraryState.folders) {
        val validIds = libraryState.items.mapTo(mutableSetOf()) { it.id }
        selectedItemIds.removeAll { it !in validIds }
        if (libraryState.items.isEmpty() && libraryState.folders.isEmpty()) {
            selectionMode = false
            selectedItemIds.clear()
            showPresetDialog = false
        }
    }

    val categories = listOf("All", "Videos", "Photos", "Music", "Files")
    val filteredItems = libraryState.items
        .filter { item ->
            selectedCategory == "All" ||
                (selectedCategory == "Videos" && item.category == MediaCategory.VIDEO) ||
                (selectedCategory == "Photos" && item.category == MediaCategory.PHOTO) ||
                (selectedCategory == "Music" && item.category == MediaCategory.MUSIC) ||
                (selectedCategory == "Files" && item.category == MediaCategory.FILE)
        }
        .filter { item ->
            query.isBlank() || item.displayName.contains(query, ignoreCase = true)
        }
        .let { items ->
            when (sortOption) {
                "Name" -> items.sortedBy { it.displayName.lowercase() }
                "Size" -> items.sortedByDescending { it.sizeBytes }
                else -> items.sortedByDescending { it.dateAddedEpochMs }
            }
        }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LibraryHeader(libraryState = libraryState) }
        item {
            LibraryControlsCard(
                query = query,
                onQueryChange = { query = it },
                categories = categories,
                selectedCategory = selectedCategory,
                onSelectCategory = { selectedCategory = it },
                sortOption = sortOption,
                sortMenuExpanded = sortMenuExpanded,
                onSortExpand = { sortMenuExpanded = true },
                onSortDismiss = { sortMenuExpanded = false },
                onSortSelected = {
                    sortOption = it
                    sortMenuExpanded = false
                },
                selectionMode = selectionMode,
                selectedCount = selectedItemIds.size,
                hasNonDirectVideo = libraryState.items.any {
                    it.category == MediaCategory.VIDEO && it.playbackDecision.mode != PlaybackMode.DIRECT
                },
                onOpenAddFiles = onOpenAddFiles,
                onOpenAddFolder = onOpenAddFolder,
                onOpenBatchSelect = onOpenBatchSelect,
                onEnterSelectionMode = {
                    selectionMode = true
                    selectedItemIds.clear()
                },
                onCancelSelectionMode = {
                    selectionMode = false
                    selectedItemIds.clear()
                },
                onSaveSelection = {
                    presetName = if (selectedItemIds.size == 1) "Single file" else "Selected files"
                    showPresetDialog = true
                },
            )
        }

        if (libraryState.items.isEmpty() && libraryState.folders.isEmpty()) {
            item {
                LibraryEmptyState(
                    title = "No files added yet",
                    description = "Pick files, add a folder, or use Smart Picks to build your library.",
                )
            }
        } else {
            if (libraryState.folders.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SectionHeader(
                            title = "Folders",
                            subtitle = "These folders stay linked to your current share.",
                        )
                        libraryState.folders.forEach { folder ->
                            FolderRow(folder = folder, onRemoveFolder = onRemoveFolder)
                        }
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                item {
                    LibraryEmptyState(
                        title = "Nothing matches this view",
                        description = "Try another filter or search term.",
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = "Files",
                        subtitle = "${filteredItems.size} item${if (filteredItems.size == 1) "" else "s"} in this view",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(filteredItems, key = { it.id }) { item ->
                    LibraryItemRow(
                        item = item,
                        compatibilityJob = compatibilityJobs[item.id],
                        showThumbnails = showThumbnails,
                        selectionMode = selectionMode,
                        isSelected = item.id in selectedItemIds,
                        onToggleSelected = { itemId ->
                            if (itemId in selectedItemIds) selectedItemIds.remove(itemId) else selectedItemIds.add(itemId)
                        },
                        onPrepareItem = onPrepareItem,
                        onRemoveItem = onRemoveItem,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }

    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("Save selected files") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Save these files together so you can bring them back with one tap later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("Share name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSavePresetSelection(presetName, selectedItemIds.toList())
                        showPresetDialog = false
                        selectionMode = false
                        selectedItemIds.clear()
                    },
                    enabled = presetName.isNotBlank() && selectedItemIds.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    colors = libraryPrimaryButtonColors(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showPresetDialog = false },
                    shape = RoundedCornerShape(16.dp),
                    colors = librarySecondaryButtonColors(),
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHeader(
    libraryState: LibraryState,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Shared Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "These are the files people can open from your share link.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LibraryInfoChip(label = "Items", value = libraryState.summary.totalItems.toString(), showDot = true)
                LibraryInfoChip(label = "Folders", value = libraryState.folders.size.toString())
                LibraryInfoChip(label = "Size", value = formatBytes(libraryState.summary.totalBytes))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryInfoChip(
    label: String,
    value: String,
    showDot: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("$label ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun LibraryEmptyState(title: String, description: String) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryControlsCard(
    query: String,
    onQueryChange: (String) -> Unit,
    categories: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    sortOption: String,
    sortMenuExpanded: Boolean,
    onSortExpand: () -> Unit,
    onSortDismiss: () -> Unit,
    onSortSelected: (String) -> Unit,
    selectionMode: Boolean,
    selectedCount: Int,
    hasNonDirectVideo: Boolean,
    onOpenAddFiles: () -> Unit,
    onOpenAddFolder: () -> Unit,
    onOpenBatchSelect: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    onCancelSelectionMode: () -> Unit,
    onSaveSelection: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!selectionMode) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Save only the files you want", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Tap Choose files to save, then select items below and save them as one reusable share.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (selectionMode) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Choose files for a saved share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (selectedCount == 0) {
                                "Tap Select on the files below. When you are done, save the selected files together."
                            } else {
                                "$selectedCount file${if (selectedCount == 1) "" else "s"} selected. You can keep choosing more before saving."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                label = { Text("Search files") },
                singleLine = true,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Filter", subtitle = "Choose what you want to see")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { onSelectCategory(category) },
                            label = { Text(category) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = libraryAccentSurface(),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                                labelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedCategory == category,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = libraryAccentBorder(),
                            ),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Actions", subtitle = "Add content or save a smaller share")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onSortExpand,
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) {
                        Text("Sort: $sortOption")
                    }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = onSortDismiss) {
                        listOf("Newest", "Name", "Size").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { onSortSelected(option) },
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenAddFiles,
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) { Text("Add files") }
                    OutlinedButton(
                        onClick = onOpenAddFolder,
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) { Text("Add folder") }
                    OutlinedButton(
                        onClick = onOpenBatchSelect,
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) { Text("Smart Picks") }

                    if (selectionMode) {
                        OutlinedButton(
                            onClick = onCancelSelectionMode,
                            shape = RoundedCornerShape(16.dp),
                            colors = librarySecondaryButtonColors(),
                        ) {
                            Text("Stop choosing")
                        }
                        Button(
                            onClick = onSaveSelection,
                            enabled = selectedCount > 0,
                            shape = RoundedCornerShape(16.dp),
                            colors = libraryPrimaryButtonColors(),
                        ) {
                            Text("Save selected files")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onEnterSelectionMode,
                            shape = RoundedCornerShape(16.dp),
                            colors = librarySecondaryButtonColors(),
                        ) {
                            Text("Choose files to save")
                        }
                    }
                }
            }

            if (hasNonDirectVideo) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Browser prep keeps the original", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "DirectServe may make a temporary browser copy for playback. Downloads still use the original file.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: SharedFolder,
    onRemoveFolder: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(libraryAccentSurface(), RoundedCornerShape(16.dp))
                        .border(BorderStroke(1.dp, libraryAccentBorder()), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(folder.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${folder.fileCount} files | ${formatBytes(folder.totalSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { onRemoveFolder(folder.id) },
                shape = RoundedCornerShape(14.dp),
                colors = librarySecondaryButtonColors(),
            ) {
                Text("Remove")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryItemRow(
    item: SharedItem,
    compatibilityJob: CompatibilityJob?,
    showThumbnails: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelected: (String) -> Unit,
    onPrepareItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clickable(enabled = selectionMode) { onToggleSelected(item.id) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                libraryAccentSurface()
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) libraryAccentBorder() else MaterialTheme.colorScheme.outline,
        ),
    ) {
        BoxWithConstraints {
            val compactActions = maxWidth < 520.dp
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LibraryItemVisual(item = item, showThumbnails = showThumbnails)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(
                                itemTypeLabel(item.category),
                                item.durationMs?.let(::formatDuration),
                                formatBytes(item.sizeBytes),
                                if (!item.isAvailable) "Unavailable" else null,
                            ).joinToString(" | "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode == PlaybackMode.DIRECT) {
                                ItemPill("Direct Play", accent = true)
                            }
                            item.playbackDecision.compatibilityLabel?.let { label -> ItemPill(label) }
                            if (item.subtitleMatch != null) {
                                ItemPill("Subtitle")
                            }
                            if (!item.isAvailable) {
                                ItemPill("Unavailable")
                            }
                            if (selectionMode) {
                                ItemPill(if (isSelected) "Selected" else "Tap to select", accent = isSelected)
                            }
                        }
                    }
                }

                if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            compatibilityJob?.let { job ->
                                Text(
                                    text = compatibilityStatusLabel(job),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (job.status == CompatibilityStatus.READY) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(
                                text = "This creates a temporary browser copy. Downloads still use the original file.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (selectionMode) {
                        OutlinedButton(
                            onClick = { onToggleSelected(item.id) },
                            shape = RoundedCornerShape(16.dp),
                            colors = librarySecondaryButtonColors(),
                        ) {
                            Text(if (isSelected) "Selected" else "Select this file")
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onRemoveItem(item.id) },
                            shape = RoundedCornerShape(16.dp),
                            colors = librarySecondaryButtonColors(),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Remove")
                        }
                    }

                    if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT) {
                        when (compatibilityJob?.status) {
                            CompatibilityStatus.QUEUED,
                            CompatibilityStatus.PREPARING,
                            -> OutlinedButton(
                                onClick = {},
                                enabled = false,
                                shape = RoundedCornerShape(16.dp),
                                colors = librarySecondaryButtonColors(),
                            ) {
                                Text(if (compactActions) "Preparing" else "Preparing for browser")
                            }

                            CompatibilityStatus.READY -> OutlinedButton(
                                onClick = {},
                                enabled = false,
                                shape = RoundedCornerShape(16.dp),
                                colors = librarySecondaryButtonColors(),
                            ) {
                                Text(if (compactActions) "Ready" else "Ready for browser")
                            }

                            else -> Button(
                                onClick = { onPrepareItem(item.id) },
                                enabled = item.isAvailable,
                                shape = RoundedCornerShape(16.dp),
                                colors = libraryPrimaryButtonColors(),
                            ) {
                                Text(
                                    if (compatibilityJob?.status == CompatibilityStatus.FAILED) {
                                        if (compactActions) "Try again" else "Try browser prep again"
                                    } else {
                                        if (compactActions) "Prepare" else "Prepare for browser"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryItemVisual(
    item: SharedItem,
    showThumbnails: Boolean,
) {
    val shape = RoundedCornerShape(16.dp)
    if (showThumbnails && (item.category == MediaCategory.PHOTO || item.category == MediaCategory.VIDEO)) {
        AsyncImage(
            model = Uri.parse(item.uri),
            contentDescription = null,
            modifier = Modifier
                .size(84.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(libraryAccentSurface(), RoundedCornerShape(18.dp))
                .border(BorderStroke(1.dp, libraryAccentBorder()), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (item.category) {
                    MediaCategory.VIDEO -> Icons.Outlined.Movie
                    MediaCategory.PHOTO -> Icons.Outlined.Photo
                    MediaCategory.MUSIC -> Icons.Outlined.MusicNote
                    MediaCategory.FILE -> Icons.Outlined.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun ItemPill(
    label: String,
    accent: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (accent) libraryAccentSurface() else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (accent) libraryAccentBorder() else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (accent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun itemTypeLabel(category: MediaCategory): String {
    return when (category) {
        MediaCategory.VIDEO -> "Video"
        MediaCategory.PHOTO -> "Photo"
        MediaCategory.MUSIC -> "Music"
        MediaCategory.FILE -> "File"
    }
}

private fun compatibilityStatusLabel(job: CompatibilityJob): String {
    if (job.streamable && job.status != CompatibilityStatus.READY) {
        return "Ready to play | ${job.message}"
    }
    val prefix = when (job.status) {
        CompatibilityStatus.IDLE -> "Not prepared"
        CompatibilityStatus.QUEUED -> "Queued"
        CompatibilityStatus.PREPARING -> "Preparing"
        CompatibilityStatus.READY -> "Ready"
        CompatibilityStatus.FAILED -> "Unavailable"
    }
    return "$prefix | ${job.message}"
}

@Composable
private fun libraryPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f),
)

@Composable
private fun librarySecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.46f),
)

@Composable
private fun libraryAccentSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

@Composable
private fun libraryAccentBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "${(value * 10).roundToInt() / 10.0} ${units[index]}"
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
