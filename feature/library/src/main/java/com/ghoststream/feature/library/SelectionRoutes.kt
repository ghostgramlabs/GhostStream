package com.ghoststream.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghoststream.core.model.SmartSelectionGroup

@Composable
fun AddFilesRoute(
    onBack: () -> Unit,
    onAddSelected: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedUris = remember { mutableStateListOf<Uri>() }
    val launcher = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        selectedUris.clear()
        selectedUris.addAll(uris)
    }

    LaunchedEffect(Unit) {
        launcher.launch(arrayOf("*/*"))
    }

    SelectionRouteScaffold(
        modifier = modifier,
        title = stringResource(R.string.selection_add_files_title),
        subtitle = stringResource(R.string.selection_add_files_body),
        primaryLabel = stringResource(R.string.selection_add_selected),
        onPrimary = {
            onAddSelected(selectedUris.toList())
            onBack()
        },
        onSecondary = { launcher.launch(arrayOf("*/*")) },
        secondaryLabel = stringResource(R.string.selection_pick_files_again),
        onBack = onBack,
    ) {
        if (selectedUris.isEmpty()) {
            LibraryEmptyState(
                title = stringResource(R.string.selection_nothing_selected_title),
                description = stringResource(R.string.selection_nothing_selected_body),
            )
        } else {
            selectedUris.forEach { uri ->
                SelectionUriCard(uri = uri)
            }
        }
    }
}

@Composable
fun AddFolderRoute(
    onBack: () -> Unit,
    onAddFolder: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(OpenDocumentTree()) { uri ->
        selectedTreeUri = uri
    }

    LaunchedEffect(Unit) {
        launcher.launch(null)
    }

    SelectionRouteScaffold(
        modifier = modifier,
        title = stringResource(R.string.selection_add_folder_title),
        subtitle = stringResource(R.string.selection_add_folder_body),
        primaryLabel = stringResource(R.string.selection_add_folder),
        onPrimary = {
            selectedTreeUri?.let(onAddFolder)
            onBack()
        },
        onSecondary = { launcher.launch(null) },
        secondaryLabel = stringResource(R.string.selection_choose_another_folder),
        onBack = onBack,
    ) {
        if (selectedTreeUri == null) {
            LibraryEmptyState(
                title = stringResource(R.string.selection_no_folder_title),
                description = stringResource(R.string.selection_no_folder_body),
            )
        } else {
            SelectionUriCard(uri = selectedTreeUri!!, title = stringResource(R.string.selection_selected_folder))
        }
    }
}

@Composable
fun BatchSelectRoute(
    groups: List<SmartSelectionGroup>,
    isLoading: Boolean,
    hasMediaAccess: Boolean,
    onBack: () -> Unit,
    onRequestAccess: () -> Unit,
    onRefresh: () -> Unit,
    onAddGroup: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.selection_smart_picks_title), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.selection_smart_picks_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasMediaAccess) {
                    OutlinedButton(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(16.dp),
                        colors = selectionSecondaryButtonColors(),
                    ) {
                        Text(stringResource(R.string.common_refresh))
                    }
                }
            }
        }

        if (!hasMediaAccess) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = selectionPanelColor()),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.selection_media_access_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.selection_media_access_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onRequestAccess,
                            shape = RoundedCornerShape(16.dp),
                            colors = selectionPrimaryButtonColors(),
                        ) {
                            Text(stringResource(R.string.selection_grant_access))
                        }
                    }
                }
            }
        } else if (isLoading) {
            item {
                LibraryEmptyState(
                    title = stringResource(R.string.selection_loading_groups_title),
                    description = stringResource(R.string.selection_loading_groups_body),
                )
            }
        } else if (groups.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = selectionPanelColor()),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.selection_no_groups_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.selection_no_groups_body),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = onRefresh,
                            shape = RoundedCornerShape(16.dp),
                            colors = selectionSecondaryButtonColors(),
                        ) {
                            Text(stringResource(R.string.selection_refresh_groups))
                        }
                    }
                }
            }
        } else {
            items(groups, key = { it.id }) { group ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = selectionPanelColor()),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(group.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(group.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.library_items_in_view, group.itemCount, if (group.itemCount == 1) "" else "s") + " | " + formatBytes(group.totalSizeBytes))
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                onAddGroup(group.uris.map(Uri::parse))
                                onBack()
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = selectionPrimaryButtonColors(),
                        ) {
                            Text(stringResource(R.string.selection_add_group))
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }
}

@Composable
private fun SelectionRouteScaffold(
    title: String,
    subtitle: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = selectionPanelColor()),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
        item {
            Row(modifier = Modifier.padding(horizontal = 20.dp)) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(16.dp),
                    colors = selectionSecondaryButtonColors(),
                ) {
                    Text(stringResource(R.string.common_back))
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onSecondary,
                    shape = RoundedCornerShape(16.dp),
                    colors = selectionSecondaryButtonColors(),
                ) {
                    Text(secondaryLabel)
                }
            }
        }
        item {
            Button(
                onClick = onPrimary,
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = selectionPrimaryButtonColors(),
            ) {
                Text(primaryLabel)
            }
        }
        item { Spacer(modifier = Modifier.height(18.dp)) }
    }
}

@Composable
private fun SelectionUriCard(
    uri: Uri,
    title: String = uri.lastPathSegment ?: "",
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = selectionRaisedColor()),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(14.dp),
                    )
                    .padding(10.dp),
            ) {
                Icon(
                    Icons.Outlined.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title.ifBlank { stringResource(R.string.selection_selected_item) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(uri.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun selectionPanelColor() = MaterialTheme.colorScheme.surface

@Composable
private fun selectionRaisedColor() = MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun selectionPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun selectionSecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    contentColor = MaterialTheme.colorScheme.primary,
)
