package org.nextcolor.dashcamviewer.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class NearbyWifi(val ssid: String, val signal: Int)

data class WifiDiscoveryResult(val networks: List<NearbyWifi>, val message: String)

/** User-triggered discovery only. Does not join networks or derive a physical location. */
class WifiDiscovery(context: Context) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)

    @android.annotation.SuppressLint("MissingPermission")
    suspend fun scan(): WifiDiscoveryResult {
        if (
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        )
            return WifiDiscoveryResult(
                emptyList(),
                "Allow precise location access to show Wi-Fi names. You can still enter the name manually.",
            )
        if (!wifi.isWifiEnabled)
            return WifiDiscoveryResult(
                emptyList(),
                "Wi-Fi is off. Turn it on in Android Wi-Fi settings, then search again.",
            )
        if (!app.getSystemService(LocationManager::class.java).isLocationEnabled)
            return WifiDiscoveryResult(
                emptyList(),
                "Android requires Location to be on to show Wi-Fi names. Turn it on in Android settings, or enter the name manually.",
            )
        var scanReceiver: BroadcastReceiver? = null
        val updated =
            try {
                withTimeoutOrNull(10_000) {
                    suspendCancellableCoroutine<Boolean> { continuation ->
                        val receiver =
                            object : BroadcastReceiver() {
                                override fun onReceive(context: Context?, intent: Intent?) {
                                    if (
                                        intent?.action ==
                                            WifiManager.SCAN_RESULTS_AVAILABLE_ACTION &&
                                            continuation.isActive
                                    )
                                        continuation.resume(
                                            intent.getBooleanExtra(
                                                WifiManager.EXTRA_RESULTS_UPDATED,
                                                false,
                                            )
                                        )
                                }
                            }
                        scanReceiver = receiver
                        ContextCompat.registerReceiver(
                            app,
                            receiver,
                            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                            ContextCompat.RECEIVER_EXPORTED,
                        )
                        val requested = runCatching { wifi.startScan() }.getOrDefault(false)
                        if (!requested && continuation.isActive) continuation.resume(false)
                    }
                }
            } finally {
                scanReceiver?.let { runCatching { app.unregisterReceiver(it) } }
                scanReceiver = null
            }
        val names =
            wifi.scanResults
                .mapNotNull { result ->
                    @Suppress("DEPRECATION")
                    val name = result.SSID.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    NearbyWifi(name, result.level)
                }
                .groupBy { it.ssid }
                .map { (_, values) -> values.maxBy { it.signal } }
                .sortedByDescending { it.signal }
        return WifiDiscoveryResult(
            names,
            when {
                names.isEmpty() ->
                    "No Wi-Fi networks found. Make sure the camera’s Wi-Fi is on and nearby, then search again. Hidden networks can be entered manually."
                updated != true -> "Android limited the scan. Showing recent Wi-Fi results."
                else -> "Select the camera’s Wi-Fi name."
            },
        )
    }
}
