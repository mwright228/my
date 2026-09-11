package id.my.mub.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
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
    initialProtocol: ProtocolType = ProtocolType.VLESS_WS,
    initialProfile: VpnProfile? = null,
    onDismiss: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    var protocol by remember { mutableStateOf(initialProfile?.protocol ?: initialProtocol) }
    var name by remember {
        mutableStateOf(
            initialProfile?.name ?: when (protocol) {
                ProtocolType.VLESS_WS -> "VLESS Cloudflare WS"
                ProtocolType.VLESS_TCP -> "VLESS Direct TCP"
                ProtocolType.SSH_PAYLOAD -> "SSH Custom Payload"
                ProtocolType.SHADOWSOCKS_2022 -> "Shadowsocks 2022"
                ProtocolType.ZIVPN_UDP -> "ZiVPN UDP Obfs"
                ProtocolType.T_BRUTAL -> "T-Brutal Multi-Path"
                else -> "Custom Node"
            }
        )
    }
    var serverHost by remember { mutableStateOf(initialProfile?.serverHost ?: "") }
    var serverPort by remember {
        mutableStateOf(
            initialProfile?.serverPort?.toString() ?: when (protocol) {
                ProtocolType.SSH_PAYLOAD -> "22"
                ProtocolType.SHADOWSOCKS_2022 -> "8388"
                ProtocolType.ZIVPN_UDP -> "5666"
                else -> "443"
            }
        )
    }
    var userUUID by remember { mutableStateOf(initialProfile?.userUUID ?: "") }
    var sni by remember { mutableStateOf(initialProfile?.bugHostSNI ?: "") }
    var wsPath by remember { mutableStateOf(initialProfile?.wsPath ?: "/vless-ws") }
    var wsHost by remember { mutableStateOf(initialProfile?.wsHost ?: "") }
    var vlessFlow by remember { mutableStateOf(initialProfile?.vlessFlow ?: "none") }
    var allowInsecureTLS by remember { mutableStateOf(initialProfile?.allowInsecureTLS ?: false) }

    // SSH fields
    var sshUser by remember { mutableStateOf(initialProfile?.sshUser ?: "") }
    var sshPassword by remember { mutableStateOf(initialProfile?.sshPassword ?: "") }
    var proxyHost by remember { mutableStateOf(initialProfile?.proxyHost ?: "") }
    var proxyPort by remember {
        mutableStateOf(if (initialProfile?.proxyPort != null && initialProfile.proxyPort > 0) initialProfile.proxyPort.toString() else "80")
    }
    var customPayload by remember { mutableStateOf(initialProfile?.customPayload ?: "") }

    // Shadowsocks fields
    var ssCipher by remember { mutableStateOf(initialProfile?.ssCipher ?: "2022-blake3-aes-128-gcm") }

    // ZiVPN fields
    var udpObfsPassword by remember { mutableStateOf(initialProfile?.udpObfsPassword ?: "") }
    var udpPortHopRange by remember { mutableStateOf(initialProfile?.udpPortHopRange ?: "10000-65535") }

    // T-Brutal fields
    var brutalRateMbps by remember { mutableStateOf(initialProfile?.brutalRateMbps ?: 50) }
    var poolConcurrency by remember { mutableStateOf(initialProfile?.poolConcurrency ?: 2) }

    // DNS fields
    var dnsPrimary by remember { mutableStateOf(initialProfile?.dnsServer ?: "1.1.1.1") }
    var dnsSecondary by remember { mutableStateOf(initialProfile?.dnsSecondary ?: "8.8.8.8") }

    // Dropdowns & Dialog states
    var protoDropdownExpanded by remember { mutableStateOf(false) }
    var cipherDropdownExpanded by remember { mutableStateOf(false) }
    var flowDropdownExpanded by remember { mutableStateOf(false) }
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
            initialTargetHost = sni.ifBlank { proxyHost }.ifBlank { serverHost }.ifBlank { "m.facebook.com" },
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
                .fillMaxHeight(0.92f)
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
                // Header with Protocol Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (initialProfile == null) "Add ${protocol.displayName}" else "Edit Configuration",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Configure specialized parameters for ${protocol.displayName}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Protocol Selector dropdown
                Text("Protocol Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
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
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Expand", tint = TextSecondary)
                    }

                    DropdownMenu(
                        expanded = protoDropdownExpanded,
                        onDismissRequest = { protoDropdownExpanded = false },
                        modifier = Modifier
                            .background(SurfaceCard)
                            .border(1.dp, SurfaceCardBorder)
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

                // 1. General Node Info
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

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        label = { Text(if (protocol == ProtocolType.SSH_PAYLOAD) "SSH Server Host" else "Server Host / IP", fontSize = 11.sp) },
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

                // 2. DYNAMIC PROTOCOL SPECIFIC FIELDS (Isolated per protocol!)
                when (protocol) {
                    ProtocolType.VLESS_WS -> {
                        Text("VLESS WebSocket Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                            label = { Text("SNI / Bug Host (Zero-Rated Host)", fontSize = 11.sp) },
                            placeholder = { Text("e.g. m.facebook.com or fast.com", fontSize = 11.sp, color = TextMuted) },
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
                                value = wsPath,
                                onValueChange = { wsPath = it },
                                label = { Text("WS Path", fontSize = 11.sp) },
                                placeholder = { Text("/vless-ws", fontSize = 11.sp, color = TextMuted) },
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
                                value = wsHost,
                                onValueChange = { wsHost = it },
                                label = { Text("Host Header (Optional)", fontSize = 11.sp) },
                                placeholder = { Text("e.g. gr.mub.my.id", fontSize = 11.sp, color = TextMuted) },
                                modifier = Modifier.weight(1.5f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentPrimary,
                                    unfocusedBorderColor = SurfaceCardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowInsecureTLS,
                                onCheckedChange = { allowInsecureTLS = it },
                                colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                            )
                            Text("Allow Insecure TLS (Self-signed certificates)", fontSize = 12.sp, color = TextSecondary)
                        }
                    }

                    ProtocolType.VLESS_TCP -> {
                        Text("VLESS Direct TCP Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
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
                            placeholder = { Text("e.g. gr.mub.my.id", fontSize = 11.sp, color = TextMuted) },
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

                        // Flow control selector
                        Text("Flow Control", fontSize = 11.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BgMain)
                                    .border(1.dp, SurfaceCardBorder, RoundedCornerShape(8.dp))
                                    .clickable { flowDropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(if (vlessFlow.isBlank() || vlessFlow == "none") "None (Standard TCP)" else vlessFlow, fontSize = 12.sp, color = TextPrimary)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextSecondary)
                            }
                            DropdownMenu(
                                expanded = flowDropdownExpanded,
                                onDismissRequest = { flowDropdownExpanded = false },
                                modifier = Modifier.background(SurfaceCard).border(1.dp, SurfaceCardBorder)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None (Standard TCP)", fontSize = 12.sp, color = TextPrimary) },
                                    onClick = { vlessFlow = "none"; flowDropdownExpanded = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("xtls-rprx-vision", fontSize = 12.sp, color = TextPrimary) },
                                    onClick = { vlessFlow = "xtls-rprx-vision"; flowDropdownExpanded = false }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowInsecureTLS,
                                onCheckedChange = { allowInsecureTLS = it },
                                colors = CheckboxDefaults.colors(checkedColor = AccentPrimary)
                            )
                            Text("Allow Insecure TLS", fontSize = 12.sp, color = TextSecondary)
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
                                placeholder = { Text("e.g. 104.16.89.23", fontSize = 11.sp, color = TextMuted) },
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

                        Spacer(modifier = Modifier.height(12.dp))

                        // Payload Generator Button and Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("HTTP Carrier Payload", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Button(
                                onClick = { showPayloadGenerator = true },
                                colors = ButtonDefaults.buttonColors(containerColor = BgMain),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CyberMint),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = CyberMint, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Payload Generator", fontSize = 11.sp, color = CyberMint, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = customPayload,
                            onValueChange = { customPayload = it },
                            placeholder = { Text("CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]...", fontSize = 11.sp, color = TextMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = CyberMint,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        // Macro Quick Insertion Chips (Horizontal Scroll)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Insert Macro", fontSize = 11.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val macros = listOf("[crlf]", "[host_port]", "[host]", "[port]", "[protocol]", "[ua]", "[split]", "[instant_split]", "[cr]", "[lf]", "[raw]")
                            for (m in macros) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(BgMain)
                                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(6.dp))
                                        .clickable { customPayload += m }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
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

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Cipher / Encryption Method", fontSize = 11.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BgMain)
                                    .border(1.dp, SurfaceCardBorder, RoundedCornerShape(8.dp))
                                    .clickable { cipherDropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ssCipher, fontSize = 12.sp, color = TextPrimary)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = TextSecondary)
                            }
                            DropdownMenu(
                                expanded = cipherDropdownExpanded,
                                onDismissRequest = { cipherDropdownExpanded = false },
                                modifier = Modifier.background(SurfaceCard).border(1.dp, SurfaceCardBorder)
                            ) {
                                val ciphers = listOf(
                                    "2022-blake3-aes-128-gcm",
                                    "2022-blake3-aes-256-gcm",
                                    "2022-blake3-chacha20-poly1305",
                                    "aes-128-gcm",
                                    "aes-256-gcm",
                                    "chacha20-ietf-poly1305"
                                )
                                for (c in ciphers) {
                                    DropdownMenuItem(
                                        text = { Text(c, fontSize = 12.sp, color = TextPrimary) },
                                        onClick = { ssCipher = c; cipherDropdownExpanded = false }
                                    )
                                }
                            }
                        }
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
                            label = { Text("Port Hopping Range", fontSize = 11.sp) },
                            placeholder = { Text("e.g. 10000-65535", fontSize = 11.sp, color = TextMuted) },
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
                        Text("T-Brutal Multi-Path Parameters", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = userUUID,
                            onValueChange = { userUUID = it },
                            label = { Text("Token / Pre-shared Key", fontSize = 11.sp) },
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
                                value = brutalRateMbps.toString(),
                                onValueChange = { brutalRateMbps = it.toIntOrNull() ?: 50 },
                                label = { Text("Rate (Mbps)", fontSize = 11.sp) },
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
                                value = poolConcurrency.toString(),
                                onValueChange = { poolConcurrency = it.toIntOrNull() ?: 2 },
                                label = { Text("Parallel Lanes", fontSize = 11.sp) },
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
                    }

                    else -> {
                        // General fallback
                        OutlinedTextField(
                            value = userUUID,
                            onValueChange = { userUUID = it },
                            label = { Text("Credentials / Token", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 3. DNS Configuration (Primary & Secondary)
                Text("DNS Resolvers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dnsPrimary,
                        onValueChange = { dnsPrimary = it },
                        label = { Text("Primary DNS", fontSize = 11.sp) },
                        placeholder = { Text("1.1.1.1", fontSize = 11.sp, color = TextMuted) },
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
                        value = dnsSecondary,
                        onValueChange = { dnsSecondary = it },
                        label = { Text("Secondary DNS", fontSize = 11.sp) },
                        placeholder = { Text("8.8.8.8", fontSize = 11.sp, color = TextMuted) },
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

                Spacer(modifier = Modifier.height(22.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            val portInt = serverPort.toIntOrNull() ?: 443
                            val prPortInt = proxyPort.toIntOrNull() ?: 0
                            val profile = (initialProfile ?: VpnProfile()).copy(
                                name = name.ifBlank { "Custom Node" },
                                serverHost = serverHost.trim(),
                                serverIp = serverHost.trim(),
                                serverPort = portInt,
                                protocol = protocol,
                                userUUID = userUUID.trim(),
                                bugHostSNI = sni.trim(),
                                wsPath = wsPath.trim(),
                                wsHost = wsHost.trim(),
                                vlessFlow = vlessFlow.trim(),
                                allowInsecureTLS = allowInsecureTLS,
                                sshUser = sshUser.trim(),
                                sshPassword = sshPassword.trim(),
                                proxyHost = proxyHost.trim(),
                                proxyPort = prPortInt,
                                customPayload = customPayload.trim(),
                                ssCipher = ssCipher.trim(),
                                udpObfsPassword = udpObfsPassword.trim(),
                                udpPortHopRange = udpPortHopRange.trim(),
                                brutalRateMbps = brutalRateMbps,
                                poolConcurrency = poolConcurrency,
                                dnsServer = dnsPrimary.trim().ifBlank { "1.1.1.1" },
                                dnsSecondary = dnsSecondary.trim().ifBlank { "8.8.8.8" }
                            )
                            onSave(profile)
                        },
                        modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Configuration", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
