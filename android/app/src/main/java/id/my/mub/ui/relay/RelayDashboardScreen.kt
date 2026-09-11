package id.my.mub.ui.relay

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.ConfigParser
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.ui.theme.*

@Composable
fun RelayDashboardScreen(
    vpnState: VpnState,
    currentProfile: VpnProfile,
    onBackToNotes: () -> Unit,
    onToggleRelay: () -> Unit,
    onOpenConsole: () -> Unit,
    onUpdateProfile: (VpnProfile) -> Unit
) {
    val context = LocalContext.current
    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting
    var showEditModal by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgMain,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back to cover notes
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onBackToNotes() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Notes",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Notes", fontSize = 14.sp, color = TextSecondary)
                }

                Text(
                    text = "Cloud Sync Relay",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                IconButton(onClick = onOpenConsole) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Console Logs",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // --- Main Minimalist One-Button Status Card ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .border(
                        1.5.dp,
                        if (isConnected) AccentSuccess else if (isConnecting) AccentWarning else SurfaceCardBorder,
                        RoundedCornerShape(20.dp)
                    ),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isConnected) AccentSuccess
                                    else if (isConnecting) AccentWarning
                                    else TextMuted
                                )
                        )
                        Text(
                            text = when {
                                isConnected -> "RELAY ACTIVE"
                                isConnecting -> "CONNECTING..."
                                else -> "RELAY IDLE"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) AccentSuccess else if (isConnecting) AccentWarning else TextSecondary,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Active Server Host & Protocol
                    Text(
                        text = if (currentProfile.name.isNotBlank()) currentProfile.name else "No Server Configured",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    val endpoint = currentProfile.serverHost.ifBlank { currentProfile.serverIp }
                    Text(
                        text = if (endpoint.isNotBlank()) "${currentProfile.protocol.displayName} · $endpoint:${currentProfile.serverPort}" else "Tap Edit below to paste or add config",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // --- The Big One-Button Switch ---
                    Button(
                        onClick = onToggleRelay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isConnected) AccentError else AccentPrimary
                        ),
                        enabled = !isConnecting
                    ) {
                        Text(
                            text = when {
                                isConnecting -> "Connecting..."
                                isConnected -> "STOP RELAY"
                                else -> "START RELAY"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                    }

                    // Live Telemetry (when connected)
                    if (isConnected && vpnState is VpnState.Connected) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfaceCardElevated)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("DOWN", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                Text(
                                    String.format("%.1f MB/s", vpnState.rxSpeedMbps / 8.0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentSuccess
                                )
                            }
                            Box(modifier = Modifier.height(24.dp).width(1.dp).background(SurfaceCardBorder))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("UP", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                Text(
                                    String.format("%.1f MB/s", vpnState.txSpeedMbps / 8.0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPrimary
                                )
                            }
                            Box(modifier = Modifier.height(24.dp).width(1.dp).background(SurfaceCardBorder))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PING", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                Text(
                                    if (vpnState.pingMs > 0) "${vpnState.pingMs} ms" else "--",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Action Buttons: Edit / Paste Config & Clipboard Paste ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Edit / Manual Config Button
                Button(
                    onClick = { showEditModal = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCard),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                ) {
                    Text("Edit Config", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }

                // 1-Tap Paste Link Button
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                        if (clipText.isNotBlank()) {
                            val parsed = ConfigParser.parseUri(clipText)
                            if (parsed != null) {
                                onUpdateProfile(parsed)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentPrimary)
                ) {
                    Text("Paste Link", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AccentPrimary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // --- Clean Manual Configuration Sheet / Modal ---
    if (showEditModal) {
        ManualConfigDialog(
            current = currentProfile,
            onDismiss = { showEditModal = false },
            onSave = { updated ->
                onUpdateProfile(updated)
                showEditModal = false
            }
        )
    }
}

@Composable
fun ManualConfigDialog(
    current: VpnProfile,
    onDismiss: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    var name by remember { mutableStateOf(current.name) }
    var protocol by remember { mutableStateOf(current.protocol) }
    var host by remember { mutableStateOf(current.serverHost) }
    var port by remember { mutableStateOf(current.serverPort.toString()) }
    var uuid by remember { mutableStateOf(current.userUUID) }
    var sni by remember { mutableStateOf(current.bugHostSNI) }
    var customPayload by remember { mutableStateOf(current.customPayload) }
    var insecureTLS by remember { mutableStateOf(current.allowInsecureTLS) }

    val protocols = listOf(
        ProtocolType.VLESS_WS,
        ProtocolType.VLESS_TCP,
        ProtocolType.SHADOWSOCKS,
        ProtocolType.SSH_DIRECT,
        ProtocolType.ZIVPN_UDP,
        ProtocolType.T_BRUTAL
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Text("Relay Server Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Protocol Selector Chips
                Text("Protocol", fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    protocols.take(3).forEach { p ->
                        val isSel = protocol == p
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) AccentPrimary else SurfaceCardElevated)
                                .clickable { protocol = p }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(p.displayName, fontSize = 11.sp, color = if (isSel) PureWhite else TextSecondary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    protocols.drop(3).forEach { p ->
                        val isSel = protocol == p
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) AccentPrimary else SurfaceCardElevated)
                                .clickable { protocol = p }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(p.displayName, fontSize = 11.sp, color = if (isSel) PureWhite else TextSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Configuration Name", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Server Host / IP", fontSize = 11.sp) },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uuid,
                    onValueChange = { uuid = it },
                    label = { Text("UUID / Password / Token", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = sni,
                    onValueChange = { sni = it },
                    label = { Text("SNI / Bug Host", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                if (protocol == ProtocolType.SSH_DIRECT) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customPayload,
                        onValueChange = { customPayload = it },
                        label = { Text("Custom HTTP Payload", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Allow Insecure TLS", fontSize = 12.sp, color = TextSecondary)
                    Switch(
                        checked = insecureTLS,
                        onCheckedChange = { insecureTLS = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val pInt = port.trim().toIntOrNull() ?: 443
                    val updated = current.copy(
                        name = name.ifBlank { host.ifBlank { "Custom Node" } },
                        protocol = protocol,
                        serverHost = host.trim(),
                        serverPort = pInt,
                        userUUID = uuid.trim(),
                        bugHostSNI = sni.trim(),
                        customPayload = customPayload.trim(),
                        allowInsecureTLS = insecureTLS
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Apply & Save", color = PureWhite, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
