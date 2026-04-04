package com.ghoststream.feature.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onSkip: () -> Unit,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = listOf(
        OnboardingCard(
            icon = Icons.Outlined.Collections,
            title = "Add files or folders",
            description = "Works with your own local files so you can start from photos, videos, music, and documents already on your device.",
        ),
        OnboardingCard(
            icon = Icons.Outlined.CloudOff,
            title = "Nearby devices only",
            description = "Connect nearby devices over the same Wi-Fi or your phone hotspot. No internet required.",
        ),
        OnboardingCard(
            icon = Icons.Outlined.QrCode2,
            title = "Open in any browser",
            description = "The receiver only needs a browser. Scan the QR code, stream media, or download the original file instantly.",
        ),
        OnboardingCard(
            icon = Icons.Outlined.Lock,
            title = "Private by design",
            description = "Data stays on your device. No cloud, no login, and no remote internet access in this build.",
        ),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ghostBackdropBrush())
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSkip) {
                    Text("Skip")
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                Card(
                    modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = ghostPanelColor(),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(72.dp),
                            shape = CircleShape,
                            color = ghostAccentSurface(),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = pages[page].icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            text = pages[page].title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = pages[page].description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(width = if (pagerState.currentPage == index) 28.dp else 8.dp, height = 8.dp)
                            .background(
                                color = if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary else ghostMutedSurface(),
                                shape = RoundedCornerShape(99.dp),
                            ),
                    )
                }
            }

            Button(
                onClick = {
                    if (pagerState.currentPage == pages.lastIndex) {
                        onGetStarted()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(if (pagerState.currentPage == pages.lastIndex) "Get Started" else "Next")
            }
        }
    }
}

private data class OnboardingCard(
    val icon: ImageVector,
    val title: String,
    val description: String,
)

@Composable
private fun ghostBackdropBrush(): Brush {
    val colors = MaterialTheme.colorScheme
    return Brush.verticalGradient(
        listOf(
            colors.background,
            colors.surface.copy(alpha = 0.98f),
            colors.surfaceVariant.copy(alpha = 0.86f),
        ),
    )
}

@Composable
private fun ghostPanelColor(): Color = MaterialTheme.colorScheme.surface

@Composable
private fun ghostAccentSurface(): Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)

@Composable
private fun ghostMutedSurface(): Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
