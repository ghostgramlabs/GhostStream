package com.ghoststream.feature.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07080C), Color(0xFF10141B)),
                ),
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text("Shared Library", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Curate everything available to nearby browsers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121823)),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        label = { Text("Search by filename") },
                        singleLine = true,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        categories.forEach { category ->
                            AssistChip(
                                onClick = { selectedCategory = category },
                                label = { Text(category) },
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = { sortMenuExpanded = true }, shape = RoundedCornerShape(16.dp)) {
                            Text("Sort: $sortOption")
                        }
                        DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                            listOf("Newest", "Name", "Size").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        sortOption = option
                                        sortMenuExpanded = false
                                    },
                                )
                            }
                        }
                        OutlinedButton(onClick = onOpenAddFiles, shape = RoundedCornerShape(16.dp)) { Text("Add files") }
                        OutlinedButton(onClick = onOpenAddFolder, shape = RoundedCornerShape(16.dp)) { Text("Add folder") }
                        OutlinedButton(onClick = onOpenBatchSelect, shape = RoundedCornerShape(16.dp)) { Text("Batch select") }
                    }
                }
            }
        }

        if (libraryState.items.isEmpty() && libraryState.folders.isEmpty()) {
            item {
                LibraryEmptyState(
                    title = "No files added yet",
                    description = "Pick files, add a folder, or use batch select to build your offline library.",
                )
            }
        } else {
            if (libraryState.folders.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Folders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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
                items(filteredItems, key = { it.id }) { item ->
                    LibraryItemRow(
                        item = item,
                        compatibilityJob = compatibilityJobs[item.id],
                        onRemoveItem = onRemoveItem,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }
}

@Composable
internal fun LibraryEmptyState(title: String, description: String) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111720)),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderRow(folder: SharedFolder, onRemoveFolder: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF18202A)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(folder.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${folder.fileCount} files | ${formatBytes(folder.totalSizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Remove folder",
                modifier = Modifier.clickable { onRemoveFolder(folder.id) },
            )
        }
    }
}

@Composable
private fun LibraryItemRow(
    item: SharedItem,
    compatibilityJob: CompatibilityJob?,
    onRemoveItem: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121823)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.category == MediaCategory.PHOTO || item.category == MediaCategory.VIDEO) {
                AsyncImage(
                    model = Uri.parse(item.uri),
                    contentDescription = null,
                    modifier = Modifier
                        .size(76.dp)
                        .background(Color(0xFF1B222C), RoundedCornerShape(14.dp)),
                )
            } else {
                Icon(
                    imageVector = when (item.category) {
                        MediaCategory.VIDEO -> Icons.Outlined.Movie
                        MediaCategory.PHOTO -> Icons.Outlined.Photo
                        MediaCategory.MUSIC -> Icons.Outlined.MusicNote
                        MediaCategory.FILE -> Icons.Outlined.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = listOfNotNull(
                        item.category.name.lowercase().replaceFirstChar { it.uppercase() },
                        item.durationMs?.let(::formatDuration),
                        formatBytes(item.sizeBytes),
                        if (!item.isAvailable) "Unavailable" else null,
                    ).joinToString(" | "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                item.playbackDecision.compatibilityLabel?.let { label ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                compatibilityJob
                    ?.takeIf { item.category == MediaCategory.VIDEO && item.playbackDecision.mode != PlaybackMode.DIRECT }
                    ?.let { job ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = compatibilityStatusLabel(job),
                            color = when (job.status) {
                                CompatibilityStatus.FAILED -> MaterialTheme.colorScheme.error
                                CompatibilityStatus.READY -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
            }
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Remove item",
                modifier = Modifier.clickable { onRemoveItem(item.id) },
            )
        }
    }
}

private fun compatibilityStatusLabel(job: CompatibilityJob): String {
    if (job.streamable && job.status != CompatibilityStatus.READY) {
        return "Playback live | ${job.message}"
    }
    val prefix = when (job.status) {
        CompatibilityStatus.IDLE -> "Compatibility idle"
        CompatibilityStatus.QUEUED -> "Compatibility queued"
        CompatibilityStatus.PREPARING -> "Compatibility preparing"
        CompatibilityStatus.READY -> "Compatibility ready"
        CompatibilityStatus.FAILED -> "Compatibility unavailable"
    }
    return "$prefix | ${job.message}"
}

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
