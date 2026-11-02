package org.nextcolor.dashcamviewer.android

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

enum class ConnectionStage { Disconnected, RequestingPermission, RequestingNetwork, Identifying, Connected, Denied, Unavailable, Lost, Disconnecting }
data class CameraConnectionState(
    val stage: ConnectionStage = ConnectionStage.Disconnected,
    val network: Network? = null,
    val owned: Boolean = false,
    val detail: String = "Disconnected",
    val generation: Long = 0,
    val cameraCapabilities: NetworkCapabilities? = null,
)
data class DefaultNetworkState(val transport: String, val validated: Boolean, val internet: Boolean)
data class ExistingWifi(val network: Network, val validated: Boolean)

/** Local-only Wi-Fi ownership. Does not bind process routing or alter system network settings. */
class CameraNetworkController(context: Context) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wm = app.getSystemService(WifiManager::class.java)
    private val _state = MutableStateFlow(CameraConnectionState())
    val state: StateFlow<CameraConnectionState> = _state.asStateFlow()
    private var cameraCallback: ConnectivityManager.NetworkCallback? = null
    private var activeRequest: CompletableDeferred<Network>? = null
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    private val _defaultState = MutableStateFlow(readDefault())
    val defaultState: StateFlow<DefaultNetworkState> = _defaultState.asStateFlow()

    init {
        defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { refreshDefault() }
            override fun onLost(network: Network) { refreshDefault() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refreshDefault() }
        }.also { cm.registerDefaultNetworkCallback(it) }
    }

    fun existingWifiNetworks(): List<ExistingWifi> = cm.allNetworks.mapNotNull { n ->
        val c = cm.getNetworkCapabilities(n) ?: return@mapNotNull null
        if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) null else ExistingWifi(n, c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }
    fun needsWifiLossConsent(): Boolean {
        return existingWifiNetworks().isNotEmpty() && !wm.isStaConcurrencyForLocalOnlyConnectionsSupported
    }

    suspend fun borrow(network: Network) {
        require(existingWifiNetworks().any { it.network == network }) { "Select an existing Wi-Fi network." }
        releaseCamera()
        val caps = cm.getNetworkCapabilities(network) ?: throw IllegalStateException("Selected network is no longer available.")
        _state.value = CameraConnectionState(ConnectionStage.Identifying, network, false, "Using existing Wi-Fi; checking camera", _state.value.generation + 1, caps)
    }

    suspend fun requestCamera(ssid: String, password: String, consentToPossibleWifiLoss: Boolean) {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        require(ssidBytes.size in 1..32 && ssid.none { it.code < 0x20 || it.code == 0x7f }) { "Enter an exact SSID of 1–32 UTF-8 bytes." }
        require(password.length in 8..63 && password.matches(Regex("[A-Za-z0-9_.~-]+"))) { "Enter a supported WPA2 passphrase." }
        val hasOtherWifi = existingWifiNetworks().isNotEmpty()
        val concurrency = Build.VERSION.SDK_INT >= 31 && wm.isStaConcurrencyForLocalOnlyConnectionsSupported
        if (hasOtherWifi && !concurrency && !consentToPossibleWifiLoss) {
            throw IllegalStateException("Connecting may disconnect your current Wi-Fi. Mobile data may remain available. Confirmation is required.")
        }
        releaseCamera()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val deferred = CompletableDeferred<Network>()
        activeRequest = deferred
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!deferred.isCompleted) deferred.complete(network)
                _state.value = CameraConnectionState(ConnectionStage.Identifying, network, true,
                    if (hasOtherWifi && concurrency) "Camera network acquired; checking whether primary Wi-Fi remains available" else "Camera network acquired; checking camera",
                    _state.value.generation + 1, cm.getNetworkCapabilities(network))
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (_state.value.network == network) _state.value = _state.value.copy(cameraCapabilities = caps)
            }
            override fun onUnavailable() {
                _state.value = _state.value.copy(stage = ConnectionStage.Unavailable, detail = "Camera Wi-Fi request unavailable")
                if (!deferred.isCompleted) deferred.completeExceptionally(IllegalStateException("Camera Wi-Fi unavailable"))
            }
            override fun onLost(network: Network) {
                if (_state.value.network == network) {
                    _state.value = _state.value.copy(stage = ConnectionStage.Lost, detail = "Camera network lost", network = null, generation = _state.value.generation + 1)
                    cameraCallback?.let { lostCallback -> cameraCallback = null; try { cm.unregisterNetworkCallback(lostCallback) } catch (_: IllegalArgumentException) { } }
                }
            }
        }
        cameraCallback = callback
        _state.value = CameraConnectionState(ConnectionStage.RequestingNetwork, owned = true, detail = "Requesting camera Wi-Fi", generation = _state.value.generation)
        try {
            cm.requestNetwork(request, callback)
            withTimeout(30_000) { deferred.await() }
        } catch (t: SecurityException) {
            cleanCallback(callback)
            _state.value = CameraConnectionState(ConnectionStage.Denied, detail = "Wi-Fi permission denied")
            throw t
        } catch (t: TimeoutCancellationException) {
            cleanCallback(callback)
            _state.value = CameraConnectionState(ConnectionStage.Unavailable, detail = "Camera Wi-Fi request timed out")
            throw t
        } catch (t: CancellationException) {
            cleanCallback(callback)
            _state.value = CameraConnectionState(ConnectionStage.Disconnected, detail = "Camera Wi-Fi request cancelled")
            throw t
        } catch (t: Throwable) {
            cleanCallback(callback)
            if (_state.value.stage != ConnectionStage.Unavailable) _state.value = CameraConnectionState(ConnectionStage.Unavailable, detail = "Camera Wi-Fi request did not complete")
            throw t
        } finally {
            if (activeRequest === deferred) activeRequest = null
        }
    }

    fun markIdentified() { if (_state.value.network != null) _state.value = _state.value.copy(stage = ConnectionStage.Connected, detail = "A13 camera connected") }
    fun markIdentityRejected() { _state.value = _state.value.copy(stage = ConnectionStage.Unavailable, detail = "Unsupported device; A13 controls disabled") }
    fun currentNetwork(): Network? = _state.value.network
    fun markRequestingPermission() { _state.value = _state.value.copy(stage = ConnectionStage.RequestingPermission, detail = "Waiting for Android Wi-Fi permission") }
    fun markPermissionDenied() { _state.value = _state.value.copy(stage = ConnectionStage.Denied, detail = "Wi-Fi permission denied") }
    fun markSessionCleared() {
        if (_state.value.network != null) _state.value = _state.value.copy(stage = ConnectionStage.Identifying, detail = "Camera session ended; identify again manually")
    }

    suspend fun disconnect() {
        _state.value = _state.value.copy(stage = ConnectionStage.Disconnecting, detail = "Disconnecting")
        releaseCamera()
    }

    suspend fun releaseOwnedWhenIdle(downloadServiceOwnsLease: Boolean, appBackgrounded: Boolean) {
        if (appBackgrounded && !downloadServiceOwnsLease && _state.value.owned) releaseCamera()
    }

    private suspend fun releaseCamera() {
        activeRequest?.cancel(CancellationException("Camera network request cancelled"))
        activeRequest = null
        val callback = cameraCallback
        cameraCallback = null
        _state.value.network?.let { }
        if (callback != null) cleanCallback(callback)
        _state.value = CameraConnectionState(stage = ConnectionStage.Disconnected, detail = "Disconnected", generation = _state.value.generation + 1)
    }

    private fun cleanCallback(callback: ConnectivityManager.NetworkCallback) {
        if (callback === cameraCallback) cameraCallback = null
        try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { }
    }

    private fun readDefault(): DefaultNetworkState {
        val n = cm.activeNetwork ?: return DefaultNetworkState("No default network", false, false)
        val caps = cm.getNetworkCapabilities(n) ?: return DefaultNetworkState("Unknown", false, false)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        return DefaultNetworkState(transport, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
    }
    private fun refreshDefault() { _defaultState.value = readDefault() }
}
