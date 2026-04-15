package com.ghoststream.feature.onboarding

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostgramlabs.directserve.core.resources.R
import com.ghostgramlabs.directserve.core.resources.ui.GhostSpacing
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
            title = stringResource(R.string.onboarding_page_1_title),
            description = stringResource(R.string.onboarding_page_1_description),
        ),
        OnboardingCard(
            icon = Icons.Outlined.Devices,
            title = stringResource(R.string.onboarding_page_2_title),
            description = stringResource(R.string.onboarding_page_2_description),
        ),
        OnboardingCard(
            icon = Icons.Outlined.QrCode2,
            title = stringResource(R.string.onboarding_page_3_title),
            description = stringResource(R.string.onboarding_page_3_description),
        ),
        OnboardingCard(
            icon = Icons.Outlined.SyncAlt,
            title = stringResource(R.string.onboarding_page_4_title),
            description = stringResource(R.string.onboarding_page_4_description),
        ),
        OnboardingCard(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.onboarding_page_5_title),
            description = stringResource(R.string.onboarding_page_5_description),
        ),
        OnboardingCard(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.onboarding_page_6_title),
            description = stringResource(R.string.onboarding_page_6_description),
        ),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                horizontal = GhostSpacing.screenHorizontal,
                vertical = GhostSpacing.screenVertical,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp),
        ) {
            val compactHeight = maxHeight < 760.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 16.dp else 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.heightIn(min = 48.dp), // Keep the secondary action easy to tap without crowding the header.
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalPager(
                    state = pagerState,
                    pageSpacing = 12.dp, // Add breathing room between pages so each card reads as a separate step.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { page ->
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = ghostPanelColor(),
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), // Subtle lift improves hierarchy without overpowering the content.
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = GhostSpacing.heroCard, vertical = GhostSpacing.heroCard),
                        ) {
                            Surface(
                                modifier = Modifier.size(72.dp),
                                shape = CircleShape,
                                color = ghostAccentSurface(),
                                border = BorderStroke(1.dp, ghostAccentBorder()),
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
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = pages[page].title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = pages[page].description,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.weight(1f)) // Push progress metadata lower to keep the title and body grouped together.
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = ghostAccentSurface(),
                            ) {
                                Text(
                                    text = stringResource(R.string.onboarding_page_counter, page + 1, pages.size),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                            .heightIn(min = 56.dp), // Preserve a comfortable primary action target across device sizes.
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            if (pagerState.currentPage == pages.lastIndex) {
                                stringResource(R.string.onboarding_get_started)
                            } else {
                                stringResource(R.string.onboarding_next)
                            },
                        )
                    }
                }
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
private fun ghostPanelColor() = MaterialTheme.colorScheme.surface

@Composable
private fun ghostAccentSurface() = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)

@Composable
private fun ghostAccentBorder() = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

@Composable
private fun ghostMutedSurface() = MaterialTheme.colorScheme.surfaceVariant
