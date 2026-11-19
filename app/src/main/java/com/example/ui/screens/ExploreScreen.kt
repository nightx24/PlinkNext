package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.components.rememberPressScaleModifier
import com.example.ui.theme.LocalPocketLinkColors

private data class ExploreCardItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit,
)

@Composable
fun ExploreScreen(
    onOpenPhotos: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDrives: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    val cards = listOf(
        ExploreCardItem("photos", "Photos", "Picture gallery & favorites", Icons.Rounded.PhotoLibrary, colors.amber, onOpenPhotos),
        ExploreCardItem("music", "Music", "Albums, playlists & audio", Icons.Rounded.Headphones, colors.violet, onOpenMusic),
        ExploreCardItem("videos", "Videos", "Movies & camera clips", Icons.Rounded.PlayCircle, colors.teal, onOpenVideos),
        ExploreCardItem("documents", "Documents", "PDF, Office & text files", Icons.Rounded.Description, colors.sky, onOpenDocuments),
        ExploreCardItem("files", "Files", "Browse server folders", Icons.Rounded.Folder, MaterialTheme.colorScheme.primary, onOpenFiles),
        ExploreCardItem("search", "Search", "Find across all storage", Icons.Rounded.Search, colors.cyan, onOpenSearch),
        ExploreCardItem("drives", "Drives", "Internal & USB drives", Icons.Rounded.Usb, colors.emerald, onOpenDrives),
    )

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Explore",
                subtitle = "Libraries, search & connected storage",
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 128.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("All media & volumes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Stream directly over LAN or Tailscale VPN",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = "${cards.size} hubs",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            itemsIndexed(cards, key = { _, item -> item.id }) { index, card ->
                val interactionSource = remember { MutableInteractionSource() }
                val scaleMod = rememberPressScaleModifier(interactionSource, targetScale = 0.965f)
                AnimatedVisibility(
                    visible = revealed,
                    enter = fadeIn(tween(240, delayMillis = index * 35)) +
                        slideInVertically(
                            initialOffsetY = { 28 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ) +
                        scaleIn(initialScale = 0.94f, animationSpec = tween(220, delayMillis = index * 35)),
                ) {
                    Surface(
                        modifier = Modifier
                            .aspectRatio(1.12f)
                            .then(scaleMod)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = card.onClick,
                            )
                            .testTag("explore_card_${card.id}"),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            card.tint.copy(alpha = 0.06f),
                                            Color.Transparent,
                                        )
                                    )
                                )
                                .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp))
                                .padding(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(15.dp))
                                        .background(card.tint.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(card.icon, contentDescription = null, tint = card.tint, modifier = Modifier.size(25.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                card.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
