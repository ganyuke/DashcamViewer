package org.nextcolor.dashcamviewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.ui.PlayerView

@Composable
internal fun MediaViewer(
    state: AppUiState,
    player: androidx.media3.common.Player?,
    onClose: () -> Unit,
) {
    if (
        state.playerUri == null &&
            state.viewedJpeg == null &&
            !state.openingMedia &&
            state.playbackError == null
    )
        return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.playerUri == null) "Photo" else "Video",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onClose) { Icon(AppIcons.Close, "Close media") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    state.viewedJpeg?.let {
                        Image(it.asImageBitmap(), "Dashcam photo", Modifier.fillMaxSize())
                    }
                    if (state.playerUri != null && state.playbackError == null)
                        AndroidView(
                            factory = { context ->
                                PlayerView(context).apply { this.player = player }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    if (state.openingMedia || state.playbackLoading)
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(
                                Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(if (state.openingMedia) "Opening photo…" else "Loading video…")
                                TextButton(onClick = onClose) { Text("Cancel") }
                            }
                        }
                    state.playbackError?.let { error ->
                        Column(
                            Modifier.fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(AppIcons.Info, null, Modifier.size(48.dp))
                            Text(
                                "Could not open media",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(error, style = MaterialTheme.typography.bodyMedium)
                            FilledTonalButton(onClick = onClose) { Text("Back to files") }
                        }
                    }
                }
            }
        }
    }
}
