package org.nextcolor.dashcamviewer

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.nextcolor.dashcamviewer.android.*
import org.nextcolor.dashcamviewer.protocol.*

class WorkflowUiTest {
    @get:Rule val compose = createComposeRule()
    private val application
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as DashcamApplication

    private fun captureReview(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // UiAutomation reads the rendered window, after Compose semantics and the render thread
        // settle.
        Thread.sleep(200)
        val folder =
            File(application.getExternalFilesDir(null), "workflow-review").apply { mkdirs() }
        val bitmap =
            requireNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private val identity =
        CameraIdentity("fixture", "HUAXIN", "eeasytech", "DVR-A13-20250808", "A13")

    private fun media() =
        RemoteMedia::class
            .java
            .declaredConstructors
            .first()
            .newInstance(
                identity,
                "/DCIM/LOOP/ux-audit.mp4",
                MediaFolder.LOOP,
                emptyMap<String, String>(),
                1L,
            ) as RemoteMedia

    private class DownloadClient(private val block: suspend (TransferSink) -> Unit) : A13Client {
        override fun download(file: RemoteMedia, sink: TransferSink) = flow {
            block(sink)
            emit(TransferEvent(12, 12, true))
        }

        override suspend fun readSnapshot(): CameraSnapshot = error("Not used")

        override suspend fun listFiles(folder: MediaFolder, start: Int, limit: Int): MediaPage =
            error("Not used")

        override fun liveVideo(): Flow<VideoEvent> = error("Not used")

        override suspend fun prepare(command: CameraCommand): PreparedAction = error("Not used")

        override suspend fun execute(
            action: PreparedAction,
            confirmation: ActionConfirmation,
        ): CommandOutcome = error("Not used")
    }

    private val discardSink =
        object : TransferSink {
            override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {}

            override suspend fun commit() {}

            override suspend fun rollback() {}
        }

    @Test
    fun returningToCameraDoesNotReopenSelectedSsidSuggestions() {
        val vm = DashcamViewModel(application)
        compose.runOnIdle { vm.setWifiSuggestionsOpen(true) }
        compose.setContent {
            val state by vm.state.collectAsState()
            val credentials by vm.credentials.collectAsState()
            DashcamTheme {
                AppShell(state.tab, vm::chooseTab, null, {}) { tab ->
                    if (tab == MainTab.Camera)
                        NetworkNameField(
                            credentials.ssid,
                            vm::editNetworkName,
                            listOf("A13_CAMERA", "HOME_WIFI"),
                            suggestionsOpen = state.wifiSuggestionsOpen,
                            onSuggestionsOpenChange = vm::setWifiSuggestionsOpen,
                        )
                    else Text("Files")
                }
            }
        }
        compose.onNodeWithText("A13_CAMERA").performClick()
        compose.onNodeWithText("HOME_WIFI").assertDoesNotExist()
        compose.onNodeWithText("Recordings").performClick()
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("HOME_WIFI").assertDoesNotExist()
        compose.onNodeWithText("A13_CAMERA").assertExists()
        assertFalse(vm.state.value.wifiSuggestionsOpen)
    }

    @Test
    fun localPlaybackStaysOpenAndPausedWhenTheAppIsBackgrounded() {
        var vm: DashcamViewModel? = null
        val uri = Uri.parse("content://org.nextcolor.dashcamviewer.missing/local-video")
        compose.runOnIdle {
            vm = DashcamViewModel(application)
            vm!!.playLocal(uri)
        }
        val original = vm!!.player()
        try {
            val stopped = compose.runOnIdle { vm!!.onAppBackgrounded() }
            runBlocking { stopped.join() }
            compose.runOnIdle {
                assertSame(original, vm!!.player())
                assertEquals(uri, vm!!.state.value.playerUri)
                assertFalse(vm!!.player()!!.playWhenReady)
            }
        } finally {
            compose.runOnIdle { vm!!.viewModelScopeForTestClear() }
        }
    }

    @Test
    fun livePreviewExplainsWaitingAndStalledVideo() {
        val vm = DashcamViewModel(application)
        var state by
            mutableStateOf(
                AppUiState(
                    connection = CameraConnectionState(stage = ConnectionStage.Connected),
                    liveActive = true,
                )
            )
        compose.setContent { DashcamTheme { LiveViewPanel(vm, state, false, {}) } }
        compose.onNodeWithText("Waiting for live video…").assertIsDisplayed()
        captureReview("live-waiting")
        compose.runOnIdle {
            state = state.copy(liveIssue = "Live video has stalled. Stop live view and try again.")
        }
        compose
            .onNodeWithText("Live video has stalled. Stop live view and try again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Stop live view").assertIsDisplayed()
        captureReview("live-stalled")
    }

    @Test
    fun anUnavailableVideoReportsFailureInsteadOfLeavingABlankPlayer() {
        var vm: DashcamViewModel? = null
        compose.runOnIdle {
            vm = DashcamViewModel(application)
            vm!!.playLocal(Uri.parse("content://org.nextcolor.dashcamviewer.missing/video"))
        }
        compose.setContent {
            val state by vm!!.state.collectAsState()
            DashcamTheme { MediaViewer(state, vm!!.player(), vm!!::closeMedia) }
        }
        try {
            compose.waitUntil(10_000) { vm!!.state.value.playbackError != null }
            compose.onNodeWithText("Could not open media").assertIsDisplayed()
            captureReview("video-error")
            compose.onNodeWithText("Back to files").performScrollTo().performClick()
            compose.onNodeWithText("Could not open media").assertDoesNotExist()
        } finally {
            compose.runOnIdle { vm!!.viewModelScopeForTestClear() }
        }
    }

    @Test
    fun notificationChoiceDoesNotHideDownloadProgress() {
        var requests = 0
        val vm = DashcamViewModel(application)
        compose.setContent {
            DashcamTheme {
                DownloadsScreen(
                    vm,
                    emptyList(),
                    true,
                    "drive.mp4",
                    512,
                    1024,
                    null,
                    onEnableNotifications = { requests++ },
                )
            }
        }
        compose.onNodeWithText("Cancel download").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        assertEquals(1, requests)
    }

    @Test
    fun completedDownloadPublishesToTheVisibleHistoryWithoutRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(application.cacheDir, "audit-${UUID.randomUUID()}.preferences_pb")
        val prefs =
            PrivatePreferences(
                application,
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
            )
        val container = AppContainer(application, application.container.networks, prefs)
        var vm: DashcamViewModel? = null
        var savedUri: Uri? = null
        try {
            compose.runOnIdle { vm = DashcamViewModel(application, container) }
            val sink =
                MediaStoreTransferSink(
                    application,
                    "audit-${UUID.randomUUID()}.mp4",
                    12,
                    {},
                    { item ->
                        savedUri = Uri.parse(item.uri)
                        prefs.appendHistory(item)
                    },
                )
            savedUri = sink.pendingUri()
            val clip = media()
            assertTrue(container.reserveDownload(clip))
            container.transferToSink(
                clip,
                DownloadClient { output ->
                    output.write(byteArrayOf(0, 0, 0, 12, 102, 116, 121, 112, 105, 115, 111, 109))
                    output.commit()
                },
                sink,
            )
            compose.waitUntil(5_000) {
                vm!!.state.value.history.any { it.uri == savedUri.toString() }
            }
            assertFalse(container.downloadState.value.active)
            assertEquals("Download completed", container.downloadState.value.message)
        } finally {
            savedUri?.let { application.contentResolver.delete(it, null, null) }
            vm?.viewModelScopeForTestClear()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun failedAndCancelledTransfersKeepTheirFinalFeedback() = runBlocking {
        val container =
            AppContainer(
                application,
                application.container.networks,
                application.container.preferences,
            )
        val clip = media()
        assertTrue(container.reserveDownload(clip))
        try {
            container.transferToSink(
                clip,
                DownloadClient { error("Camera disconnected") },
                discardSink,
            )
        } catch (_: IllegalStateException) {}
        assertEquals("Download failed: Camera disconnected", container.downloadState.value.message)
        assertFalse(container.hasActiveDownload())
        assertTrue(container.reserveDownload(clip))
        val started = CompletableDeferred<Unit>()
        val job = launch {
            container.transferToSink(
                clip,
                DownloadClient {
                    started.complete(Unit)
                    awaitCancellation()
                },
                discardSink,
            )
        }
        started.await()
        job.cancelAndJoin()
        assertEquals("Download cancelled", container.downloadState.value.message)
        assertFalse(container.hasActiveDownload())
    }

    @Test
    fun activeDownloadIsReachableFromOtherTabs() {
        var tab by mutableStateOf(MainTab.Camera)
        compose.setContent {
            DashcamTheme {
                AppShell(
                    tab,
                    { tab = it },
                    null,
                    {},
                    transfer = DownloadUiState(active = true, name = "drive.mp4"),
                ) {
                    Text("Destination: ${it.title}")
                }
            }
        }
        compose.onNodeWithText("Download in progress").assertIsDisplayed().performClick()
        compose.onNodeWithText("Destination: Downloads").assertIsDisplayed()
    }

    @Test
    fun photoLoadingHasACancelAction() {
        var state by mutableStateOf(AppUiState(openingMedia = true))
        compose.setContent { DashcamTheme { MediaViewer(state, null) { state = AppUiState() } } }
        compose.onNodeWithText("Opening photo…").assertIsDisplayed()
        captureReview("photo-loading")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Opening photo…").assertDoesNotExist()
    }

    @Test
    fun playbackFailureHasAnExplicitWayBackToFiles() {
        var state by
            mutableStateOf(
                AppUiState(
                    playerUri = Uri.parse("content://missing/video"),
                    playbackError = "This file is unavailable.",
                )
            )
        compose.setContent { DashcamTheme { MediaViewer(state, null) { state = AppUiState() } } }
        compose.onNodeWithText("Could not open media").assertIsDisplayed()
        compose.onNodeWithText("Back to files").performScrollTo().performClick()
        compose.onNodeWithText("Could not open media").assertDoesNotExist()
    }

    @Test
    fun recordingsFailureIsNotPresentedAsAnEmptyFolder() {
        val vm = DashcamViewModel(application)
        compose.setContent {
            DashcamTheme {
                RecordingsScreen(
                    vm,
                    AppUiState(
                        connection = CameraConnectionState(stage = ConnectionStage.Connected),
                        mediaListError = "Camera request timed out",
                        mediaListLoaded = true,
                    ),
                    false,
                ) {}
            }
        }
        compose.onNodeWithText("Could not load recordings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
        captureReview("recordings-error")
        compose.onNodeWithText("No recordings in this folder").assertDoesNotExist()
    }
}

private fun DashcamViewModel.viewModelScopeForTestClear() {
    androidx.lifecycle.ViewModelStore().also { store ->
        store.put("audit", this)
        store.clear()
    }
}

class ConnectionDraftUiTest {
    @get:Rule
    val activity = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()

    @Test
    fun changingNetworksClearsThePreviousPassword() {
        activity
            .onNodeWithText("Camera Wi-Fi name")
            .performScrollTo()
            .performTextReplacement("A13_FIRST")
        activity
            .onNodeWithText("Wi-Fi password")
            .performScrollTo()
            .performTextInput("test-password")
        activity
            .onNodeWithText("Camera Wi-Fi name")
            .performScrollTo()
            .performTextReplacement("A13_SECOND")
        val password =
            activity
                .onNodeWithText("Wi-Fi password")
                .performScrollTo()
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text
        assertEquals("", password)
    }

    @Test
    fun networkNameSurvivesActivityRecreation() {
        activity.onNodeWithText("Camera Wi-Fi name").performScrollTo().performTextClearance()
        activity.onNodeWithText("Camera Wi-Fi name").performTextInput("A13_ROTATION_TEST")
        activity.activityRule.scenario.recreate()
        activity
            .onNodeWithText("Camera Wi-Fi name")
            .performScrollTo()
            .assertTextContains("A13_ROTATION_TEST")
    }
}
