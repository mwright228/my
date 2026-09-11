package id.my.mub.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.ui.theme.*
import java.util.UUID

@Composable
fun ProtocolConfigDialog(
    initialProfile: VpnProfile? = null,
    onDismiss: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    var name by remember { mutableStateOf(initialProfile?.name ?: "Custom Node") }
    var protocol by remember { mutableStateOf(initialProfile?.protocol ?: ProtocolType.VLESS_WS) }
    var serverHost by remember { mutableStateOf(initialProfile?.serverHost ?: "") }
    var serverPort by remember { mutableStateOf(initialProfile?.serverPort?.toString() ?: "443") }
    var userUUID by remember { mutableStateOf(initialProfile?.userUUID ?: "") }
    var sni by remember { mutableStateOf(initialProfile?.bugHostSNI ?: "") }
    var allowInsecureTLS by remember { mutableStateOf(initialProfile?.allowInsecureTLS ?: false) }

    // SSH fields
    var sshUser by remember { mutableStateOf(initialProfile?.sshUser ?: "") }
    var sshPassword by remember { mutableStateOf(initialProfile?.sshPassword ?: "") }
    var proxyHost by remember { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by remember { mutableStateOf(if (initialProfile?.proxyPort != null && initialProfile.proxyPort > 0) initialProfile.proxyPort.toString() else "80") }
    var customPayload by remember { mutableStateOf(initialProfile?.customPayload ?: "") }

    // ZiVPN fields
    var udpObfsPassword by remember { mutableStateOf(initialProfile?.udpObfsPassword ?: "") }
    var udpPortHopRange by remember { mutableStateOf(initialProfile?.udpPortHopRange ?: "10000-65535") }

    // T-Brutal fields
    var brutalRateMbps by remember { mutableStateOf(initialProfile?.brutalRateMbps ?: 50) }
    var poolConcurrency by remember { mutableStateOf(initialProfile?.poolConcurrency ?: 2) }

    // Protocol dropdown menu state
    var protoDropdownExpanded by remember { mutableStateOf(false) }

    // Payload generator dialog state
    var showPayloadGenerator by remember { mutableStateOf(false) }

    val protocols = listOf(
        ProtocolType.VLESS_WS,
        ProtocolType.VLESS_TCP,
        ProtocolType.SSH_PAYLOAD,
        ProtocolType.SHADOWSOCKS_2022,
        ProtocolType.ZIVPN_UDP,
        ProtocolType.T_BRUTAL
    )

    if (showPayloadGenerator) {
        PayloadGeneratorDialog(
            initialTargetHost = sni.ifBlank { serverHost }.ifBlank { "m.facebook.com" },
            onDismiss = { showPayloadGenerator = false },
            onGenerated = { genPayload ->
                customPayload = genPayload
                showPayloadGenerator = false
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (initialProfile == null) "Add New Node" else "Edit Configuration",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Select protocol and configure specialized parameters",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Protocol Selector
                Text("Protocol", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgMain)
                            .border(1.dp, AccentPrimary, RoundedCornerShape(8.dp))
                            .clickable { protoDropdownExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = protocol.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberMint
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand",
                            tint = TextSecondary
                        )
                    }

                    DropdownMenu(
                        expanded = protoDropdownExpanded,
                        onDismissRequest = { protoDropdownExpanded = false },
                        modifier = Modifier.background(SurfaceCard).border(1.dp, SurfaceCardBorder)
                    ) {
                        for (p in protocols) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        Text(p.badge, fontSize = 10.sp, color = TextSecondary)
                                    }
                                },
                                onClick = {
                                    protocol = p
                                    protoDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Node Remark / Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Node Remark / Name", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPrimary,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Server Address & Port
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        label = { Text("Server Host / IP", fontSize = 11.sp) },
                        placeholder = { Text("e.g. gr.mub.my.id", fontSize = 11.sp, color = TextMuted) },
                        modifier = Modifier.weight(2.5f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPrimary,
                            unfocusedBorderColor = SurfaceCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it },
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

                Spacer(modifier = Modifier.height(14.dp))

                // DYNAMIC PROTOCOL SPECIFIC FIELDS
                when (protocol) {
                    ProtocolType.VLESS_WS, ProtocolType.VLESS_TCP -> {
                        Text("VLESS Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = userUUID,
                            onValueChange = { userUUID = it },
                            label = { Text("User UUID", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                TextButton(onClick = { userUUID = UUID.randomUUID().toString() }) {
                                    Text("Gen", fontSize = 11.sp, color = AccentPrimary)
                                }
                            },
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
                            placeholder = { Text("e.g. server domain", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowInsecureTLS,
                                onCheckedChange = { allowInsecureTLS = it },
                                colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                            )
                            Text("Allow Insecure TLS (Self-signed certs)", fontSize = 12.sp, color = TextSecondary)
                        }
                    }

                    ProtocolType.SSH_PAYLOAD -> {
                        Text("SSH & HTTP Injector Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it },
                                label = { Text("SSH Username", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentPrimary,
                                    unfocusedBorderColor = SurfaceCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                            OutlinedTextField(
                                value = sshPassword,
                                onValueChange = { sshPassword = it },
                                label = { Text("SSH Password", fontSize = 11.sp) },
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

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = proxyHost,
                                onValueChange = { proxyHost = it },
                                label = { Text("Proxy Bug Host (Optional)", fontSize = 11.sp) },
                                modifier = Modifier.weight(2.5f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentPrimary,
                                    unfocusedBorderColor = SurfaceCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                            OutlinedTextField(
                                value = proxyPort,
                                onValueChange = { proxyPort = it },
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

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("HTTP Carrier Payload", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Button(
                                onClick = { showPayloadGenerator = true },
                                colors = ButtonDefaults.buttonColors(containerColor = BgMain),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentPrimary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Payload Generator", fontSize = 11.sp, color = AccentPrimary, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        OutlinedTextField(
                            value = customPayload,
                            onValueChange = { customPayload = it },
                            placeholder = { Text("CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]...", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        // Macro quick chips
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val macros = listOf("[crlf]", "[host_port]", "[host]", "[protocol]")
                            for (m in macros) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(BgMain)
                                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(4.dp))
                                        .clickable { customPayload += m }
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(m, fontSize = 10.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    ProtocolType.SHADOWSOCKS_2022 -> {
                        Text("Shadowsocks Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = userUUID,
                            onValueChange = { userUUID = it },
                            label = { Text("Password / Pre-shared Key", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }

                    ProtocolType.ZIVPN_UDP -> {
                        Text("ZiVPN UDP Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = udpObfsPassword,
                            onValueChange = { udpObfsPassword = it },
                            label = { Text("Salamander Obfuscation Password", fontSize = 11.sp) },
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
                            value = udpPortHopRange,
                            onValueChange = { udpPortHopRange = it },
                            label = { Text("Port Hopping Range (e.g. 10000-65535)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }

                    ProtocolType.T_BRUTAL -> {
                        Text("T-Brutal Pacing Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = userUUID,
                            onValueChange = { userUUID = it },
                            label = { Text("Authentication Token", fontSize = 11.sp) },
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

                        Text("Pacing Rate: $brutalRateMbps Mbps", fontSize = 12.sp, color = TextPrimary)
                        Slider(
                            value = brutalRateMbps.toFloat(),
                            onValueChange = { brutalRateMbps = it.toInt() },
                            valueRange = 10f..300f,
                            colors = SliderDefaults.colors(thumbColor = AccentPrimary, activeTrackColor = AccentPrimary)
                        )
                    }

                    else -> {
                        // Fallback
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val finalProfile = (initialProfile ?: VpnProfile()).copy(
                                name = name.trim().ifBlank { "Custom Node" },
                                protocol = protocol,
                                serverHost = serverHost.trim(),
                                serverPort = serverPort.trim().toIntOrNull() ?: 443,
                                userUUID = userUUID.trim(),
                                bugHostSNI = sni.trim(),
                                allowInsecureTLS = allowInsecureTLS,
                                sshUser = sshUser.trim(),
                                sshPassword = sshPassword.trim(),
                                proxyHost = proxyHost.trim(),
                                proxyPort = proxyPort.trim().toIntOrNull() ?: 0,
                                customPayload = customPayload.trim(),
                                udpObfsPassword = udpObfsPassword.trim(),
                                udpPortHopRange = udpPortHopRange.trim(),
                                brutalRateMbps = brutalRateMbps,
                                poolConcurrency = poolConcurrency
                            )
                            onSave(finalProfile)
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) {
                        Text("Save Configuration", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
