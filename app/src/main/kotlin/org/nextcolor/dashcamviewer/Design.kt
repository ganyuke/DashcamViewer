@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.nextcolor.dashcamviewer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nextcolor.dashcamviewer.android.ConnectionStage

internal val MainTab.icon: ImageVector
    get() =
        when (this) {
            MainTab.Camera -> AppIcons.Camera
            MainTab.Recordings -> AppIcons.Recordings
            MainTab.Downloads -> AppIcons.Download
            MainTab.Settings -> AppIcons.Settings
        }

@Composable
internal fun DashcamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography =
            Typography(
                headlineLarge =
                    TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        lineHeight = 38.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                headlineMedium =
                    TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp,
                        lineHeight = 34.sp,
                    ),
                titleLarge =
                    TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                    ),
                titleMedium =
                    TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                labelLarge =
                    TextStyle(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    ),
            ),
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
        content = content,
    )
}

@Composable
internal fun ConnectionCard(state: AppUiState) {
    val (title, detail) =
        when (state.connection.stage) {
            ConnectionStage.Connected ->
                "Camera connected" to "WANLIPO A13. Internet access is not required."
            ConnectionStage.RequestingPermission ->
                "Wi-Fi access needed" to "Allow access in the Android permission prompt."
            ConnectionStage.RequestingNetwork ->
                "Connecting to Wi-Fi…" to "Choose your camera in the Android connection prompt."
            ConnectionStage.Identifying ->
                "Checking camera…" to "Wi-Fi is available. Verifying the A13 camera."
            ConnectionStage.Denied ->
                "Wi-Fi access denied" to "Try again and allow access to connect your camera."
            ConnectionStage.Unavailable ->
                (if (state.connection.network != null) "Wi-Fi connected; camera unavailable"
                else "Camera unavailable") to state.connection.detail
            ConnectionStage.VerificationRequired ->
                "Wi-Fi connected" to "Verify the camera to continue."
            ConnectionStage.Lost ->
                "Connection lost" to "Select the camera’s connected Wi-Fi network again."
            ConnectionStage.Disconnecting -> "Disconnecting…" to "Ending the camera connection."
            ConnectionStage.Disconnected ->
                "Not connected" to "Turn on the camera’s Wi-Fi to connect."
        }
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (
                state.connection.stage in
                    setOf(
                        ConnectionStage.RequestingPermission,
                        ConnectionStage.RequestingNetwork,
                        ConnectionStage.Identifying,
                        ConnectionStage.Disconnecting,
                    )
            )
                LoadingIndicator(Modifier.size(36.dp))
            else
                Icon(
                    if (state.connection.stage == ConnectionStage.Connected) AppIcons.Check
                    else if (
                        state.connection.stage in
                            setOf(
                                ConnectionStage.Lost,
                                ConnectionStage.Unavailable,
                                ConnectionStage.Denied,
                            )
                    )
                        AppIcons.Disconnect
                    else AppIcons.Wifi,
                    null,
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun EmptyPanel(icon: ImageVector, title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun String.settingTitle(): String =
    when (this) {
        "rec_resolution" -> "Video resolution"
        "loop_record",
        "rec_split_duration" -> "Loop duration"
        "record_audio",
        "mic" -> "Microphone"
        "gsensor",
        "g_sensor",
        "gsr_sensitivity" -> "Impact sensitivity"
        "parking_monitor" -> "Parking monitor"
        "watermark",
        "osd" -> "Date watermark"
        "key_tone" -> "Camera sounds"
        "light_fre" -> "Light frequency"
        "screen_standby" -> "Screen timeout"
        "language" -> "Language"
        "exposure" -> "Exposure"
        else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

@Composable
internal fun PageHeading(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PrimaryAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun DetailTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun CameraFacts(snapshot: org.nextcolor.dashcamviewer.protocol.CameraSnapshot) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailTile(
            "Recording",
            snapshot.recording?.let { if (it) "On" else "Off" } ?: "Unknown",
            AppIcons.Camera,
            Modifier.weight(1f),
        )
        DetailTile(
            "Current clip",
            snapshot.currentLockStatus?.let { if (it) "Protected" else "Unlocked" } ?: "Unknown",
            AppIcons.Shield,
            Modifier.weight(1f),
        )
    }
}

internal val org.nextcolor.dashcamviewer.protocol.MediaFolder.title: String
    get() =
        when (this) {
            org.nextcolor.dashcamviewer.protocol.MediaFolder.LOOP -> "Loop"
            org.nextcolor.dashcamviewer.protocol.MediaFolder.EVENT -> "Events"
            org.nextcolor.dashcamviewer.protocol.MediaFolder.EMR -> "Emergency"
            org.nextcolor.dashcamviewer.protocol.MediaFolder.PARK -> "Parking"
            org.nextcolor.dashcamviewer.protocol.MediaFolder.RACE -> "Race"
        }

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

@Composable
internal fun MediaFileCard(name: String, detail: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    icon,
                    null,
                    Modifier.padding(14.dp).size(28.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(AppIcons.Chevron, "Open recording")
        }
    }
}
