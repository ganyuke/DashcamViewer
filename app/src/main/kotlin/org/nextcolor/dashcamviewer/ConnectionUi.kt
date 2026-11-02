@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.nextcolor.dashcamviewer

import android.net.Network
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.nextcolor.dashcamviewer.android.CameraWifiCredentials
import org.nextcolor.dashcamviewer.android.ExistingWifi

@Composable
internal fun NetworkNameField(
    ssid: String,
    onSsid: (String) -> Unit,
    suggestions: List<String>,
    enabled: Boolean = true,
    onNext: () -> Unit = {},
    suggestionsOpen: Boolean = false,
    onSuggestionsOpenChange: (Boolean) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    val matches = suggestions.filter { it.contains(query, ignoreCase = true) }.distinct()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = ssid,
            onValueChange = {
                onSsid(it)
                query = it
                onSuggestionsOpenChange(true)
            },
            label = { Text("Camera Wi-Fi name") },
            placeholder = { Text("Enter network name") },
            enabled = enabled,
            singleLine = true,
            isError = ssid.isNotEmpty() && CameraWifiCredentials.ssidError(ssid) != null,
            supportingText =
                if (ssid.isNotEmpty() && CameraWifiCredentials.ssidError(ssid) != null)
                    ({ Text(CameraWifiCredentials.ssidError(ssid).orEmpty()) })
                else null,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = {
                        onSuggestionsOpenChange(false)
                        onNext()
                    }
                ),
            modifier =
                Modifier.fillMaxWidth().onFocusChanged {
                    val previouslyFocused = focused
                    focused = it.isFocused
                    if (it.isFocused) onSuggestionsOpenChange(true)
                    else if (previouslyFocused) onSuggestionsOpenChange(false)
                },
        )
        if (suggestionsOpen && enabled && matches.isNotEmpty()) {
            (if (showAll) matches else matches.take(5)).forEach { name ->
                Surface(
                    onClick = {
                        onSsid(name)
                        query = ""
                        onSuggestionsOpenChange(false)
                        onNext()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = { Icon(AppIcons.Wifi, null) },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                    )
                }
            }
            if (!showAll && matches.size > 5)
                TextButton(onClick = { showAll = true }) {
                    Text("Show all ${matches.size} networks")
                }
        }
    }
}

@Composable
internal fun ExistingWifiSection(
    choices: List<ExistingWifi>,
    selected: Network?,
    enabled: Boolean,
    onSelect: (Network?) -> Unit,
    onUse: () -> Unit,
    onFindNames: () -> Unit,
) {
    if (choices.isEmpty()) return
    SectionCard("Phone’s Wi-Fi connections", AppIcons.Wifi) {
        Text(
            "If your phone is already on the camera’s Wi-Fi, use it without entering the password again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        choices
            .filter { it.ssid != null || it.isDefault || choices.size == 1 }
            .forEach { choice ->
                Surface(
                    onClick = { onSelect(choice.network) },
                    enabled = enabled,
                    shape = MaterialTheme.shapes.medium,
                    color =
                        if (selected == choice.network) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    ListItem(
                        headlineContent = {
                            Text(choice.ssid ?: "Connected Wi-Fi · name hidden by Android")
                        },
                        supportingContent = {
                            Text(
                                if (choice.ssid == null)
                                    "Allow Wi-Fi discovery to show its name, or identify it in Android Wi-Fi settings."
                                else "Connected on your phone · Camera not verified"
                            )
                        },
                        leadingContent = {
                            RadioButton(
                                selected == choice.network,
                                onClick = null,
                                enabled = enabled,
                            )
                        },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                    )
                }
            }
        if (choices.any { it.ssid == null })
            TextButton(onClick = onFindNames, enabled = enabled) { Text("Show Wi-Fi names") }
        FilledTonalButton(
            onClick = onUse,
            enabled = enabled && selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check selected network for camera")
        }
    }
}
