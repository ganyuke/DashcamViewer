package org.nextcolor.dashcamviewer

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.nextcolor.dashcamviewer.android.*
import org.nextcolor.dashcamviewer.protocol.*

class ConnectionVerificationTest {
    @get:Rule val compose = createComposeRule()
    private val application
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as DashcamApplication

    private class ReadClient(private val read: suspend () -> CameraSnapshot) : A13Client {
        override suspend fun readSnapshot() = read()

        override suspend fun listFiles(folder: MediaFolder, start: Int, limit: Int): MediaPage =
            error("Not used")

        override fun download(file: RemoteMedia, sink: TransferSink): Flow<TransferEvent> =
            error("Not used")

        override fun liveVideo(): Flow<VideoEvent> = error("Not used")

        override suspend fun prepare(command: CameraCommand): PreparedAction = error("Not used")

        override suspend fun execute(
            action: PreparedAction,
            confirmation: ActionConfirmation,
        ): CommandOutcome = error("Not used")
    }

    private fun wifi() =
        application.getSystemService(ConnectivityManager::class.java).let { cm ->
            cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ==
                    true
            }
        }

    private val snapshot
        get() =
            CameraSnapshot(
                CameraIdentity("fixture", "HUAXIN", "eeasytech", "DVR-A13-fixture", "fixture"),
                null,
                emptyList(),
                emptyMap(),
                true,
                emptyMap(),
                null,
                false,
            )

    @Test
    fun verificationTimeoutKeepsWifiAndCanBeRetriedWithoutRejoining() = runBlocking {
        val network = wifi()
        assumeNotNull(network)
        val networks = CameraNetworkController(application)
        var attempts = 0
        val container =
            AppContainer(
                application,
                networks,
                application.container.preferences,
                clientFactory = { _, _, _ ->
                    ReadClient { if (++attempts == 1) awaitCancellation() else snapshot }
                },
                verificationTimeoutMillis = 100,
            )
        var vm: DashcamViewModel? = null
        try {
            compose.runOnIdle {
                vm = DashcamViewModel(application, container)
                vm!!.selectExisting(network)
                vm!!.borrowSelected()
            }
            compose.waitUntil(5_000) {
                vm!!.state.value.connection.stage == ConnectionStage.Unavailable
            }
            assertEquals(network, networks.currentNetwork())
            assertTrue(vm!!.state.value.message!!.contains("timed out"))
            compose.runOnIdle { vm!!.verifyCurrentNetwork() }
            compose.waitUntil(5_000) {
                vm!!.state.value.connection.stage == ConnectionStage.Connected &&
                    vm!!.state.value.snapshot != null
            }
            assertEquals(network, networks.currentNetwork())
            assertEquals(2, attempts)
        } finally {
            compose.runOnIdle {
                vm?.let {
                    ViewModelStore().apply {
                        put("verification", it)
                        clear()
                    }
                }
            }
            networks.close()
        }
    }

    @Test
    fun cancellingVerificationStopsTheSpinnerWithoutDroppingWifi() = runBlocking {
        val network = wifi()
        assumeNotNull(network)
        val networks = CameraNetworkController(application)
        val container =
            AppContainer(
                application,
                networks,
                application.container.preferences,
                clientFactory = { _, _, _ -> ReadClient { awaitCancellation() } },
            )
        var vm: DashcamViewModel? = null
        try {
            compose.runOnIdle {
                vm = DashcamViewModel(application, container)
                vm!!.selectExisting(network)
                vm!!.borrowSelected()
            }
            compose.waitUntil(5_000) {
                vm!!.state.value.connection.stage == ConnectionStage.Identifying
            }
            compose.runOnIdle { vm!!.cancelVerification() }
            compose.waitUntil(5_000) {
                vm!!.state.value.connection.stage == ConnectionStage.VerificationRequired
            }
            assertEquals(network, networks.currentNetwork())
            assertNull(vm!!.state.value.snapshot)
        } finally {
            compose.runOnIdle {
                vm?.let {
                    ViewModelStore().apply {
                        put("verification", it)
                        clear()
                    }
                }
            }
            networks.close()
        }
    }
}
