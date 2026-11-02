@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.nextcolor.dashcamviewer

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

@Composable
internal fun AppShell(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    message: String?,
    onDismiss: () -> Unit,
    transfer: DownloadUiState = DownloadUiState(),
    content: @Composable (MainTab) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val navigate: (MainTab) -> Unit = { destination ->
        focusManager.clearFocus()
        keyboard?.hide()
        onTab(destination)
    }
    val imeVisible = WindowInsets.isImeVisible
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val rail = maxWidth >= 600.dp && !imeVisible
        val shortLabels = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.2f
        Row(Modifier.fillMaxSize()) {
            if (rail)
                WideNavigationRail(
                    header = {
                        Icon(
                            AppIcons.Camera,
                            "DashcamViewer",
                            Modifier.padding(vertical = 24.dp).size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                ) {
                    MainTab.entries.forEach { destination ->
                        WideNavigationRailItem(
                            railExpanded = false,
                            selected = tab == destination,
                            onClick = { navigate(destination) },
                            icon = { Icon(destination.icon, null) },
                            label = { Text(destination.title) },
                        )
                    }
                }
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!rail && !imeVisible)
                        ShortNavigationBar {
                            MainTab.entries.forEach { destination ->
                                ShortNavigationBarItem(
                                    selected = tab == destination,
                                    onClick = { navigate(destination) },
                                    icon = { Icon(destination.icon, null) },
                                    label = {
                                        Text(
                                            if (shortLabels && destination == MainTab.Recordings)
                                                "Clips"
                                            else if (
                                                shortLabels && destination == MainTab.Downloads
                                            )
                                                "Saved"
                                            else destination.title,
                                            maxLines = 1,
                                            overflow =
                                                androidx.compose.ui.text.style.TextOverflow
                                                    .Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                },
            ) { padding ->
                Box(
                    Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                        if (transfer.active && tab != MainTab.Downloads)
                            Surface(
                                onClick = { onTab(MainTab.Downloads) },
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(AppIcons.Download, null)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Download in progress",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            "View progress or cancel",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Icon(AppIcons.Chevron, null)
                                }
                            }
                        AnimatedVisibility(!message.isNullOrBlank()) {
                            Surface(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        message.orEmpty(),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 4,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = onDismiss) {
                                        Icon(AppIcons.Close, "Dismiss message")
                                    }
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) { content(tab) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdaptiveCameraPanels(
    controls: @Composable () -> Unit,
    preview: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.9f)) { controls() }
                Column(Modifier.weight(1.1f)) { preview() }
            }
        else
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                preview()
                controls()
            }
    }
}
