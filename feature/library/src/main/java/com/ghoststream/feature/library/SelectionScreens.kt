package com.ghoststream.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AddFilesScreen(
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

    SelectionScreenScaffold(
        modifier = modifier,
        title = "Add Files",
        subtitle = "Select one or more files to make available in your session.",
        primaryLabel = "Add Selected",
        onPrimary = {
            onAddSelected(selectedUris.toList())
            onBack()
        },
        onSecondary = { launcher.launch(arrayOf("*/*")) },
        secondaryLabel = "Pick Files Again",
        onBack = onBack,
    ) {
        if (selectedUris.isEmpty()) {
            LibraryEmptyState(
                title = "Nothing selected yet",
                description = "Choose videos, photos, music, PDFs, or other files from the system picker.",
            )
        } else {
            selectedUris.forEach { uri ->
                SelectionUriRow(uri = uri)
            }
        }
    }
}

@Composable
fun AddFolderScreen(
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

    SelectionScreenScaffold(
        modifier = modifier,
        title = "Add Folder",
        subtitle = "Choose a folder so DirectServe can scan its contents for sharing.",
        primaryLabel = "Add Folder",
        onPrimary = {
            selectedTreeUri?.let(onAddFolder)
            onBack()
        },
        onSecondary = { launcher.launch(null) },
        secondaryLabel = "Choose Another Folder",
        onBack = onBack,
    ) {
        if (selectedTreeUri == null) {
            LibraryEmptyState(
                title = "No folder selected",
                description = "DirectServe can keep a lightweight link to folders you choose with Android's document picker.",
            )
        } else {
            SelectionUriRow(uri = selectedTreeUri!!, title = "Selected folder")
        }
    }
}

@Composable
private fun SelectionScreenScaffold(
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = selectionSecondaryButtonColors(),
                ) {
                    Text("Back")
                }
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.weight(1f),
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
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = selectionPrimaryButtonColors(),
            ) {
                Text(primaryLabel)
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun SelectionUriRow(uri: Uri, title: String = uri.lastPathSegment ?: "Selected item") {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = selectionRaisedColor()),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                Icon(Icons.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
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
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    contentColor = MaterialTheme.colorScheme.onSurface,
)
