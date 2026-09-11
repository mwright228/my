package id.my.mub.ui.config

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.ConfigParser
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.service.NativeCoreBridge
import id.my.mub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ConfigEditorScreen(
    currentProfile: VpnProfile,
    onSaveAndDeploy: (VpnProfile) -> Unit,
    onSaveAsNew: (VpnProfile) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(currentProfile.name) }
    var serverHost by remember { mutableStateOf(currentProfile.serverHost) }
    var serverIp by remember { mutableStateOf(currentProfile.serverIp) }
    var serverPort by remember { mutableStateOf(if (currentProfile.serverPort > 0) currentProfile.serverPort.toString() else "443") }
    var protocol by remember { mutableStateOf(currentProfile.protocol) }
    var bugHostSNI by remember { mutableStateOf(currentProfile.bugHostSNI) }
    var userUUID by remember { mutableStateOf(currentProfile.userUUID) }
    var customPayload by remember { mutableStateOf(currentProfile.customPayload) }
    var dnsPrimary by remember { mutableStateOf(currentProfile.dnsServer) }

    // Latency probe state
    var probeResult by remember { mutableStateOf<String?>(null) }
    var isProbing by remember { mutableStateOf(false) }

    // Clipboard notification
    var pasteMessage by remember { mutableStateOf<String?>(null) }

    fun buildUpdatedProfile(): VpnProfile {
        val portInt = serverPort.trim().toIntOrNull() ?: 443
        val host = serverHost.trim()
        val ip = serverIp.trim().ifBlank { host }
        return currentProfile.copy(
            name = name.trim().ifBlank { "Custom Server" },
            serverHost = host,
            serverIp = ip,
            serverPort = portInt,
            protocol = protocol,
            bugHostSNI = bugHostSNI.trim(),
            userUUID = userUUID.trim(),
            customPayload = customPayload.trim(),
            dnsServer = dnsPrimary.trim().ifBlank { "1.1.1.1" }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header & Quick Import
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Configuration",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Manual Node & Protocol Setup",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                        val parsed = ConfigParser.parseUri(text)
                        if (parsed != null) {
                            name = parsed.name
                            serverHost = parsed.serverHost
                            serverIp = parsed.serverIp
                            serverPort = parsed.serverPort.toString()
                            protocol = parsed.protocol
                            bugHostSNI = parsed.bugHostSNI
                            userUUID = parsed.userUUID
                            customPayload = parsed.customPayload
                            pasteMessage = "Imported ${parsed.protocol.displayName} configuration!"
                        } else {
                            pasteMessage = "No valid vless://, vmess://, or ss:// link found in clipboard"
                        }
                    } else {
                        pasteMessage = "Clipboard is empty"
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated)
            ) {
                Text("Paste Link", fontSize = 12.sp, color = TextPrimary)
            }
        }

        if (pasteMessage != null) {
            Surface(
                color = AccentPrimary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = pasteMessage!!,
                    fontSize = 12.sp,
                    color = AccentPrimary,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Section 1: Protocol Selection
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Protocol Selection",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                val selectableProtocols = listOf(
                    ProtocolType.VLESS_WS,
                    ProtocolType.VLESS_TCP,
                    ProtocolType.SHADOWSOCKS_2022,
                    ProtocolType.ZIVPN_UDP,
                    ProtocolType.SSH_PAYLOAD,
                    ProtocolType.T_BRUTAL
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    selectableProtocols.chunked(2).forEach { rowProtocols ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowProtocols.forEach { p ->
                                val isSelected = protocol == p
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { protocol = p },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) AccentPrimary.copy(alpha = 0.15f) else BgMain,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) AccentPrimary else SurfaceCardBorder
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp)) {
                                        Text(
                                            text = p.displayName,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isSelected) TextPrimary else TextSecondary,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = p.badge,
                                            fontSize = 10.sp,
                                            color = if (isSelected) AccentPrimary else TextMuted,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section 2: Server Details
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Server Details",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )

                // Profile Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Configuration Name", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                // Server Address & Port
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        label = { Text("Server Host / IP", fontSize = 12.sp) },
                        placeholder = { Text("192.168.1.1 or domain", color = TextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(2.2f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it },
                        label = { Text("Port", fontSize = 12.sp) },
                        placeholder = { Text("443", color = TextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                // Password / UUID
                OutlinedTextField(
                    value = userUUID,
                    onValueChange = { userUUID = it },
                    label = { Text("Password / UUID / Token", fontSize = 12.sp) },
                    placeholder = { Text("Enter client secret", color = TextMuted, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                // SNI / Bug Host with latency probe button
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = bugHostSNI,
                            onValueChange = { bugHostSNI = it },
                            label = { Text("SNI / Bug Host (Optional)", fontSize = 12.sp) },
                            placeholder = { Text("e.g. carrier zero-rated host", color = TextMuted, fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (bugHostSNI.isBlank()) return@Button
                                isProbing = true
                                coroutineScope.launch {
                                    val res = NativeCoreBridge.probeBugHost(bugHostSNI.trim(), 2500)
                                    probeResult = if (res.isWhitelisted) {
                                        "Online: ${res.statusCode} (${res.latencyMs} ms)"
                                    } else {
                                        "Failed: ${res.errorMessage ?: "Status ${res.statusCode}"}"
                                    }
                                    isProbing = false
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated)
                        ) {
                            Text(if (isProbing) "..." else "Probe", fontSize = 12.sp, color = TextPrimary)
                        }
                    }

                    if (probeResult != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = probeResult!!,
                            fontSize = 11.sp,
                            color = if (probeResult!!.startsWith("Online")) AccentSuccess else AccentWarning,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Section 3: HTTP Custom / Injector Payload (Only when relevant or requested)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "HTTP Payload (Custom Injection)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Supported macros: [host], [port], [crlf], [split], [ua]",
                    fontSize = 11.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Macro quick-insert chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("[crlf]", "[host]", "[port]", "[split]", "[ua]").forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SurfaceCardElevated,
                            modifier = Modifier.clickable {
                                customPayload += tag
                            }
                        ) {
                            Text(
                                text = tag,
                                fontSize = 11.sp,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customPayload,
                    onValueChange = { customPayload = it },
                    placeholder = { Text("CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host][crlf][crlf]", color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val updated = buildUpdatedProfile()
                    onSaveAndDeploy(updated)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
            ) {
                Text("Save & Apply", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
            }

            OutlinedButton(
                onClick = {
                    val updated = buildUpdatedProfile()
                    onSaveAsNew(updated)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
            ) {
                Text("Save as New", fontSize = 14.sp, color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
