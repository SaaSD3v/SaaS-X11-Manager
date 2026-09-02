package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saas.x11manager.util.VncLaunchSettings
import com.saas.x11manager.util.VncSettings

@Composable
internal fun TigerVncSettingsDialog(
    containerName: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    var settings by remember(containerName) {
        mutableStateOf(VncSettings.getLaunchSettings(context, containerName))
    }
    var saveError by remember(containerName) { mutableStateOf<String?>(null) }

    fun update(block: (VncLaunchSettings) -> VncLaunchSettings) {
        settings = block(settings)
        saveError = null
    }

    val validationError = VncSettings.validateLaunchSettings(settings)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SettingsEthernet, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "TigerVNC settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "$containerName · full server configuration",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        TigerSection(
                            title = "Manager-controlled values",
                            subtitle = "These values remain owned by SaaS X11 Manager."
                        ) {
                            ManagedValue("VNC port", VncSettings.getPort(context, containerName).toString())
                            ManagedValue("Password file", "/root/.vnc/passwd")
                            ManagedValue(
                                "VNC password",
                                "Configured through the access wizard; plaintext is never stored in Android preferences."
                            )
                            ManagedValue(
                                "Display number",
                                "Allocated automatically. Mirror mode uses the active Integrated X11 display."
                            )
                        }
                    }

                    item {
                        TigerSection(
                            title = "Standalone Xvnc display",
                            subtitle = "Virtual display options used when access mode is VNC."
                        ) {
                            TigerTextField(
                                label = "Resolution (geometry)",
                                value = settings.geometry,
                                supporting = "WIDTHxHEIGHT. Default ${VncSettings.DEFAULT_GEOMETRY}."
                            ) { update { s -> s.copy(geometry = it) } }
                            TigerTextField(
                                label = "Color depth",
                                value = settings.depth,
                                supporting = "16, 24 or 32. Default 24.",
                                numeric = true
                            ) { update { s -> s.copy(depth = it) } }
                            TigerTextField(
                                label = "Pixel format",
                                value = settings.pixelFormat,
                                supporting = "Optional Xvnc -pixelformat value. Leave empty for TigerVNC default."
                            ) { update { s -> s.copy(pixelFormat = it) } }
                            TigerTextField(
                                label = "Render node",
                                value = settings.renderNode,
                                supporting = "Optional Xvnc DRM render node. Leave empty for automatic selection."
                            ) { update { s -> s.copy(renderNode = it) } }
                            TigerTextField(
                                label = "Desktop name",
                                value = settings.desktopName,
                                supporting = "Optional name advertised to VNC clients."
                            ) { update { s -> s.copy(desktopName = it) } }
                            TigerSwitch(
                                title = "Avoid Shift/NumLock",
                                subtitle = "Enable TigerVNC AvoidShiftNumLock.",
                                checked = settings.avoidShiftNumLock
                            ) { update { s -> s.copy(avoidShiftNumLock = it) } }
                            TigerTextField(
                                label = "AllowOverride",
                                value = settings.allowOverride,
                                supporting = "Optional Xvnc AllowOverride parameter list."
                            ) { update { s -> s.copy(allowOverride = it) } }
                        }
                    }

                    item {
                        TigerSection(
                            title = "Network and sockets",
                            subtitle = "RFB bind behavior and protocol families."
                        ) {
                            TigerTextField(
                                label = "Interface",
                                value = settings.interfaceAddress,
                                supporting = "Optional TigerVNC interface/address. Empty keeps the server default."
                            ) { update { s -> s.copy(interfaceAddress = it) } }
                            TigerSwitch(
                                title = "Localhost only",
                                subtitle = "When enabled, TCP VNC accepts only local-device connections.",
                                checked = settings.localhostOnly
                            ) { update { s -> s.copy(localhostOnly = it) } }
                            TigerSwitch(
                                title = "Use IPv4",
                                subtitle = "Enable IPv4 RFB listening.",
                                checked = settings.useIPv4
                            ) { update { s -> s.copy(useIPv4 = it) } }
                            TigerSwitch(
                                title = "Use IPv6",
                                subtitle = "Enable IPv6 RFB listening.",
                                checked = settings.useIPv6
                            ) { update { s -> s.copy(useIPv6 = it) } }
                            TigerTextField(
                                label = "RFB UNIX socket path",
                                value = settings.rfbUnixPath,
                                supporting = "Optional rfbunixpath. TCP remains Manager-controlled by the VNC port."
                            ) { update { s -> s.copy(rfbUnixPath = it) } }
                            TigerTextField(
                                label = "RFB UNIX socket mode",
                                value = settings.rfbUnixMode,
                                supporting = "Octal permissions such as 0600."
                            ) { update { s -> s.copy(rfbUnixMode = it) } }
                        }
                    }

                    item {
                        TigerSection(
                            title = "Security and authentication",
                            subtitle = "TigerVNC security, blacklist, query and TLS parameters."
                        ) {
                            TigerTextField(
                                label = "SecurityTypes",
                                value = settings.securityTypes,
                                supporting = "'auto' preserves current Manager behavior. Or enter a TigerVNC comma-separated list."
                            ) { update { s -> s.copy(securityTypes = it) } }
                            TigerSwitch(
                                title = "Use blacklist",
                                subtitle = "Temporarily block clients after repeated authentication failures.",
                                checked = settings.useBlacklist
                            ) { update { s -> s.copy(useBlacklist = it) } }
                            TigerTextField(
                                label = "Blacklist threshold",
                                value = settings.blacklistThreshold,
                                supporting = "Default 5.",
                                numeric = true
                            ) { update { s -> s.copy(blacklistThreshold = it) } }
                            TigerTextField(
                                label = "Blacklist timeout (seconds)",
                                value = settings.blacklistTimeout,
                                supporting = "Default 10.",
                                numeric = true
                            ) { update { s -> s.copy(blacklistTimeout = it) } }
                            TigerSwitch(
                                title = "QueryConnect",
                                subtitle = "Ask locally before accepting a new connection when supported.",
                                checked = settings.queryConnect
                            ) { update { s -> s.copy(queryConnect = it) } }
                            TigerTextField(
                                label = "QueryConnect timeout",
                                value = settings.queryConnectTimeout,
                                supporting = "Seconds. Default 10.",
                                numeric = true
                            ) { update { s -> s.copy(queryConnectTimeout = it) } }
                            TigerSwitch(
                                title = "Require username",
                                subtitle = "Require username in security modes that support it.",
                                checked = settings.requireUsername
                            ) { update { s -> s.copy(requireUsername = it) } }
                            TigerTextField(
                                label = "PAM service",
                                value = settings.pamService,
                                supporting = "Default: vnc."
                            ) { update { s -> s.copy(pamService = it) } }
                            TigerTextField(
                                label = "PlainUsers",
                                value = settings.plainUsers,
                                supporting = "Optional TigerVNC PlainUsers value."
                            ) { update { s -> s.copy(plainUsers = it) } }
                            TigerTextField(
                                label = "GnuTLS priority",
                                value = settings.gnuTlsPriority,
                                supporting = "Optional GnuTLSPriority string."
                            ) { update { s -> s.copy(gnuTlsPriority = it) } }
                            TigerTextField(
                                label = "X509 certificate",
                                value = settings.x509Cert,
                                supporting = "Optional certificate path inside the container."
                            ) { update { s -> s.copy(x509Cert = it) } }
                            TigerTextField(
                                label = "X509 key",
                                value = settings.x509Key,
                                supporting = "Optional private-key path inside the container."
                            ) { update { s -> s.copy(x509Key = it) } }
                            TigerTextField(
                                label = "RSA key",
                                value = settings.rsaKey,
                                supporting = "Optional RSAKey path inside the container."
                            ) { update { s -> s.copy(rsaKey = it) } }
                        }
                    }

                    item {
                        TigerSection(
                            title = "Sharing, keyboard and clipboard",
                            subtitle = "Client sharing, input acceptance and clipboard synchronization."
                        ) {
                            TigerSwitch(
                                title = "Always shared",
                                subtitle = "Allow multiple VNC clients to share the session.",
                                checked = settings.alwaysShared
                            ) { update { s -> s.copy(alwaysShared = it) } }
                            TigerSwitch(
                                title = "Never shared",
                                subtitle = "Force exclusive sessions. Cannot be enabled together with Always shared.",
                                checked = settings.neverShared
                            ) { update { s -> s.copy(neverShared = it) } }
                            TigerSwitch(
                                title = "Disconnect existing clients",
                                subtitle = "Allow a non-shared request to disconnect existing clients.",
                                checked = settings.disconnectClients
                            ) { update { s -> s.copy(disconnectClients = it) } }
                            TigerSwitch(
                                title = "Accept key events",
                                subtitle = "Accept keyboard events from VNC clients.",
                                checked = settings.acceptKeyEvents
                            ) { update { s -> s.copy(acceptKeyEvents = it) } }
                            TigerSwitch(
                                title = "Accept pointer events",
                                subtitle = "Accept mouse/touch pointer events from VNC clients.",
                                checked = settings.acceptPointerEvents
                            ) { update { s -> s.copy(acceptPointerEvents = it) } }
                            TigerSwitch(
                                title = "Accept desktop resize",
                                subtitle = "Allow clients to request desktop-size changes when supported.",
                                checked = settings.acceptSetDesktopSize
                            ) { update { s -> s.copy(acceptSetDesktopSize = it) } }
                            TigerSwitch(
                                title = "Accept client clipboard",
                                subtitle = "Accept text sent from the VNC client.",
                                checked = settings.acceptCutText
                            ) { update { s -> s.copy(acceptCutText = it) } }
                            TigerSwitch(
                                title = "Send clipboard",
                                subtitle = "Send server clipboard text to clients.",
                                checked = settings.sendCutText
                            ) { update { s -> s.copy(sendCutText = it) } }
                            TigerSwitch(
                                title = "Send primary selection",
                                subtitle = "Send the X11 primary selection when supported.",
                                checked = settings.sendPrimary
                            ) { update { s -> s.copy(sendPrimary = it) } }
                            TigerSwitch(
                                title = "Set primary selection",
                                subtitle = "Apply client clipboard data to the X11 primary selection.",
                                checked = settings.setPrimary
                            ) { update { s -> s.copy(setPrimary = it) } }
                            TigerTextField(
                                label = "Maximum clipboard bytes",
                                value = settings.maxCutText,
                                supporting = "Default 262144.",
                                numeric = true
                            ) { update { s -> s.copy(maxCutText = it) } }
                            TigerSwitch(
                                title = "Raw keyboard",
                                subtitle = "Use RawKeyboard when the client/server combination supports it.",
                                checked = settings.rawKeyboard
                            ) { update { s -> s.copy(rawKeyboard = it) } }
                            TigerTextField(
                                label = "RemapKeys",
                                value = settings.remapKeys,
                                supporting = "Optional TigerVNC key remapping specification."
                            ) { update { s -> s.copy(remapKeys = it) } }
                            TigerSwitch(
                                title = "Protocol 3.3 compatibility",
                                subtitle = "Force legacy RFB protocol 3.3 behavior.",
                                checked = settings.protocol33
                            ) { update { s -> s.copy(protocol33 = it) } }
                        }
                    }

                    item {
                        TigerSection(
                            title = "Timeouts and performance",
                            subtitle = "Connection limits, frame pacing and framebuffer comparison."
                        ) {
                            TigerTextField(
                                label = "IdleTimeout",
                                value = settings.idleTimeout,
                                supporting = "Seconds. 0 disables the timeout.",
                                numeric = true
                            ) { update { s -> s.copy(idleTimeout = it) } }
                            TigerTextField(
                                label = "MaxConnectionTime",
                                value = settings.maxConnectionTime,
                                supporting = "Seconds. 0 means unlimited.",
                                numeric = true
                            ) { update { s -> s.copy(maxConnectionTime = it) } }
                            TigerTextField(
                                label = "MaxDisconnectionTime",
                                value = settings.maxDisconnectionTime,
                                supporting = "Seconds. 0 means unlimited.",
                                numeric = true
                            ) { update { s -> s.copy(maxDisconnectionTime = it) } }
                            TigerTextField(
                                label = "MaxIdleTime",
                                value = settings.maxIdleTime,
                                supporting = "Seconds. 0 means unlimited.",
                                numeric = true
                            ) { update { s -> s.copy(maxIdleTime = it) } }
                            TigerTextField(
                                label = "FrameRate",
                                value = settings.frameRate,
                                supporting = "1-240. Default 60.",
                                numeric = true
                            ) { update { s -> s.copy(frameRate = it) } }
                            TigerTextField(
                                label = "CompareFB",
                                value = settings.compareFb,
                                supporting = "0, 1 or 2. Default 2.",
                                numeric = true
                            ) { update { s -> s.copy(compareFb = it) } }
                            TigerSwitch(
                                title = "Improved Hextile",
                                subtitle = "Use TigerVNC's improved Hextile encoder.",
                                checked = settings.improvedHextile
                            ) { update { s -> s.copy(improvedHextile = it) } }
                            TigerTextField(
                                label = "Log",
                                value = settings.logSpec,
                                supporting = "Optional TigerVNC Log specification. Empty keeps current server logging."
                            ) { update { s -> s.copy(logSpec = it) } }
                        }
                    }

                    item {
                        TigerSection(
                            title = "x0vncserver mirror",
                            subtitle = "Only used by access mode Both, which mirrors the Integrated X11 display."
                        ) {
                            TigerTextField(
                                label = "Mirror crop geometry",
                                value = settings.mirrorGeometry,
                                supporting = "Optional WIDTHxHEIGHT or WIDTHxHEIGHT+X+Y. Empty shares the full Integrated X11 display."
                            ) { update { s -> s.copy(mirrorGeometry = it) } }
                            TigerTextField(
                                label = "HostsFile",
                                value = settings.hostsFile,
                                supporting = "Optional x0vncserver hosts access-control file."
                            ) { update { s -> s.copy(hostsFile = it) } }
                            TigerTextField(
                                label = "MaxProcessorUsage",
                                value = settings.maxProcessorUsage,
                                supporting = "1-100. Default 35.",
                                numeric = true
                            ) { update { s -> s.copy(maxProcessorUsage = it) } }
                            TigerTextField(
                                label = "PollingCycle",
                                value = settings.pollingCycle,
                                supporting = "Milliseconds. Default 30.",
                                numeric = true
                            ) { update { s -> s.copy(pollingCycle = it) } }
                            TigerSwitch(
                                title = "Use shared memory",
                                subtitle = "Enable x0vncserver UseSHM.",
                                checked = settings.useShm
                            ) { update { s -> s.copy(useShm = it) } }
                        }
                    }

                    item {
                        TigerSection(
                            title = "Extra TigerVNC arguments",
                            subtitle = "Compatibility escape hatch for package/version-specific parameters."
                        ) {
                            OutlinedTextField(
                                value = settings.extraArguments,
                                onValueChange = { value ->
                                    update { s -> s.copy(extraArguments = value) }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                label = { Text("One complete argument per line") },
                                supportingText = {
                                    Text(
                                        "Use -Option=value form. Blank lines and # comments are ignored. " +
                                            "Manager-owned rfbport, display and password options are blocked."
                                    )
                                },
                                singleLine = false,
                                minLines = 5
                            )
                        }
                    }

                    item {
                        val message = saveError ?: validationError
                        if (message != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = message,
                                    modifier = Modifier.padding(14.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                "Settings are valid. They are applied on the next VNC/Both start.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            settings = VncLaunchSettings()
                            saveError = null
                        }
                    ) {
                        Text("Reset")
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            val error = VncSettings.validateLaunchSettings(settings)
                            if (error != null) {
                                saveError = error
                            } else if (VncSettings.setLaunchSettings(context, containerName, settings)) {
                                onSaved()
                            } else {
                                saveError = "Could not save TigerVNC settings."
                            }
                        },
                        enabled = validationError == null
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun TigerSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun ManagedValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TigerSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TigerTextField(
    label: String,
    value: String,
    supporting: String,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (!numeric || newValue.all { it.isDigit() }) {
                onValueChange(newValue)
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(supporting) },
        singleLine = true,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions()
        }
    )
}
