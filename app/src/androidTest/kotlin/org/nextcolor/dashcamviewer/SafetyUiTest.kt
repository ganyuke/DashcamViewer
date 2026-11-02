package org.nextcolor.dashcamviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.nextcolor.dashcamviewer.protocol.CameraIdentity
import org.nextcolor.dashcamviewer.protocol.CameraSnapshot
import org.nextcolor.dashcamviewer.protocol.SettingDescriptor

@RunWith(AndroidJUnit4::class)
class SafetyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun missingParkingApiShowsPhysicalMenuMessageAndNoWriteControls() {
        val snapshot =
            CameraSnapshot(
                CameraIdentity("fixture", "HUAXIN", "eeasytech", "DVR-A13-20250808", "fixture"),
                null,
                emptyList(),
                emptyMap(),
                true,
                emptyMap(),
                null,
                false,
            )
        var writes = 0
        compose.setContent { MaterialTheme { ParkingMonitorSection(snapshot, true) { writes++ } } }
        compose
            .onAllNodesWithText(
                "Not exposed by the camera in its current state. Use the camera’s physical menu."
            )
            .assertCountEquals(1)
        compose.onAllNodesWithText("Open").assertCountEquals(0)
        compose.onAllNodesWithText("Closed").assertCountEquals(0)
        assertEquals(0, writes)
    }

    @Test
    fun parkingWriteRequiresFreshConfirmationAndAdvertisedOption() {
        val identity =
            CameraIdentity("fixture", "HUAXIN", "eeasytech", "DVR-A13-20250808", "fixture")
        val descriptor =
            SettingDescriptor("parking_monitor", listOf("Closed", "Open"), listOf(0, 1), 0)
        val snapshot =
            CameraSnapshot(
                identity,
                null,
                listOf(descriptor),
                mapOf("parking_monitor" to 0),
                true,
                emptyMap(),
                null,
                false,
            )
        var writes = 0
        compose.setContent { MaterialTheme { ParkingMonitorSection(snapshot, true) { writes++ } } }
        compose.onNodeWithText("Open").performClick()
        compose
            .onAllNodesWithText(
                "Parking behavior depends on camera power and hardware; this app has not verified it."
            )
            .assertCountEquals(1)
        compose.onAllNodesWithText("Set parking monitor Open?").assertCountEquals(1)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, writes)
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(1, writes)
    }

    @Test
    fun experimentalWarningStartsUncheckedAndCancelSendsNothing() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                ExperimentalOperation("Reset camera…", "Reset warning", enabled = true) { calls++ }
            }
        }
        compose.onNodeWithText("Reset camera…").performClick()
        compose.onAllNodesWithText("I understand this experimental action").assertCountEquals(1)
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, calls)
    }
}

@RunWith(AndroidJUnit4::class)
class NavigationTabsTest {
    @get:Rule val activity = createAndroidComposeRule<MainActivity>()

    @Test
    fun displaysExactlyTheFourSpecifiedTabs() {
        listOf("Camera", "Recordings", "Downloads", "Settings").forEach {
            activity.onNode(hasText(it) and isSelectable()).assertExists()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ConnectionStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun connectedStatusExplainsThatInternetIsNotRequired() {
        compose.setContent {
            DashcamTheme {
                ConnectionCard(
                    AppUiState(
                        connection =
                            org.nextcolor.dashcamviewer.android.CameraConnectionState(
                                stage =
                                    org.nextcolor.dashcamviewer.android.ConnectionStage.Connected
                            )
                    )
                )
            }
        }
        compose.onNodeWithText("Camera connected").assertExists()
        compose.onNodeWithText("WANLIPO A13. Internet access is not required.").assertExists()
    }

    @Test
    fun lostNetworkReplacesConnectedStatus() {
        val state =
            androidx.compose.runtime.mutableStateOf(
                AppUiState(
                    connection =
                        org.nextcolor.dashcamviewer.android.CameraConnectionState(
                            stage = org.nextcolor.dashcamviewer.android.ConnectionStage.Connected
                        )
                )
            )
        compose.setContent { DashcamTheme { ConnectionCard(state.value) } }
        compose.onNodeWithText("Camera connected").assertExists()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    connection =
                        state.value.connection.copy(
                            stage = org.nextcolor.dashcamviewer.android.ConnectionStage.Lost
                        )
                )
        }
        compose.onNodeWithText("Connection lost").assertExists()
        compose.onAllNodesWithText("Camera connected").assertCountEquals(0)
    }
}
