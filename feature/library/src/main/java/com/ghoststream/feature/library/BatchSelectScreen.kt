package com.ghoststream.feature.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghoststream.core.model.SmartSelectionGroup

@Composable
fun BatchSelectScreen(
    groups: List<SmartSelectionGroup>,
    onBack: () -> Unit,
    onAddGroup: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                Text("Smart Picks", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Smart one-tap groups based on your recent local media", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (groups.isEmpty()) {
            item {
                LibraryEmptyState(
                    title = "No smart groups available right now",
                    description = "Grant media access if needed, or add files manually from the picker.",
                )
            }
        } else {
            items(groups, key = { it.id }) { group ->
                Card(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = selectionPanelColor()),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), // Light elevation improves grouping without redesigning the flow.
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(group.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(group.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${group.itemCount} items | ${formatBytes(group.totalSizeBytes)}")
                        Button(
                            onClick = {
                                onAddGroup(group.uris.map(Uri::parse))
                                onBack()
                            },
                            modifier = Modifier.heightIn(min = 48.dp), // Keeps the main action aligned with the app-wide touch target standard.
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text("Add Group")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun selectionPanelColor() = MaterialTheme.colorScheme.surface
