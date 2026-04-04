package com.ghoststream.feature.library

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
            .background(
                Brush.verticalGradient(listOf(Color(0xFF05070A), Color(0xFF121A28))),
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1522)),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(group.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(group.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("${group.itemCount} items | ${formatBytes(group.totalSizeBytes)}")
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                onAddGroup(group.uris.map(Uri::parse))
                                onBack()
                            },
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Add Group")
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }
}
