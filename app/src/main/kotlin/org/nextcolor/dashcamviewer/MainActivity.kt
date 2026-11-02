package org.nextcolor.dashcamviewer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import org.nextcolor.dashcamviewer.android.ConnectionStage
import org.nextcolor.dashcamviewer.protocol.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var model: DashcamViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = androidx.lifecycle.ViewModelProvider(this)[DashcamViewModel::class.java]
        setContent { MaterialTheme { DashcamRoot(model) } }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.onAppForegrounded() }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations && ::model.isInitialized) model.onAppBackgrounded() }
}

@Composable
private fun DashcamRoot(vm: DashcamViewModel) {
    val state by vm.state.collectAsState()
    val transfer by vm.downloadState().collectAsState()
    var showNetworkLoss by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<Pair<String, String>?>(null) }
    var permissionReason by remember { mutableStateOf("") }
    var showNotifyDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val appContainer = (context.applicationContext as DashcamApplication).container

    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(false) }
    var newSsid by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        appContainer.preferences.loadCredentials()?.let { saved ->
            ssid = saved.first; password = saved.second; rememberPassword = true
        }
    }

    fun requestConnect(consented: Boolean = false) {
        val missing = when {
            Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED -> Manifest.permission.NEARBY_WIFI_DEVICES
            Build.VERSION.SDK_INT in 31..32 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> Manifest.permission.ACCESS_FINE_LOCATION
            else -> null
        }
        if (missing != null) { vm.requestingPermission(); permissionReason = missing; return }
        if (!consented && appContainer.networks.needsWifiLossConsent()) { pendingConnect = ssid to password; showNetworkLoss = true; return }
        vm.connect(ssid, password, rememberPassword, consented)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestConnect() else vm.permissionDenied()
    }
    LaunchedEffect(permissionReason) {
        if (permissionReason.isNotEmpty()) {
            permissionLauncher.launch(permissionReason)
            permissionReason = ""
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) { /* service remains active; Android may hide its notification */ }
    }
    val createSample = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runCatching { vm.beginLiveSample(uri) }
    }

    Scaffold(bottomBar = {
        NavigationBar {
            MainTab.entries.forEach { tab ->
                NavigationBarItem(selected = state.tab == tab, onClick = { vm.chooseTab(tab) },
                    icon = { Text(tab.title.take(1)) }, label = { Text(tab.title) })
            }
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            state.message?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }
            when (state.tab) {
                MainTab.Camera -> CameraScreen(vm, state, transfer.active, ssid, { ssid = it }, password, { password = it }, rememberPassword, { rememberPassword = it },
                    { requestConnect() }, { vm.connectionOptions() }, { vm.borrowSelected() }, { vm.selectExisting(it) }, { vm.disconnect() },
                    { showNetworkLoss = false; pendingConnect = null }) { createSample.launch("DashcamViewer-live.h264") }
                MainTab.Recordings -> RecordingsScreen(vm, state, transfer.active) { media ->
                    vm.startDownload(media)
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        showNotifyDialog = true
                    }
                }
                MainTab.Downloads -> DownloadsScreen(vm, state.history, transfer.active, transfer.name, transfer.bytes, transfer.expected, transfer.message)
                MainTab.Settings -> SettingsScreen(vm, state, transfer.active, newSsid, { newSsid = it }, newPassword, { newPassword = it }, repeatedPassword, { repeatedPassword = it })
            }
        }
    }

    if (showNetworkLoss) AlertDialog(
        onDismissRequest = { showNetworkLoss = false; pendingConnect = null },
        title = { Text("Connect to camera Wi-Fi?") },
        text = { Text("Connecting may disconnect your current Wi-Fi. Mobile data may remain available.") },
        confirmButton = { TextButton(onClick = { showNetworkLoss = false; val saved = pendingConnect; pendingConnect = null; if (saved != null) vm.connect(saved.first, saved.second, rememberPassword, true) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { showNetworkLoss = false; pendingConnect = null }) { Text("Cancel") } },
    )
    if (showNotifyDialog) AlertDialog(
        onDismissRequest = { showNotifyDialog = false },
        title = { Text("Download progress notification") },
        text = { Text("DashcamViewer uses an ongoing notification with a Cancel action while this download runs. If notifications are denied, the notification may be hidden; in-app cancellation remains available.") },
        confirmButton = { TextButton(onClick = { showNotifyDialog = false; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") } },
        dismissButton = { TextButton(onClick = { showNotifyDialog = false }) { Text("Continue without notifications") } },
    )
}

@Composable
private fun CameraScreen(
    vm: DashcamViewModel, state: AppUiState, downloading: Boolean,
    ssid: String, onSsid: (String) -> Unit, password: String, onPassword: (String) -> Unit,
    rememberPassword: Boolean, onRemember: (Boolean) -> Unit, onConnect: () -> Unit,
    onFindWifi: () -> Unit, onBorrow: () -> Unit, onSelectWifi: (Network?) -> Unit, onDisconnect: () -> Unit,
    onCancelConsent: () -> Unit, onSaveSample: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Camera connection", style = MaterialTheme.typography.titleLarge)
        Text("${state.connection.stage}: ${state.connection.detail}")
        Text(if (state.connection.owned) "Connection: app requested camera Wi-Fi" else if (state.connection.network != null) "Connection: using already-connected Wi-Fi" else "Connection: none")
        Text("Default network: ${state.defaultNetwork}")
        OutlinedTextField(ssid, onSsid, label = { Text("Exact camera Wi-Fi name (SSID)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, onPassword, label = { Text("WPA2 password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(rememberPassword, onRemember)
            Text("Remember password (encrypted; off by default)")
        }
        if (Build.VERSION.SDK_INT in 31..32) Text("Android 12 may request location permission because its Wi-Fi connection API requires it. DashcamViewer does not use location.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConnect, enabled = state.connection.stage !in setOf(ConnectionStage.RequestingPermission, ConnectionStage.RequestingNetwork, ConnectionStage.Identifying)) { Text("Connect") }
            OutlinedButton(onClick = onFindWifi) { Text("Use already-connected Wi-Fi") }
            if (state.connection.network != null) OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            else if (state.connection.stage == ConnectionStage.RequestingNetwork || state.connection.stage == ConnectionStage.Identifying) {
                OutlinedButton(onClick = onDisconnect) { Text("Cancel connection") }
            }
        }
        if (state.wifiChoices.isNotEmpty()) {
            Text("Choose an existing Wi-Fi network. The app will not disconnect it.")
            state.wifiChoices.forEachIndexed { index, choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.chosenWifi == choice.network, { onSelectWifi(choice.network) })
                    Text("Wi-Fi ${index + 1} — validated internet: ${choice.validated}")
                }
            }
            Button(onClick = onBorrow, enabled = state.chosenWifi != null) { Text("Use selected Wi-Fi") }
        }
        if (state.snapshot != null) {
            val snapshot = state.snapshot
            HorizontalDivider()
            Text("WANLIPO A13", style = MaterialTheme.typography.titleLarge)
            Text("Firmware ${snapshot.identity.firmware} • hardware ${snapshot.identity.hardware}")
            Text("UUID ${snapshot.identity.uuid}")
            Text("Recording: ${snapshot.recording?.let { if (it) "On" else "Off" } ?: "Unknown"}")
            Text("SD card: ${snapshot.sdStatus}")
            Text("Battery: ${snapshot.batteryValue ?: "Unavailable"}")
            Text("Current clip protected: ${snapshot.currentLockStatus?.toString() ?: "Unknown"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::refreshSnapshot) { Text("Refresh status") }
                if (snapshot.recording == true) ConfirmActionButton("Stop recording…", "Stop camera recording?", "Recording will stop. The app will not restart it automatically.", enabled = !downloading) { vm.execute(CameraCommand.StopRecording, ActionConfirmation(true, exactDisplayedValue = "Stop recording")) }
                else Button(onClick = { vm.execute(CameraCommand.StartRecording) }, enabled = !downloading) { Text("Start recording") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.execute(CameraCommand.Snapshot) }, enabled = !downloading) { Text("Take snapshot") }
                ConfirmActionButton("Protect current clip…", "Protect current recording?", "This protects the current recording. It does not select or lock a historical file.", enabled = !downloading && snapshot.recording == true) { vm.execute(CameraCommand.LockCurrentRecording, ActionConfirmation(true, exactDisplayedValue = "Protect current recording")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Camera: ${if (snapshot.currentSettingValues["switchcam"] == 1) "Rear" else "Front"}")
                ConfirmActionButton("Switch front/rear…", "Switch camera view?", "The camera’s actual rear video feed has not been verified.", enabled = !downloading) {
                    val next = if (snapshot.currentSettingValues["switchcam"] == 1) 0 else 1
                    vm.execute(CameraCommand.SwitchCamera(next), ActionConfirmation(true, exactDisplayedValue = if (next == 1) "Rear" else "Front"))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live view", style = MaterialTheme.typography.titleMedium)
                    Text("Video only. Live view requires camera recording to be on and does not change recording state.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.connection.stage == ConnectionStage.Connected && !containerSessionLive(vm)) {
                            Button(onClick = {
                                if (snapshot.recording == true) vm.execute(CameraCommand.StartLive)
                                else vm.execute(CameraCommand.StartRecording, ActionConfirmation(true, exactDisplayedValue = "Start recording for live view"))
                            }, enabled = !downloading) { Text(if (snapshot.recording == true) "Start live" else "Start recording for live…") }
                        } else Button(onClick = vm::stopLive, enabled = !downloading) { Text("Stop live") }
                        if (containerSessionLive(vm)) {
                            OutlinedButton(onClick = onSaveSample) { Text("Save live H.264 sample…") }
                            OutlinedButton(onClick = vm::stopLiveSample) { Text("Stop sample") }
                        }
                    }
                    if (containerSessionLive(vm)) AndroidView(
                        factory = { ctx -> SurfaceView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 520)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { vm.attachLiveSurface(holder.surface) }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { vm.attachLiveSurface(holder.surface) }
                                override fun surfaceDestroyed(holder: SurfaceHolder) { vm.attachLiveSurface(null) }
                            })
                        } }, modifier = Modifier.fillMaxWidth().height(280.dp))
                }
            }
        }
        state.playerUri?.let { PlayerPanel(vm) }
    }
}

// Kept as a read-only lookup so composition itself never starts network work.
@Composable private fun containerSessionLive(vm: DashcamViewModel): Boolean = vm.state.value.connection.stage == ConnectionStage.Connected && (vm.state.value.snapshot != null) && vm.isLiveActive()

@Composable
private fun PlayerPanel(vm: DashcamViewModel) {
    AndroidView(factory = { context -> PlayerView(context).apply { player = vm.player(); layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600) } }, modifier = Modifier.fillMaxWidth().height(280.dp))
    OutlinedButton(onClick = vm::stopPlayback) { Text("Stop playback") }
}

@Composable
private fun RecordingsScreen(vm: DashcamViewModel, state: AppUiState, downloading: Boolean, onDownload: (RemoteMedia) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recordings", style = MaterialTheme.typography.titleLarge)
        Text("Camera timestamps may be wrong. Sizes are approximate camera-reported KiB.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { MediaFolder.entries.forEach { folder -> FilterChip(state.folder == folder, { vm.list(folder, 0) }, label = { Text(folder.name.lowercase().replaceFirstChar(Char::uppercase)) }) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.list() }) { Text("Refresh") }
            OutlinedButton(onClick = { vm.list(start = (state.pageStart - 50).coerceAtLeast(0)) }, enabled = state.pageStart > 0) { Text("Previous") }
            OutlinedButton(onClick = { vm.list(start = state.pageStart + 50) }, enabled = state.media.size == 50) { Text("Next") }
            Text("Entries ${state.pageStart + 1}–${state.pageStart + state.media.size}")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.media, key = { it.exactCameraPath }) { media ->
                ListItem(headlineContent = { Text(media.exactCameraPath.substringAfterLast('/')) },
                    supportingContent = { Text("${media.rawMetadata["time"] ?: media.rawMetadata["date"] ?: "Camera timestamp unavailable"} • ${media.reportedSizeKiB?.let { "~$it KiB" } ?: "size unavailable"}") },
                    modifier = Modifier.clickable { vm.select(media) }, trailingContent = { RadioButton(state.selected == media, { vm.select(media) }) })
            }
        }
        state.selected?.let { media ->
            Text("Selected: ${media.exactCameraPath}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (media.exactCameraPath.endsWith(".jpg", true)) OutlinedButton(onClick = { vm.viewRemoteJpeg(media) }, enabled = !downloading) { Text("View") }
                else OutlinedButton(onClick = { vm.playRemote(media) }, enabled = !downloading) { Text("Play") }
                Button(onClick = { onDownload(media) }, enabled = !downloading) { Text("Download") }
                ExperimentalFileDelete(vm, media, enabled = !downloading)
            }
        }
        state.viewedJpeg?.let { bitmap ->
            Image(bitmap.asImageBitmap(), "Selected dashcam JPEG", modifier = Modifier.fillMaxWidth().height(280.dp).clickable { vm.clearViewedJpeg() })
            TextButton(onClick = vm::clearViewedJpeg) { Text("Close photo") }
        }
        state.playerUri?.let { PlayerPanel(vm) }
    }
}

@Composable
private fun DownloadsScreen(vm: DashcamViewModel, history: List<org.nextcolor.dashcamviewer.android.OwnedDownload>, active: Boolean, name: String?, bytes: Long, expected: Long?, message: String?) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Downloads", style = MaterialTheme.typography.titleLarge)
        if (active) {
            Text("Downloading ${name ?: "camera file"}")
            LinearProgressIndicator(progress = { if (expected != null && expected > 0) (bytes.toFloat() / expected).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text("$bytes bytes${expected?.let { " of $it" } ?: ""}")
            Button(onClick = vm::cancelDownload) { Text("Cancel download") }
        }
        message?.let { Text(it) }
        Text("Completed files created by DashcamViewer", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(history, key = { it.uri }) { item ->
                ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.mimeType) }, trailingContent = {
                    Row {
                        TextButton(onClick = { if (item.mimeType == "image/jpeg") vm.openLocalImage(android.net.Uri.parse(item.uri)) else vm.playLocal(android.net.Uri.parse(item.uri)) }) { Text(if (item.mimeType == "image/jpeg") "View" else "Play") }
                        TextButton(onClick = { vm.shareDownload(android.net.Uri.parse(item.uri)) }) { Text("Share") }
                    }
                })
            }
        }
        vm.state.collectAsState().value.playerUri?.let { PlayerPanel(vm) }
        vm.state.collectAsState().value.viewedJpeg?.let { Image(it.asImageBitmap(), "Downloaded dashcam photo", Modifier.fillMaxWidth().height(240.dp)) }
    }
}

@Composable
private fun SettingsScreen(vm: DashcamViewModel, state: AppUiState, downloading: Boolean, newSsid: String, onNewSsid: (String) -> Unit, newPassword: String, onNewPassword: (String) -> Unit, repeatPassword: String, onRepeatPassword: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Experimental actions remain visible and require a fresh warning acknowledgement every time.")
        state.snapshot?.let { snapshot ->
            Text("Advertised camera settings", style = MaterialTheme.typography.titleMedium)
            snapshot.advertisedSettings.forEach { descriptor ->
                when (descriptor.key) {
                    "switchcam", "rec" -> Unit
                    "rec_resolution" -> {
                        if (descriptor.rawCurrentValue != null && descriptor.rawCurrentValue !in descriptor.optionValues) {
                            Text("Current value: ${descriptor.rawCurrentValue}; camera options inconsistent")
                        }
                        SettingOptions(vm, descriptor, enabled = !downloading && (descriptor.rawCurrentValue == null || descriptor.rawCurrentValue in descriptor.optionValues))
                    }
                    else -> SettingOptions(vm, descriptor, enabled = !downloading && descriptor.key in A13ProtocolClient.WRITABLE_SETTINGS)
                }
            }
        } ?: Text("Connect to the identified A13 to read settings.")
        HorizontalDivider()
        ParkingMonitorSection(state.snapshot, !downloading) { open ->
            val label = if (open) "Open" else "Closed"
            vm.execute(CameraCommand.SetParking(open), ActionConfirmation(true, exactDisplayedValue = label))
        }
        HorizontalDivider()
        Text("Clock (experimental)", style = MaterialTheme.typography.titleMedium)
        ExperimentalClock(vm, downloading)
        HorizontalDivider()
        Text("Camera Wi-Fi (experimental)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(newSsid, onNewSsid, label = { Text("New camera Wi-Fi name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ExperimentalOperation("Change camera Wi-Fi name", "Changing this can disconnect the app; reconnect manually using the new name.", enabled = !downloading && newSsid.isNotBlank()) { acknowledged ->
            vm.execute(CameraCommand.SetWifiName(newSsid), ActionConfirmation(acknowledged, exactDisplayedValue = newSsid))
        }
        OutlinedTextField(newPassword, onNewPassword, label = { Text("New camera Wi-Fi password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(repeatPassword, onRepeatPassword, label = { Text("Repeat new camera Wi-Fi password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        ExperimentalOperation("Change camera Wi-Fi password", "The password must be entered twice. The app may disconnect; reconnect manually with the new password.", enabled = !downloading && newPassword.length in 8..63 && newPassword == repeatPassword) { acknowledged ->
            vm.execute(CameraCommand.SetWifiPassword(newPassword, repeatPassword), ActionConfirmation(acknowledged, exactDisplayedValue = "Set the camera Wi-Fi password"))
        }
        ExperimentalOperation("Restart camera Wi-Fi", "Restarting disconnects the current camera connection. The app will not reconnect automatically.", enabled = !downloading && state.connection.stage == ConnectionStage.Connected) { acknowledged ->
            vm.execute(CameraCommand.RestartWifi, ActionConfirmation(acknowledged, exactDisplayedValue = "Reconnect manually after the camera Wi-Fi restarts"))
        }
        HorizontalDivider()
        Text("Maintenance (experimental)", style = MaterialTheme.typography.titleMedium)
        val recordingOff = state.snapshot?.recording == false
        val maintenanceReady = !downloading && state.connection.stage == ConnectionStage.Connected && recordingOff && !vm.isLiveActive() && !state.playerIsRemote
        Text(if (!recordingOff) "Formatting and reset require recording already confirmed off. Stop recording separately first." else "Formatting/reset also require live, remote playback, and downloads stopped.")
        ExperimentalTypedOperation("Format card", "This erases all camera card recordings. Live, remote playback, and downloads must be stopped; recording must already be off.", "ERASE ALL RECORDINGS", maintenanceReady) { phrase ->
            vm.execute(CameraCommand.FormatCard(phrase), ActionConfirmation(true, phrase))
        }
        ExperimentalTypedOperation("Factory reset", "This resets camera configuration. Live, remote playback, and downloads must be stopped; recording must already be off.", "RESET CAMERA", maintenanceReady) { phrase ->
            vm.execute(CameraCommand.FactoryReset(phrase), ActionConfirmation(true, phrase))
        }
    }
}

@Composable
internal fun ParkingMonitorSection(snapshot: CameraSnapshot?, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Text("Parking monitor", style = MaterialTheme.typography.titleMedium)
    val descriptor = snapshot?.advertisedSettings?.firstOrNull { it.key == "parking_monitor" }
    val currentValues = snapshot?.currentSettingValues.orEmpty()
    if (descriptor == null || "parking_monitor" !in currentValues) {
        Text("Not exposed by the camera in its current state. Use the camera’s physical menu.")
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(false, true).forEach { open ->
                val value = if (open) 1 else 0
                val label = if (open) "Open" else "Closed"
                ConfirmActionButton(label, "Set parking monitor $label?", "Parking behavior depends on camera power and hardware; this app has not verified it.",
                    enabled = enabled && value in descriptor.optionValues && currentValues["parking_monitor"] != value) {
                    onChange(open)
                }
            }
        }
        Text("Parking operation depends on camera power and hardware. This app has not verified that behavior.")
    }
}

@Composable
private fun SettingOptions(vm: DashcamViewModel, descriptor: SettingDescriptor, enabled: Boolean) {
    val current = descriptor.rawCurrentValue
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(descriptor.key, style = MaterialTheme.typography.titleSmall)
        Text("Current: ${current?.let { descriptor.labelFor(it) ?: it.toString() } ?: "Unknown"}")
        if (descriptor.key !in A13ProtocolClient.WRITABLE_SETTINGS) Text("Read only")
        else Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            descriptor.optionValues.zip(descriptor.optionLabels).forEach { (value, label) ->
                FilterChip(selected = current == value, onClick = { vm.execute(CameraCommand.SetSetting(descriptor.key, value)) }, enabled = enabled && current != value, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ExperimentalClock(vm: DashcamViewModel, disabled: Boolean) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyyMMddHHmmss") }
    var show by remember { mutableStateOf(false) }
    var proposed by remember { mutableStateOf<Pair<String, String>?>(null) }
    OutlinedButton(onClick = {
        proposed = LocalDateTime.now().format(formatter) to ZoneId.systemDefault().id
        show = true
    }, enabled = !disabled) { Text("Set camera clock…") }
    if (show && proposed != null) {
        var acknowledged by remember { mutableStateOf(false) }
        val (date, zone) = proposed!!
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Set camera clock?") },
            text = {
                Column {
                    Text("EXPERIMENTAL. This changes future camera timestamps.")
                    Text("Proposed wall time: $date")
                    Text("Time zone: $zone")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, { acknowledged = it })
                        Text("I understand this experimental action")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = acknowledged, onClick = {
                    show = false
                    vm.execute(CameraCommand.SetClock(date, zone), ActionConfirmation(true, exactDisplayedValue = "$date $zone"))
                }) { Text("Set clock") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ExperimentalOperation(title: String, warning: String, enabled: Boolean, onConfirm: (Boolean) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, enabled = enabled) { Text(title) }
    if (show) {
        var acknowledged by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
            Column { Text("EXPERIMENTAL\n$warning"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledged, { acknowledged = it }); Text("I understand this experimental action") } }
        }, confirmButton = { TextButton(enabled = acknowledged, onClick = { show = false; onConfirm(acknowledged) }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ExperimentalTypedOperation(title: String, warning: String, phrase: String, enabled: Boolean, onConfirm: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, enabled = enabled) { Text(title) }
    if (show) {
        var typed by remember { mutableStateOf("") }
        var acknowledged by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
            Column { Text("EXPERIMENTAL\n$warning"); OutlinedTextField(typed, { typed = it }, label = { Text("Type $phrase") }); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledged, { acknowledged = it }); Text("I understand this experimental action") } }
        }, confirmButton = { TextButton(enabled = acknowledged && typed == phrase, onClick = { show = false; onConfirm(typed) }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ConfirmActionButton(title: String, dialogTitle: String, explanation: String, enabled: Boolean, onConfirm: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, enabled = enabled) { Text(title) }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text(dialogTitle) },
        text = { Text(explanation) },
        confirmButton = { TextButton(onClick = { show = false; onConfirm() }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ExperimentalFileDelete(vm: DashcamViewModel, media: RemoteMedia, enabled: Boolean) {
    val filename = media.exactCameraPath.substringAfterLast('/')
    ExperimentalTypedOperation("Delete from camera…", "Deletes exactly this camera file: ${media.exactCameraPath}", filename, enabled) { typed ->
        vm.execute(CameraCommand.DeleteOneFile(media, typed), ActionConfirmation(true, phrase = typed, exactDisplayedValue = filename))
    }
}
