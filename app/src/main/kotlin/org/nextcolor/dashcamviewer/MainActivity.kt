@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.nextcolor.dashcamviewer.android.CameraWifiCredentials
import org.nextcolor.dashcamviewer.android.ConnectionStage
import org.nextcolor.dashcamviewer.protocol.*

class MainActivity : ComponentActivity() {
    private lateinit var model: DashcamViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = androidx.lifecycle.ViewModelProvider(this)[DashcamViewModel::class.java]
        if (intent.getBooleanExtra(DownloadService.EXTRA_OPEN_DOWNLOADS, false))
            model.chooseTab(MainTab.Downloads)
        setContent { DashcamTheme { DashcamRoot(model) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(DownloadService.EXTRA_OPEN_DOWNLOADS, false))
            model.chooseTab(MainTab.Downloads)
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) model.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // Android's permission and Wi-Fi chooser activities can cover this activity.
        // Keep their in-flight request alive until Android returns a result.
        if (
            !isChangingConfigurations &&
                ::model.isInitialized &&
                model.state.value.connection.stage !in
                    setOf(
                        ConnectionStage.RequestingPermission,
                        ConnectionStage.RequestingNetwork,
                        ConnectionStage.Identifying,
                    )
        ) {
            model.onAppBackgrounded()
        }
    }
}

@Composable
private fun DashcamRoot(vm: DashcamViewModel) {
    val state by vm.state.collectAsState()
    val transfer by vm.downloadState().collectAsState()
    var showDisconnectTransfer by rememberSaveable { mutableStateOf(false) }
    var showNetworkLoss by rememberSaveable { mutableStateOf(false) }
    var showDiscoveryPermission by rememberSaveable { mutableStateOf(false) }
    var permissionForWifi by rememberSaveable { mutableStateOf(false) }
    var permissionReason by remember { mutableStateOf("") }
    val context = LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val appContainer = (context.applicationContext as DashcamApplication).container

    val credentials by vm.credentials.collectAsState()
    val ssid = credentials.ssid
    val password = credentials.password
    val rememberPassword = credentials.remember
    var newSsid by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.observeConnectedWifi() }

    fun requestConnect(consented: Boolean = false) {
        val missing =
            when {
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    ) != PackageManager.PERMISSION_GRANTED ->
                    Manifest.permission.NEARBY_WIFI_DEVICES
                Build.VERSION.SDK_INT in 31..32 &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) != PackageManager.PERMISSION_GRANTED ->
                    Manifest.permission.ACCESS_FINE_LOCATION
                else -> null
            }
        if (missing != null) {
            vm.requestingPermission()
            permissionReason = missing
            return
        }
        if (!consented && appContainer.networks.needsWifiLossConsent()) {
            vm.pendingConnectionCredentials = ssid to password
            showNetworkLoss = true
            return
        }
        vm.connect(ssid, password, rememberPassword, consented)
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted ->
            if (granted.isNotEmpty() && granted.values.all { it }) {
                if (permissionForWifi) vm.discoverWifi() else requestConnect()
            } else if (permissionForWifi) vm.discoveryPermissionDenied() else vm.permissionDenied()
        }
    LaunchedEffect(permissionReason) {
        if (permissionReason.isNotEmpty()) {
            permissionLauncher.launch(
                if (permissionReason == Manifest.permission.ACCESS_FINE_LOCATION)
                    buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (permissionForWifi && Build.VERSION.SDK_INT >= 33)
                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }
                        .toTypedArray()
                else arrayOf(permissionReason)
            )
            permissionReason = ""
        }
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted
        }

    val createSample =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri != null) vm.beginLiveSample(uri)
        }

    fun findWifi() {
        permissionForWifi = true
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED -> showDiscoveryPermission = true
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ) != PackageManager.PERMISSION_GRANTED -> {
                permissionReason = Manifest.permission.NEARBY_WIFI_DEVICES
            }
            else -> vm.discoverWifi()
        }
    }
    if (showDiscoveryPermission)
        AlertDialog(
            onDismissRequest = { showDiscoveryPermission = false },
            title = { Text("Show nearby Wi-Fi names") },
            text = {
                Text(
                    "Android requires precise location permission to list Wi-Fi names, including your camera’s. DashcamViewer uses this access only for Wi-Fi discovery and does not calculate or store your physical location. You can also enter the network name manually."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscoveryPermission = false
                        permissionReason = Manifest.permission.ACCESS_FINE_LOCATION
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscoveryPermission = false }) {
                    Text("Enter name manually")
                }
            },
        )

    AppShell(state.tab, vm::chooseTab, state.message, vm::dismissMessage, transfer = transfer) {
        currentTab ->
        when (currentTab) {
            MainTab.Camera ->
                CameraScreen(
                    vm,
                    state,
                    transfer.active,
                    ssid,
                    vm::editNetworkName,
                    password,
                    vm::editNetworkPassword,
                    rememberPassword,
                    vm::rememberNetworkPassword,
                    {
                        permissionForWifi = false
                        requestConnect()
                    },
                    { findWifi() },
                    { vm.borrowSelected() },
                    { vm.selectExisting(it) },
                    { if (transfer.active) showDisconnectTransfer = true else vm.disconnect() },
                ) {
                    createSample.launch("DashcamViewer-live.h264")
                }
            MainTab.Recordings -> RecordingsScreen(vm, state, transfer.active, vm::startDownload)
            MainTab.Downloads ->
                DownloadsScreen(
                    vm,
                    state.history,
                    transfer.active,
                    transfer.name,
                    transfer.bytes,
                    transfer.expected,
                    transfer.message,
                    onEnableNotifications =
                        if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33)
                            ({
                                notificationPermission.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            })
                        else null,
                )
            MainTab.Settings ->
                SettingsScreen(
                    vm,
                    state,
                    transfer.active,
                    newSsid,
                    { newSsid = it },
                    newPassword,
                    { newPassword = it },
                    repeatedPassword,
                    { repeatedPassword = it },
                )
        }
    }

    MediaViewer(state, vm.player(), vm::closeMedia)

    if (showDisconnectTransfer)
        AlertDialog(
            onDismissRequest = { showDisconnectTransfer = false },
            title = { Text("Disconnect and cancel download?") },
            text = {
                Text(
                    "The current download will be cancelled. Completed files will stay on your phone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisconnectTransfer = false
                        vm.disconnect()
                    }
                ) {
                    Text("Cancel download and disconnect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectTransfer = false }) {
                    Text("Keep downloading")
                }
            },
        )

    if (showNetworkLoss)
        AlertDialog(
            onDismissRequest = {
                showNetworkLoss = false
                vm.pendingConnectionCredentials = null
            },
            title = { Text("Connect to camera Wi-Fi?") },
            text = {
                Text(
                    "Connecting may disconnect your current Wi-Fi. Mobile data may remain available."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNetworkLoss = false
                        val saved = vm.pendingConnectionCredentials
                        vm.pendingConnectionCredentials = null
                        if (saved != null)
                            vm.connect(saved.first, saved.second, rememberPassword, true)
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNetworkLoss = false
                        vm.pendingConnectionCredentials = null
                    }
                ) {
                    Text("Cancel")
                }
            },
        )
}

@Composable
internal fun CameraScreen(
    vm: DashcamViewModel,
    state: AppUiState,
    downloading: Boolean,
    ssid: String,
    onSsid: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    rememberPassword: Boolean,
    onRemember: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onFindWifi: () -> Unit,
    onBorrow: () -> Unit,
    onSelectWifi: (Network?) -> Unit,
    onDisconnect: () -> Unit,
    onSaveSample: () -> Unit,
) {
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeading("Camera")
        ConnectionCard(state)
        if (state.commandBusy) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Waiting for camera…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.connection.stage == ConnectionStage.Denied) {
            val context = LocalContext.current
            TextButton(
                onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri(),
                        )
                    )
                }
            ) {
                Icon(AppIcons.Settings, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open permission settings")
            }
        }
        val busy =
            state.connection.stage in
                setOf(
                    ConnectionStage.RequestingPermission,
                    ConnectionStage.RequestingNetwork,
                    ConnectionStage.Identifying,
                    ConnectionStage.Disconnecting,
                )
        if (
            state.connection.network != null &&
                state.connection.stage != ConnectionStage.Connected &&
                !busy
        )
            PrimaryAction("Verify camera", AppIcons.Refresh, true, vm::verifyCurrentNetwork)
        if (state.connection.stage == ConnectionStage.Identifying)
            OutlinedButton(
                onClick = { vm.cancelVerification() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel verification")
            }
        if (
            state.connection.network != null ||
                busy ||
                state.connection.stage == ConnectionStage.Connected
        ) {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcons.Disconnect, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.connection.network != null) "Disconnect camera"
                    else "Cancel connection"
                )
            }
        }
        if (state.connection.stage != ConnectionStage.Connected)
            ExistingWifiSection(
                state.wifiChoices,
                state.chosenWifi,
                !busy,
                onSelectWifi,
                onBorrow,
                onFindWifi,
            )
        if (state.connection.stage != ConnectionStage.Connected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect over Wi-Fi", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Turn on your camera’s Wi-Fi, find its name below, then enter the camera’s password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryAction(
                        if (state.scanningWifi) "Searching for Wi-Fi…" else "Find camera Wi-Fi",
                        AppIcons.Wifi,
                        !busy && !state.scanningWifi,
                        {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onFindWifi()
                        },
                    )
                    if (state.scanningWifi)
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (state.discoveryMessage?.contains("permission", ignoreCase = true) == true) {
                        val context = LocalContext.current
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings
                                            .ACTION_APPLICATION_DETAILS_SETTINGS,
                                        "package:${context.packageName}".toUri(),
                                    )
                                )
                            }
                        ) {
                            Text("Open Wi-Fi permissions")
                        }
                    }
                    state.discoveryMessage?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NetworkNameField(
                        ssid,
                        onSsid,
                        state.nearbyWifi.map { it.ssid },
                        enabled = !busy,
                        onNext = {
                            passwordFocus.requestFocus()
                            keyboard?.show()
                        },
                        suggestionsOpen = state.wifiSuggestionsOpen,
                        onSuggestionsOpenChange = vm::setWifiSuggestionsOpen,
                    )
                    var revealPassword by rememberSaveable { mutableStateOf(false) }
                    OutlinedTextField(
                        password,
                        onPassword,
                        label = { Text("Wi-Fi password") },
                        enabled = !busy,
                        isError =
                            password.isNotEmpty() &&
                                CameraWifiCredentials.passwordError(password) != null,
                        supportingText = {
                            Text(
                                if (password.isNotEmpty())
                                    CameraWifiCredentials.passwordError(password)
                                        ?: "8–63 characters"
                                else "8–63 characters"
                            )
                        },
                        leadingIcon = { Icon(AppIcons.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { revealPassword = !revealPassword }) {
                                Icon(
                                    if (revealPassword) AppIcons.VisibilityOff
                                    else AppIcons.Visibility,
                                    if (revealPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        visualTransformation =
                            if (revealPassword)
                                androidx.compose.ui.text.input.VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                autoCorrectEnabled = false,
                            ),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                }
                            ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                onRemember(!rememberPassword)
                            },
                    ) {
                        Checkbox(rememberPassword, onRemember, enabled = !busy)
                        Text(
                            "Remember password securely",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    PrimaryAction(
                        "Connect camera",
                        AppIcons.Wifi,
                        !busy &&
                            CameraWifiCredentials.ssidError(ssid) == null &&
                            CameraWifiCredentials.passwordError(password) == null,
                        onConnect,
                    )
                    val context = LocalContext.current
                    TextButton(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_WIFI_SETTINGS
                                )
                            )
                        }
                    ) {
                        Text("Open Android Wi-Fi settings")
                    }
                    if (state.discoveryMessage?.contains("Location to be on") == true)
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                                    )
                                )
                            }
                        ) {
                            Text("Open Android Location settings")
                        }
                }
            }
        }
        if (state.snapshot != null) {
            val snapshot = state.snapshot
            CameraFacts(snapshot)
            AdaptiveCameraPanels(
                controls = { CameraControls(vm, snapshot, downloading || state.commandBusy) },
                preview = {
                    LiveViewPanel(vm, state, downloading || state.commandBusy, onSaveSample)
                },
            )
        }
        if (state.snapshot != null)
            SectionCard("Device information", AppIcons.Info) {
                Text("WANLIPO A13", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Firmware ${state.snapshot.identity.firmware}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "SD card: ${state.snapshot.sdStatus.entries.joinToString { "${it.key}: ${it.value}" }.ifBlank { "Not reported" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Battery: ${state.snapshot.batteryValue ?: "Not reported"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

@Composable
private fun CameraControls(vm: DashcamViewModel, snapshot: CameraSnapshot, downloading: Boolean) {
    SectionCard("Camera controls", AppIcons.Tune) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshSnapshot() }, enabled = !downloading) {
                Icon(AppIcons.Refresh, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }
            if (snapshot.recording == true)
                ConfirmActionButton(
                    "Stop recording…",
                    "Stop camera recording?",
                    "Recording will stop. The app will not restart it automatically.",
                    enabled = !downloading,
                ) {
                    vm.execute(
                        CameraCommand.StopRecording,
                        ActionConfirmation(true, exactDisplayedValue = "Stop recording"),
                    )
                }
            else
                Button(
                    onClick = { vm.execute(CameraCommand.StartRecording) },
                    enabled = !downloading,
                ) {
                    Text("Start recording")
                }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.execute(CameraCommand.Snapshot) }, enabled = !downloading) {
                Icon(AppIcons.Photo, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Snapshot")
            }
            ConfirmActionButton(
                "Protect current clip…",
                "Protect current recording?",
                "This protects the current recording. It does not select or lock a historical file.",
                enabled = !downloading && snapshot.recording == true,
            ) {
                vm.execute(
                    CameraCommand.LockCurrentRecording,
                    ActionConfirmation(true, exactDisplayedValue = "Protect current recording"),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Camera: ${if (snapshot.currentSettingValues["switchcam"] == 1) "Rear" else "Front"}"
            )
            ConfirmActionButton(
                "Switch front/rear…",
                "Switch camera view?",
                "The camera’s actual rear video feed has not been verified.",
                enabled = !downloading,
            ) {
                val next = if (snapshot.currentSettingValues["switchcam"] == 1) 0 else 1
                vm.execute(
                    CameraCommand.SwitchCamera(next),
                    ActionConfirmation(
                        true,
                        exactDisplayedValue = if (next == 1) "Rear" else "Front",
                    ),
                )
            }
        }
    }
}

@Composable
internal fun LiveViewPanel(
    vm: DashcamViewModel,
    state: AppUiState,
    downloading: Boolean,
    onSaveSample: () -> Unit,
) {
    SectionCard("Live view", AppIcons.Camera) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (state.liveActive || state.snapshot?.recording == true)
                    "Video preview · Audio is not streamed"
                else "Turn on camera recording to start live view.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.connection.stage == ConnectionStage.Connected && !state.liveActive) {
                    PrimaryAction(
                        "Start live view",
                        AppIcons.Play,
                        !downloading && state.snapshot?.recording == true,
                    ) {
                        vm.execute(CameraCommand.StartLive)
                    }
                } else
                    PrimaryAction("Stop live view", AppIcons.Stop, !downloading) { vm.stopLive() }
                if (state.liveActive) {
                    var diagnostics by rememberSaveable { mutableStateOf(false) }
                    TextButton(onClick = { diagnostics = !diagnostics }) {
                        Text("Video diagnostics")
                    }
                    if (diagnostics) {
                        Text(
                            "The file picker may pause live view. After choosing a file, start live again to capture the sample.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = onSaveSample) { Text("Choose H.264 sample file") }
                        OutlinedButton(onClick = vm::stopLiveSample) { Text("Stop sample") }
                    }
                }
            }
            state.liveIssue?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.liveActive && !state.liveVideoReady && state.liveIssue == null)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text("Waiting for live video…", style = MaterialTheme.typography.bodyMedium)
                }
            if (!state.liveActive)
                Surface(
                    Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Live view is off", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            if (state.liveActive)
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            layoutParams =
                                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 520)
                            holder.addCallback(
                                object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        vm.attachLiveSurface(holder.surface)
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) {
                                        vm.attachLiveSurface(holder.surface)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        vm.attachLiveSurface(null)
                                    }
                                }
                            )
                        }
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MaterialTheme.shapes.medium),
                )
        }
    }
}

@Composable
internal fun RecordingsScreen(
    vm: DashcamViewModel,
    state: AppUiState,
    downloading: Boolean,
    onDownload: (RemoteMedia) -> Unit,
) {
    val connected = state.connection.stage == ConnectionStage.Connected
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeading("Recordings") }
        if (downloading)
            item {
                Text(
                    "Camera browsing is paused while a download is running.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { vm.chooseTab(MainTab.Downloads) }) {
                    Text("View download progress")
                }
            }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaFolder.entries.forEach { folder ->
                    FilterChip(
                        state.folder == folder,
                        { vm.list(folder, 0) },
                        enabled = connected && !state.loadingMedia && !downloading,
                        label = { Text(folder.title) },
                        leadingIcon =
                            if (state.folder == folder)
                                ({ Icon(AppIcons.Check, null, Modifier.size(18.dp)) })
                            else null,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.media.isEmpty()) "Camera library"
                    else
                        "${state.media.size} ${if (state.media.size == 1) "recording" else "recordings"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                FilledTonalIconButton(
                    onClick = { vm.list() },
                    enabled = connected && !state.loadingMedia && !downloading,
                ) {
                    Icon(AppIcons.Refresh, "Refresh recordings")
                }
            }
        }
        if (state.loadingMedia)
            item {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Text("Loading recordings…", style = MaterialTheme.typography.titleMedium)
                }
            }
        state.mediaListError?.let { error ->
            item {
                SectionCard("Could not load recordings", AppIcons.Info) {
                    Text(error, style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(
                        onClick = { vm.list() },
                        enabled = connected && !state.loadingMedia && !downloading,
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
        if (state.media.isEmpty() && !state.loadingMedia && state.mediaListError == null)
            item {
                EmptyPanel(
                    AppIcons.Recordings,
                    if (connected && state.mediaListLoaded) "No recordings in this folder"
                    else if (connected) "No recordings loaded" else "Camera not connected",
                    if (connected && state.mediaListLoaded)
                        "Try another folder, or record a clip with your camera."
                    else if (connected) "Refresh to load recordings."
                    else "Connect your camera to browse videos and photos.",
                )
                if (!connected)
                    FilledTonalButton(onClick = { vm.chooseTab(MainTab.Camera) }) {
                        Text("Connect camera")
                    }
            }
        items(state.media, key = { it.exactCameraPath }) { media ->
            MediaFileCard(
                media.exactCameraPath.substringAfterLast('/'),
                "${media.rawMetadata["time"] ?: media.rawMetadata["date"] ?: "Camera timestamp unavailable"} · ${media.reportedSizeKiB?.let { formatBytes(it * 1024) } ?: "Size unavailable"}",
                if (media.exactCameraPath.endsWith(".jpg", true)) AppIcons.Photo
                else AppIcons.Recordings,
                onClick = { vm.select(media) },
            )
        }
        if (state.media.isNotEmpty() || state.pageStart > 0)
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.list(start = (state.pageStart - 50).coerceAtLeast(0)) },
                        enabled =
                            connected && !state.loadingMedia && !downloading && state.pageStart > 0,
                    ) {
                        Text("Previous")
                    }
                    OutlinedButton(
                        onClick = { vm.list(start = state.pageStart + 50) },
                        enabled =
                            connected &&
                                !state.loadingMedia &&
                                !downloading &&
                                state.media.size == 50,
                    ) {
                        Text("Next")
                    }
                }
                Text(
                    "Camera timestamps and file sizes may be approximate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
    state.selected?.let { media ->
        ModalBottomSheet(onDismissRequest = { vm.select(null) }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    AppIcons.Recordings,
                    null,
                    Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    media.exactCameraPath.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleLarge,
                )
                PrimaryAction(
                    "Save to phone",
                    AppIcons.Download,
                    !downloading && connected && !state.commandBusy,
                ) {
                    onDownload(media)
                    vm.select(null)
                }
                FilledTonalButton(
                    onClick = {
                        if (media.exactCameraPath.endsWith(".jpg", true)) vm.viewRemoteJpeg(media)
                        else vm.playRemote(media)
                        vm.select(null)
                    },
                    enabled = !downloading && connected && !state.commandBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(AppIcons.Play, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open from camera")
                }
                ExperimentalFileDelete(
                    vm,
                    media,
                    enabled = !downloading && !state.commandBusy && connected,
                )
            }
        }
    }
}

@Composable
internal fun DownloadsScreen(
    vm: DashcamViewModel,
    history: List<org.nextcolor.dashcamviewer.android.OwnedDownload>,
    active: Boolean,
    name: String?,
    bytes: Long,
    expected: Long?,
    message: String?,
    onEnableNotifications: (() -> Unit)? = null,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeading("Downloads") }
        if (active)
            item {
                SectionCard("Saving to your phone", AppIcons.Download) {
                    Text(name ?: "Camera recording", style = MaterialTheme.typography.titleMedium)
                    if (expected != null && expected > 0)
                        LinearWavyProgressIndicator(
                            progress = { (bytes.toFloat() / expected).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "${formatBytes(bytes)}${expected?.let { " of ${formatBytes(it)}" } ?: " saved"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = vm::cancelDownload) {
                        Icon(AppIcons.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel download")
                    }
                }
            }
        if (active && onEnableNotifications != null)
            item {
                SectionCard("Follow progress in the background", AppIcons.Info) {
                    Text(
                        "Notifications are off. You can keep following progress here, or enable a notification with a Cancel action.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onEnableNotifications) { Text("Enable notifications") }
                }
            }
        message?.let {
            item {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (history.isEmpty() && !active)
            item {
                EmptyPanel(
                    AppIcons.Download,
                    "No downloads",
                    "Select a recording, then select Save to phone.",
                )
                FilledTonalButton(onClick = { vm.chooseTab(MainTab.Recordings) }) {
                    Text("Browse recordings")
                }
            }
        if (history.isNotEmpty())
            item {
                Text(
                    "Saved on this phone · ${history.size}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        items(history, key = { it.uri }) { item ->
            val open = {
                if (item.mimeType == "image/jpeg") vm.openLocalImage(item.uri.toUri())
                else vm.playLocal(item.uri.toUri())
                Unit
            }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (item.mimeType == "image/jpeg") AppIcons.Photo
                            else AppIcons.Recordings,
                            null,
                            Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (item.mimeType == "image/jpeg") "Photo · Saved offline"
                                else "Video · Saved offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = open) {
                            Icon(AppIcons.Play, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open")
                        }
                        TextButton(onClick = { vm.shareDownload(item.uri.toUri()) }) {
                            Icon(AppIcons.Share, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    vm: DashcamViewModel,
    state: AppUiState,
    downloading: Boolean,
    newSsid: String,
    onNewSsid: (String) -> Unit,
    newPassword: String,
    onNewPassword: (String) -> Unit,
    repeatPassword: String,
    onRepeatPassword: (String) -> Unit,
) {
    val cameraReady =
        !downloading && !state.commandBusy && state.connection.stage == ConnectionStage.Connected
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeading("Settings")
        if (state.commandBusy) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Waiting for camera…", style = MaterialTheme.typography.bodySmall)
        }
        if (state.connection.stage != ConnectionStage.Connected)
            FilledTonalButton(onClick = { vm.chooseTab(MainTab.Camera) }) { Text("Connect camera") }
        state.snapshot?.let { snapshot ->
            snapshot.advertisedSettings.forEach { descriptor ->
                when (descriptor.key) {
                    "switchcam",
                    "rec",
                    "parking_monitor" -> Unit
                    "rec_resolution" -> {
                        if (
                            descriptor.rawCurrentValue != null &&
                                descriptor.rawCurrentValue !in descriptor.optionValues
                        ) {
                            Text(
                                "Current value: ${descriptor.rawCurrentValue}; camera options inconsistent"
                            )
                        }
                        SettingOptions(
                            vm,
                            descriptor,
                            enabled =
                                cameraReady &&
                                    (descriptor.rawCurrentValue == null ||
                                        descriptor.rawCurrentValue in descriptor.optionValues),
                        )
                    }
                    else ->
                        SettingOptions(
                            vm,
                            descriptor,
                            enabled =
                                cameraReady &&
                                    descriptor.key in A13ProtocolClient.WRITABLE_SETTINGS,
                        )
                }
            }
        }
            ?: EmptyPanel(
                AppIcons.Tune,
                "Camera not connected",
                "Connect the camera to change settings.",
            )
        SectionCard("Parking", AppIcons.Shield) {
            ParkingMonitorSection(state.snapshot, cameraReady) { open ->
                val label = if (open) "Open" else "Closed"
                vm.execute(
                    CameraCommand.SetParking(open),
                    ActionConfirmation(true, exactDisplayedValue = label),
                )
            }
        }
        var advanced by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
            Icon(AppIcons.Settings, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (advanced) "Hide advanced controls" else "Advanced camera controls")
        }
        Text(
            "Clock, Wi-Fi configuration and card maintenance",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = advanced) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard("Camera clock", AppIcons.Clock) {
                    ExperimentalClock(vm, !cameraReady)
                }
                SectionCard("Camera Wi-Fi", AppIcons.Wifi) {
                    OutlinedTextField(
                        newSsid,
                        onNewSsid,
                        label = { Text("New camera Wi-Fi name") },
                        supportingText = { Text("1–32 characters: letters, numbers, _ . ~ -") },
                        isError =
                            newSsid.isNotEmpty() &&
                                (newSsid.length !in 1..32 ||
                                    !newSsid.matches(Regex("[A-Za-z0-9_.~-]+"))),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ExperimentalOperation(
                        "Change camera Wi-Fi name",
                        "Changing this can disconnect the app; reconnect manually using the new name.",
                        enabled =
                            cameraReady &&
                                newSsid.length in 1..32 &&
                                newSsid.matches(Regex("[A-Za-z0-9_.~-]+")),
                    ) { acknowledged ->
                        vm.execute(
                            CameraCommand.SetWifiName(newSsid),
                            ActionConfirmation(acknowledged, exactDisplayedValue = newSsid),
                        )
                    }
                    OutlinedTextField(
                        newPassword,
                        onNewPassword,
                        label = { Text("New camera Wi-Fi password") },
                        supportingText = { Text("8–63 characters: letters, numbers, _ . ~ -") },
                        isError =
                            newPassword.isNotEmpty() &&
                                (newPassword.length !in 8..63 ||
                                    !newPassword.matches(Regex("[A-Za-z0-9_.~-]+"))),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        repeatPassword,
                        onRepeatPassword,
                        label = { Text("Repeat new camera Wi-Fi password") },
                        isError = repeatPassword.isNotEmpty() && repeatPassword != newPassword,
                        supportingText =
                            if (repeatPassword.isNotEmpty() && repeatPassword != newPassword)
                                ({ Text("Passwords do not match.") })
                            else null,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ExperimentalOperation(
                        "Change camera Wi-Fi password",
                        "The password must be entered twice. The app may disconnect; reconnect manually with the new password.",
                        enabled =
                            cameraReady &&
                                newPassword.length in 8..63 &&
                                newPassword.matches(Regex("[A-Za-z0-9_.~-]+")) &&
                                newPassword == repeatPassword,
                    ) { acknowledged ->
                        vm.execute(
                            CameraCommand.SetWifiPassword(newPassword, repeatPassword),
                            ActionConfirmation(
                                acknowledged,
                                exactDisplayedValue = "Set the camera Wi-Fi password",
                            ),
                        )
                    }
                    ExperimentalOperation(
                        "Restart camera Wi-Fi",
                        "Restarting disconnects the current camera connection. The app will not reconnect automatically.",
                        enabled =
                            cameraReady && state.connection.stage == ConnectionStage.Connected,
                    ) { acknowledged ->
                        vm.execute(
                            CameraCommand.RestartWifi,
                            ActionConfirmation(
                                acknowledged,
                                exactDisplayedValue =
                                    "Reconnect manually after the camera Wi-Fi restarts",
                            ),
                        )
                    }
                }
                SectionCard("Maintenance", AppIcons.Storage) {
                    val recordingOff = state.snapshot?.recording == false
                    val maintenanceReady =
                        cameraReady &&
                            state.connection.stage == ConnectionStage.Connected &&
                            recordingOff &&
                            !vm.isLiveActive() &&
                            !state.playerIsRemote
                    Text(
                        if (!recordingOff)
                            "Formatting and reset require recording already confirmed off. Stop recording separately first."
                        else
                            "Formatting/reset also require live, remote playback, and downloads stopped."
                    )
                    ExperimentalTypedOperation(
                        "Format card",
                        "This erases all camera card recordings. Live, remote playback, and downloads must be stopped; recording must already be off.",
                        "ERASE ALL RECORDINGS",
                        maintenanceReady,
                    ) { phrase ->
                        vm.execute(
                            CameraCommand.FormatCard(phrase),
                            ActionConfirmation(true, phrase),
                        )
                    }
                    ExperimentalTypedOperation(
                        "Factory reset",
                        "This resets camera configuration. Live, remote playback, and downloads must be stopped; recording must already be off.",
                        "RESET CAMERA",
                        maintenanceReady,
                    ) { phrase ->
                        vm.execute(
                            CameraCommand.FactoryReset(phrase),
                            ActionConfirmation(true, phrase),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ParkingMonitorSection(
    snapshot: CameraSnapshot?,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Text("Parking monitor", style = MaterialTheme.typography.titleMedium)
    val descriptor = snapshot?.advertisedSettings?.firstOrNull { it.key == "parking_monitor" }
    val currentValues = snapshot?.currentSettingValues.orEmpty()
    if (descriptor == null || "parking_monitor" !in currentValues) {
        Text("Not exposed by the camera in its current state. Use the camera’s physical menu.")
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false, true).forEach { open ->
                val value = if (open) 1 else 0
                val label = if (open) "Open" else "Closed"
                ConfirmActionButton(
                    label,
                    "Set parking monitor $label?",
                    "Parking behavior depends on camera power and hardware; this app has not verified it.",
                    enabled =
                        enabled &&
                            value in descriptor.optionValues &&
                            currentValues["parking_monitor"] != value,
                ) {
                    onChange(open)
                }
            }
        }
        Text(
            "Parking operation depends on camera power and hardware. This app has not verified that behavior."
        )
    }
}

@Composable
private fun SettingOptions(vm: DashcamViewModel, descriptor: SettingDescriptor, enabled: Boolean) {
    val current = descriptor.rawCurrentValue
    SectionCard(descriptor.key.settingTitle(), AppIcons.Tune) {
        Text("${current?.let { descriptor.labelFor(it) ?: it.toString() } ?: "Unknown"}")
        if (descriptor.key !in A13ProtocolClient.WRITABLE_SETTINGS) Text("Read only")
        else
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                descriptor.optionValues.zip(descriptor.optionLabels).forEach { (value, label) ->
                    TonalToggleButton(
                        checked = current == value,
                        onCheckedChange = {
                            if (current != value)
                                vm.execute(CameraCommand.SetSetting(descriptor.key, value))
                        },
                        enabled = enabled,
                    ) {
                        if (current == value) {
                            Icon(AppIcons.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(label)
                    }
                }
            }
    }
}

@Composable
private fun ExperimentalClock(vm: DashcamViewModel, disabled: Boolean) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyyMMddHHmmss") }
    var show by remember { mutableStateOf(false) }
    var proposed by remember { mutableStateOf<Pair<String, String>?>(null) }
    OutlinedButton(
        onClick = {
            proposed = LocalDateTime.now().format(formatter) to ZoneId.systemDefault().id
            show = true
        },
        enabled = !disabled,
    ) {
        Text("Set camera clock…")
    }
    if (show && proposed != null) {
        var acknowledged by remember { mutableStateOf(false) }
        val (date, zone) = proposed!!
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Set camera clock?") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                TextButton(
                    enabled = acknowledged,
                    onClick = {
                        show = false
                        vm.execute(
                            CameraCommand.SetClock(date, zone),
                            ActionConfirmation(true, exactDisplayedValue = "$date $zone"),
                        )
                    },
                ) {
                    Text("Set clock")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ExperimentalOperation(
    title: String,
    warning: String,
    enabled: Boolean,
    onConfirm: (Boolean) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, enabled = enabled) { Text(title) }
    if (show) {
        var acknowledged by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("EXPERIMENTAL\n$warning")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, { acknowledged = it })
                        Text("I understand this experimental action")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = acknowledged,
                    onClick = {
                        show = false
                        onConfirm(acknowledged)
                    },
                ) {
                    Text("Continue")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExperimentalTypedOperation(
    title: String,
    warning: String,
    phrase: String,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, enabled = enabled) { Text(title) }
    if (show) {
        var typed by remember { mutableStateOf("") }
        var acknowledged by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("EXPERIMENTAL\n$warning")
                    OutlinedTextField(typed, { typed = it }, label = { Text("Type $phrase") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, { acknowledged = it })
                        Text("I understand this experimental action")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = acknowledged && typed == phrase,
                    onClick = {
                        show = false
                        onConfirm(typed)
                    },
                ) {
                    Text("Continue")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConfirmActionButton(
    title: String,
    dialogTitle: String,
    explanation: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { show = true }, enabled = enabled) { Text(title) }
    if (show)
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(dialogTitle) },
            text = { Text(explanation) },
            confirmButton = {
                TextButton(
                    onClick = {
                        show = false
                        onConfirm()
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
}

@Composable
private fun ExperimentalFileDelete(vm: DashcamViewModel, media: RemoteMedia, enabled: Boolean) {
    val filename = media.exactCameraPath.substringAfterLast('/')
    ExperimentalTypedOperation(
        "Delete from camera…",
        "Deletes exactly this camera file: ${media.exactCameraPath}",
        filename,
        enabled,
    ) { typed ->
        vm.execute(
            CameraCommand.DeleteOneFile(media, typed),
            ActionConfirmation(true, phrase = typed, exactDisplayedValue = filename),
        )
    }
}
