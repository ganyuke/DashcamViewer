package org.nextcolor.dashcamviewer

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.nextcolor.dashcamviewer.android.CameraConnectionState
import org.nextcolor.dashcamviewer.android.ConnectionStage
import org.nextcolor.dashcamviewer.android.OwnedDownload
import org.nextcolor.dashcamviewer.protocol.*

class DesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val application
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application

    private val identity =
        CameraIdentity("fixture", "HUAXIN", "eeasytech", "DVR-A13-20250808", "A13")
    private val snapshot
        get() =
            CameraSnapshot(
                identity,
                null,
                listOf(
                    SettingDescriptor(
                        "rec_resolution",
                        listOf("1080p", "1440p", "4K"),
                        listOf(0, 1, 2),
                        1,
                    ),
                    SettingDescriptor("mic", listOf("Off", "On"), listOf(0, 1), 1),
                    SettingDescriptor("parking_monitor", listOf("Closed", "Open"), listOf(0, 1), 0),
                ),
                mapOf("rec_resolution" to 1, "mic" to 1, "parking_monitor" to 0),
                true,
                mapOf("status" to "Ready"),
                "External power",
                false,
            )

    private val connected
        get() =
            AppUiState(
                connection = CameraConnectionState(stage = ConnectionStage.Connected),
                snapshot = snapshot,
            )

    @Composable
    private fun Camera(vm: DashcamViewModel, state: AppUiState, disconnect: () -> Unit = {}) {
        CameraScreen(
            vm,
            state,
            false,
            "A13_CAMERA",
            {},
            "12345678",
            {},
            false,
            {},
            {},
            {},
            {},
            {},
            disconnect,
            {},
        )
    }

    @Test
    fun choosingAnSsidSuggestionFillsTheEditableNetworkField() {
        var selected by mutableStateOf("")
        var open by mutableStateOf(false)
        compose.setContent {
            DashcamTheme {
                NetworkNameField(
                    selected,
                    { selected = it },
                    listOf("WANLIPO_A13_9C42", "Home Wi-Fi"),
                    suggestionsOpen = open,
                    onSuggestionsOpenChange = { open = it },
                )
            }
        }
        compose.onNodeWithText("Camera Wi-Fi name").performClick()
        compose.onNodeWithText("WANLIPO_A13_9C42").performClick()
        assertEquals("WANLIPO_A13_9C42", selected)
        compose.onNodeWithText("WANLIPO_A13_9C42").assertIsDisplayed()
    }

    @Test
    fun connectedNetworkPickerUsesActualNamesAndExplicitSelection() {
        val camera = android.net.Network.fromNetworkHandle(0x1cafed00dL)
        val home = android.net.Network.fromNetworkHandle(0x2cafed00dL)
        var selected by mutableStateOf<android.net.Network?>(null)
        var checks = 0
        compose.setContent {
            DashcamTheme {
                ExistingWifiSection(
                    listOf(
                        org.nextcolor.dashcamviewer.android.ExistingWifi(
                            camera,
                            false,
                            "WANLIPO_A13_9C42",
                        ),
                        org.nextcolor.dashcamviewer.android.ExistingWifi(
                            home,
                            true,
                            "Home Wi-Fi",
                            true,
                        ),
                    ),
                    selected,
                    true,
                    { selected = it },
                    { checks++ },
                    {},
                )
            }
        }
        compose.onNodeWithText("WANLIPO_A13_9C42").assertIsDisplayed().performClick()
        compose.onNodeWithText("Home Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("Check selected network for camera").performClick()
        assertEquals(camera, selected)
        assertEquals(1, checks)
        compose.onAllNodesWithText("Wi-Fi 1").assertCountEquals(0)
        compose.onAllNodesWithText("Wi-Fi 2").assertCountEquals(0)
    }

    @Test
    fun photoViewerHasAnAccessibleCloseAction() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        var state by mutableStateOf(AppUiState(viewedJpeg = bitmap))
        compose.setContent { DashcamTheme { MediaViewer(state, null) { state = AppUiState() } } }
        compose.onNodeWithContentDescription("Dashcam photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close media").performClick()
        compose.onNodeWithContentDescription("Dashcam photo").assertDoesNotExist()
        bitmap.recycle()
    }

    @Test
    fun disconnectIsVisibleAndClickableAtLargeFontOnCompactScreen() {
        val vm = DashcamViewModel(application)
        var disconnects = 0
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, 1.5f)
            ) {
                DashcamTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        Camera(vm, connected) { disconnects++ }
                    }
                }
            }
        }
        compose
            .onNodeWithText("Disconnect camera")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, disconnects)
        val button = compose.onNodeWithText("Disconnect camera").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(button.left >= root.left && button.right <= root.right)
    }

    @Test
    fun disconnectedSetupHasPasswordVisibilityAndDoesNotConnectOnReveal() {
        val vm = DashcamViewModel(application)
        compose.setContent { DashcamTheme { Camera(vm, AppUiState()) } }
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        compose.onNodeWithText("Not connected").assertExists()
    }

    @Test
    fun navigationKeepsEveryDestinationReachable() {
        var selected by mutableStateOf(MainTab.Camera)
        compose.setContent {
            DashcamTheme {
                AppShell(selected, { selected = it }, null, {}) { Text("Destination: ${it.title}") }
            }
        }
        MainTab.entries.forEach { tab ->
            compose.onNodeWithText(tab.title).performClick()
            compose.onNodeWithText("Destination: ${tab.title}").assertIsDisplayed()
        }
    }

    @Test
    fun renderLightAndDarkScreensForVisualReview() {
        val vm = DashcamViewModel(application)
        var page by mutableStateOf(0)
        var dark by mutableStateOf(false)
        val media =
            RemoteMedia::class
                .java
                .declaredConstructors
                .first()
                .newInstance(
                    identity,
                    "/DCIM/LOOP/20261102_082430_FRONT_000001.MP4",
                    MediaFolder.LOOP,
                    mapOf("time" to "Nov 2, 2026 · 8:24 AM"),
                    184320L,
                ) as RemoteMedia
        compose.setContent {
            DashcamTheme(darkTheme = dark) {
                AppShell(if (page >= 4) MainTab.Camera else MainTab.entries[page], {}, null, {}) {
                    key(page) {
                        when (page) {
                            0 -> Camera(vm, AppUiState())
                            1 ->
                                RecordingsScreen(
                                    vm,
                                    connected.copy(media = listOf(media)),
                                    false,
                                ) {}
                            2 ->
                                DownloadsScreen(
                                    vm,
                                    listOf(
                                        OwnedDownload(
                                            "content://fixture/video",
                                            "20261102_082430_FRONT_000001.MP4",
                                            "video/mp4",
                                            0,
                                        )
                                    ),
                                    true,
                                    "20261102_082430_FRONT_000001.MP4",
                                    42_000_000,
                                    188_000_000,
                                    null,
                                )
                            3 -> SettingsScreen(vm, connected, false, "", {}, "", {}, "", {})
                            4 -> Camera(vm, connected)
                            else ->
                                Camera(
                                    vm,
                                    AppUiState(
                                        connection =
                                            CameraConnectionState(stage = ConnectionStage.Lost)
                                    ),
                                )
                        }
                    }
                }
            }
        }
        val output = File(application.getExternalFilesDir(null), "design-review").apply { mkdirs() }
        for (night in listOf(false, true)) {
            for (screen in 0..5) {
                compose.runOnIdle {
                    dark = night
                    page = screen
                }
                compose.waitForIdle()
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(
                        output,
                        "${if (night) "dark" else "light"}-${listOf("setup", "recordings", "downloads", "settings", "connected", "lost")[screen]}.png",
                    )
                    .outputStream()
                    .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
