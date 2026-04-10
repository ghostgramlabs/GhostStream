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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghostgramlabs.directserve.core.resources.R
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
    onBack: () -> Unit,
    onPrepareItem: (String) -> Unit,

    onRemoveItem: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
    onOpenAddFiles: () -> Unit,
    onOpenAddFolder: () -> Unit,
    onOpenBatchSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf("all") }
    var sortOption by rememberSaveable { mutableStateOf("newest") }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val selectedItemIds = remember { mutableStateListOf<String>() }

    LaunchedEffect(libraryState.items, libraryState.folders) {
        val validIds = libraryState.items.mapTo(mutableSetOf()) { it.id }
        selectedItemIds.removeAll { it !in validIds }
        if (libraryState.items.isEmpty() && libraryState.folders.isEmpty()) {
            selectedItemIds.clear()
        }
    }

    val categories = listOf("all", "videos", "photos", "music", "files")
    val filteredItems = libraryState.items
        .filter { item ->
            selectedCategory == "all" ||
                (selectedCategory == "videos" && item.category == MediaCategory.VIDEO) ||
                (selectedCategory == "photos" && item.category == MediaCategory.PHOTO) ||
                (selectedCategory == "music" && item.category == MediaCategory.MUSIC) ||
                (selectedCategory == "files" && item.category == MediaCategory.FILE)
        }
        .filter { item ->
            query.isBlank() || item.displayName.contains(query, ignoreCase = true)
        }
        .let { items ->
            when (sortOption) {
                "name" -> items.sortedBy { it.displayName.lowercase() }
                "size" -> items.sortedByDescending { it.sizeBytes }
                else -> items.sortedByDescending { it.dateAddedEpochMs }
            }
        }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
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

                hasNonDirectVideo = libraryState.items.any {
                    it.category == MediaCategory.VIDEO && it.playbackDecision.mode != PlaybackMode.DIRECT
                },
                onOpenAddFiles = onOpenAddFiles,
                onOpenAddFolder = onOpenAddFolder,
                onOpenBatchSelect = onOpenBatchSelect,

            )
        }

        if (libraryState.items.isEmpty() && libraryState.folders.isEmpty()) {
            item {
                LibraryEmptyState(
                    title = stringResource(R.string.library_empty_title),
                    description = stringResource(R.string.library_empty_body),
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
                            title = stringResource(R.string.library_section_folders),
                            subtitle = stringResource(R.string.library_section_folders_body),
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
                        title = stringResource(R.string.library_empty_filter_title),
                        description = stringResource(R.string.library_empty_filter_body),
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = stringResource(R.string.library_section_files),
                        subtitle = stringResource(R.string.library_items_in_view, filteredItems.size, if (filteredItems.size == 1) "" else "s"),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(filteredItems, key = { it.id }) { item ->
                    LibraryItemRow(
                        item = item,
                        compatibilityJob = compatibilityJobs[item.id],
                        showThumbnails = showThumbnails,

                        onPrepareItem = onPrepareItem,
                        onRemoveItem = onRemoveItem,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }


}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHeader(
    libraryState: LibraryState,
) {
    val hasVideos = libraryState.items.any { it.category == MediaCategory.VIDEO }
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.library_header_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.library_header_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasVideos) {
                Text(
                    text = stringResource(R.string.library_header_subtitles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LibraryInfoChip(label = stringResource(R.string.library_info_items), value = libraryState.summary.totalItems.toString(), showDot = true)
                LibraryInfoChip(label = stringResource(R.string.library_info_folders), value = libraryState.folders.size.toString())
                LibraryInfoChip(label = stringResource(R.string.library_info_size), value = formatBytes(libraryState.summary.totalBytes))
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

    hasNonDirectVideo: Boolean,
    onOpenAddFiles: () -> Unit,
    onOpenAddFolder: () -> Unit,
    onOpenBatchSelect: () -> Unit,

) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.library_manage_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.library_manage_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                label = { Text(stringResource(R.string.library_search_label)) },
                singleLine = true,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = stringResource(R.string.library_show_title), subtitle = stringResource(R.string.library_show_body))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { onSelectCategory(category) },
                            label = { Text(categoryLabel(category)) },
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
                SectionHeader(title = stringResource(R.string.library_add_title), subtitle = stringResource(R.string.library_add_body))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onOpenAddFiles,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) { Text(stringResource(R.string.library_add_files)) }
                    OutlinedButton(
                        onClick = onOpenAddFolder,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) { Text(stringResource(R.string.library_add_whole_folder)) }
                    OutlinedButton(
                        onClick = onOpenBatchSelect,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) { Text(stringResource(R.string.library_add_from_suggestions)) }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = stringResource(R.string.library_organize_title), subtitle = stringResource(R.string.library_organize_body))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onSortExpand,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) {
                        Text(stringResource(R.string.library_sort_prefix, sortOptionLabel(sortOption)))
                    }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = onSortDismiss) {
                        listOf("newest", "name", "size").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(sortOptionLabel(option)) },
                                onClick = { onSortSelected(option) },
                            )
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
                        Text(stringResource(R.string.library_browser_prep_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.library_browser_prep_body),
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
        tonalElevation = 1.dp,
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
                        stringResource(R.string.library_folder_summary, folder.fileCount, formatBytes(folder.totalSizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = { onRemoveFolder(folder.id) },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = librarySecondaryButtonColors(),
            ) {
                Text(stringResource(R.string.common_remove))
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

    onPrepareItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
                                if (!item.isAvailable) stringResource(R.string.library_unavailable) else null,
                            ).joinToString(" | "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode == PlaybackMode.DIRECT) {
                                ItemPill(stringResource(R.string.library_direct_play), accent = true)
                            }
                            item.playbackDecision.compatibilityLabel?.let { label -> ItemPill(label) }
                            if (item.subtitleMatch != null) {
                                ItemPill(stringResource(R.string.library_subtitle))
                            }
                            if (!item.isAvailable) {
                                ItemPill(stringResource(R.string.library_unavailable))
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
                                text = stringResource(R.string.library_browser_prep_body),
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
                    OutlinedButton(
                        onClick = { onRemoveItem(item.id) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = librarySecondaryButtonColors(),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.common_remove))
                    }

                    if (item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT) {
                        when (compatibilityJob?.status) {
                            CompatibilityStatus.QUEUED,
                            CompatibilityStatus.PREPARING,
                            -> OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = librarySecondaryButtonColors(),
                            ) {
                                Text(if (compactActions) stringResource(R.string.library_preparing) else stringResource(R.string.library_preparing_for_browser))
                            }

                            CompatibilityStatus.READY -> OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = librarySecondaryButtonColors(),
                            ) {
                                Text(if (compactActions) stringResource(R.string.library_ready) else stringResource(R.string.library_ready_for_browser))
                            }

                            else -> Button(
                                onClick = { onPrepareItem(item.id) },
                                enabled = item.isAvailable,
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = libraryPrimaryButtonColors(),
                            ) {
                                Text(
                                    if (compatibilityJob?.status == CompatibilityStatus.FAILED) {
                                        if (compactActions) stringResource(R.string.library_try_again) else stringResource(R.string.library_try_browser_prep_again)
                                    } else {
                                        if (compactActions) stringResource(R.string.library_prepare) else stringResource(R.string.library_prepare_for_browser)
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

@Composable
private fun itemTypeLabel(category: MediaCategory): String {
    return when (category) {
        MediaCategory.VIDEO -> androidx.compose.ui.res.stringResource(R.string.library_type_video)
        MediaCategory.PHOTO -> androidx.compose.ui.res.stringResource(R.string.library_type_photo)
        MediaCategory.MUSIC -> androidx.compose.ui.res.stringResource(R.string.library_type_music)
        MediaCategory.FILE -> androidx.compose.ui.res.stringResource(R.string.library_type_file)
    }
}

@Composable
private fun compatibilityStatusLabel(job: CompatibilityJob): String {
    if (job.streamable && job.status != CompatibilityStatus.READY) {
        return stringResource(R.string.library_compat_ready_to_play, job.message)
    }
    val prefix = when (job.status) {
        CompatibilityStatus.IDLE -> stringResource(R.string.library_compat_not_prepared)
        CompatibilityStatus.QUEUED -> stringResource(R.string.library_compat_queued)
        CompatibilityStatus.PREPARING -> stringResource(R.string.library_compat_preparing)
        CompatibilityStatus.READY -> stringResource(R.string.library_compat_ready)
        CompatibilityStatus.FAILED -> stringResource(R.string.library_compat_unavailable)
    }
    return stringResource(R.string.library_compat_message, prefix, job.message)
}

@Composable
private fun categoryLabel(category: String): String = when (category) {
    "videos" -> stringResource(R.string.library_category_videos)
    "photos" -> stringResource(R.string.library_category_photos)
    "music" -> stringResource(R.string.library_category_music)
    "files" -> stringResource(R.string.library_category_files)
    else -> stringResource(R.string.library_category_all)
}

@Composable
private fun sortOptionLabel(option: String): String = when (option) {
    "name" -> stringResource(R.string.library_sort_name)
    "size" -> stringResource(R.string.library_sort_size)
    else -> stringResource(R.string.library_sort_newest)
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
