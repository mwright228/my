package id.my.mub.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import id.my.mub.data.PayloadGenerator
import id.my.mub.data.PayloadPreset
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
    var name by remember { mutableStateOf(currentProfile.name) }
    var serverHost by remember { mutableStateOf(currentProfile.serverHost) }
    var serverIp by remember { mutableStateOf(currentProfile.serverIp) }
    var serverPort by remember { mutableStateOf(currentProfile.serverPort.toString()) }
    var protocol by remember { mutableStateOf(currentProfile.protocol) }
    var bugHostSNI by remember { mutableStateOf(currentProfile.bugHostSNI) }
    var userUUID by remember { mutableStateOf(currentProfile.userUUID) }

    // Payload & Injection
    var customPayload by remember { mutableStateOf(currentProfile.customPayload) }
    var showPresetDialog by remember { mutableStateOf(false) }

    // DNS Settings
    var dnsPrimary by remember { mutableStateOf(currentProfile.dnsServer) }
    var dnsSecondary by remember { mutableStateOf(currentProfile.dnsSecondary) }

    // Advanced Tuning
    var poolLanes by remember { mutableFloatStateOf(currentProfile.poolConcurrency.toFloat()) }
    var pacingRate by remember { mutableFloatStateOf(currentProfile.brutalRateMbps.toFloat()) }
    var isWireSpeed by remember { mutableStateOf(currentProfile.brutalRateMbps == 0) }

    // UDP Obfuscation
    var udpObfs by remember { mutableStateOf(currentProfile.udpObfsPassword) }
    var udpPortRange by remember { mutableStateOf(currentProfile.udpPortHopRange) }

    // SSH Credentials
    var sshUser by remember { mutableStateOf(currentProfile.sshUser) }
    var sshPassword by remember { mutableStateOf(currentProfile.sshPassword) }

    // Kill switch
    var killSwitch by remember { mutableStateOf(currentProfile.killSwitchEnabled) }

    // Probe state
    var probeResult by remember { mutableStateOf<String?>(null) }
    var isProbing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun buildUpdatedProfile(): VpnProfile {
        val portInt = serverPort.trim().toIntOrNull() ?: 443
        return currentProfile.copy(
            name = name.trim().ifBlank { "Custom Node" },
            serverHost = serverHost.trim(),
            serverIp = serverIp.trim().ifBlank { serverHost.trim() },
            serverPort = portInt,
            protocol = protocol,
            bugHostSNI = bugHostSNI.trim(),
            userUUID = userUUID.trim(),
            customPayload = customPayload.trim(),
            dnsServer = dnsPrimary.trim().ifBlank { "1.1.1.1" },
            dnsSecondary = dnsSecondary.trim().ifBlank { "8.8.8.8" },
            poolConcurrency = poolLanes.toInt(),
            brutalRateMbps = if (isWireSpeed) 0 else pacingRate.toInt(),
            udpObfsPassword = udpObfs.trim(),
            udpPortHopRange = udpPortRange.trim(),
            sshUser = sshUser.trim(),
            sshPassword = sshPassword.trim(),
            killSwitchEnabled = killSwitch
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // --- 1. Header Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Config & Payload Editor",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
                Text(
                    text = "HTTP Custom / Injector / v2rayNG Mode",
                    fontSize = 12.sp,
                    color = SlateGray
                )
            }

            Button(
                onClick = { onSaveAndDeploy(buildUpdatedProfile()) },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Deploy ⚡", color = BgObsidian, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- 2. Server Connection Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. Server Host & IP Address",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ElectricCyan
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Profile Name
                Text("Profile Label", color = SlateGray, fontSize = 12.sp)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = SurfaceCardBorder
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Host and Port Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(2f)) {
                        Text("Server Domain / IP", color = SlateGray, fontSize = 12.sp)
                        OutlinedTextField(
                            value = serverHost,
                            onValueChange = {
                                serverHost = it
                                if (serverIp.isBlank()) serverIp = it
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. 212.60.151.69", color = SlateGray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = SurfaceCardBorder
                            )
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Port", color = SlateGray, fontSize = 12.sp)
                        OutlinedTextField(
                            value = serverPort,
                            onValueChange = { serverPort = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("443", color = SlateGray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = SurfaceCardBorder
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 3. Protocol Selection Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "2. Active Protocol Engine",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeonViolet
                )
                Spacer(modifier = Modifier.height(10.dp))

                val protocols = listOf(
                    ProtocolType.T_BRUTAL to "T-Brutal (Wire-Speed)",
                    ProtocolType.ZIVPN_UDP to "ZiVPN UDP (Salamander)",
                    ProtocolType.SSH_PAYLOAD to "SSH HTTP Injector",
                    ProtocolType.VLESS_TCP to "VLESS Direct TCP",
                    ProtocolType.VLESS_WS to "VLESS WebSocket",
                    ProtocolType.HYSTERIA_2 to "Hysteria 2 (QUIC)",
                    ProtocolType.SHADOWSOCKS_2022 to "Shadowsocks 2022"
                )

                protocols.forEach { (proto, label) ->
                    val isSelected = protocol == proto
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0x228B5CF6) else Color.Transparent)
                            .clickable { protocol = proto }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = label,
                                color = if (isSelected) PureWhite else SlateGray,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = proto.badge,
                                color = if (isSelected) ElectricCyan else Color(0xFF5A667A),
                                fontSize = 11.sp
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { protocol = proto },
                            colors = RadioButtonDefaults.colors(selectedColor = NeonViolet)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 4. Bug-Host SNI with Probe Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "3. Bug-Host / SNI Domain",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PureWhite
                    )

                    // Probe status pill
                    if (probeResult != null) {
                        Text(
                            text = probeResult ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (probeResult?.contains("OK") == true) CyberMint else AmberGold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bugHostSNI,
                    onValueChange = { bugHostSNI = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. images.vodafone.co.uk", color = SlateGray) },
                    trailingIcon = {
                        Button(
                            onClick = {
                                val host = bugHostSNI.trim()
                                if (host.isNotBlank()) {
                                    isProbing = true
                                    coroutineScope.launch {
                                        val res = NativeCoreBridge.probeBugHost("https://$host", host)
                                        probeResult = if (res.isWhitelisted) "${res.statusCode} OK (Whitelisted)" else "Status: ${res.statusCode}"
                                        isProbing = false
                                    }
                                }
                            },
                            enabled = !isProbing && bugHostSNI.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(if (isProbing) "Probing..." else "Test ⚡", fontSize = 11.sp, color = PureWhite)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = SurfaceCardBorder
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                // Quick Suggestion Chips
                Text("Popular Zero-Rated SNIs:", color = SlateGray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("images.vodafone.co.uk", "filter.ncnd.jazz.com.pk", "m.facebook.com").forEach { sni ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceCardElevated)
                                .clickable { bugHostSNI = sni }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(sni, color = ElectricCyan, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 5. Custom HTTP Payload Generator Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "4. HTTP Request Payload",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PureWhite
                        )
                        Text(
                            text = "Carrier Header Injection Engine",
                            fontSize = 11.sp,
                            color = SlateGray
                        )
                    }

                    Button(
                        onClick = { showPresetDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("Templates ▾", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Macro Chips Row
                Text("Insert Macro Chips:", color = SlateGray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PayloadGenerator.MACRO_CHIPS.take(5).forEach { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x228B5CF6))
                                .clickable { customPayload += chip }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(chip, color = PureWhite, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Payload Textarea
                OutlinedTextField(
                    value = customPayload,
                    onValueChange = { customPayload = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    placeholder = {
                        Text(
                            "e.g. CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]",
                            color = SlateGray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = SurfaceCardBorder
                    ),
                    maxLines = 5
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 6. Auth Credentials Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "5. User Credentials & Token",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PureWhite
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text("User Token / UUID", color = SlateGray, fontSize = 12.sp)
                OutlinedTextField(
                    value = userUUID,
                    onValueChange = { userUUID = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = SurfaceCardBorder
                    )
                )

                if (protocol == ProtocolType.SSH_PAYLOAD) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SSH Username", color = SlateGray, fontSize = 12.sp)
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite,
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = SurfaceCardBorder
                                )
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SSH Password", color = SlateGray, fontSize = 12.sp)
                            OutlinedTextField(
                                value = sshPassword,
                                onValueChange = { sshPassword = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite,
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = SurfaceCardBorder
                                )
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 7. DNS Settings Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "6. Custom DNS Resolvers",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PureWhite
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Primary DNS", color = SlateGray, fontSize = 12.sp)
                        OutlinedTextField(
                            value = dnsPrimary,
                            onValueChange = { dnsPrimary = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = SurfaceCardBorder
                            )
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Secondary DNS", color = SlateGray, fontSize = 12.sp)
                        OutlinedTextField(
                            value = dnsSecondary,
                            onValueChange = { dnsSecondary = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = PureWhite,
                                unfocusedTextColor = PureWhite,
                                focusedBorderColor = ElectricCyan,
                                unfocusedBorderColor = SurfaceCardBorder
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Quick DNS Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Cloudflare" to "1.1.1.1",
                        "Google" to "8.8.8.8",
                        "AdGuard" to "94.140.14.14"
                    ).forEach { (name, ip) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceCardElevated)
                                .clickable {
                                    dnsPrimary = ip
                                    if (name == "Cloudflare") dnsSecondary = "1.0.0.1"
                                    if (name == "Google") dnsSecondary = "8.8.4.4"
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(name, color = CyberMint, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- 8. Concurrency & Performance Tuning ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "7. Concurrency & Wire-Speed Tuning",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PureWhite
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Connection Pool Lanes", color = SlateGray, fontSize = 13.sp)
                    Text("${poolLanes.toInt()} Lanes", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = poolLanes,
                    onValueChange = { poolLanes = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wire-Speed Mode (Uncapped)", color = PureWhite, fontSize = 13.sp)
                    Switch(
                        checked = isWireSpeed,
                        onCheckedChange = { isWireSpeed = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = PureWhite, checkedTrackColor = ElectricCyan)
                    )
                }

                if (!isWireSpeed) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Pacing Rate: ${pacingRate.toInt()} Mbps", color = SlateGray, fontSize = 12.sp)
                    Slider(
                        value = pacingRate,
                        onValueChange = { pacingRate = it },
                        valueRange = 10f..200f,
                        steps = 18,
                        colors = SliderDefaults.colors(thumbColor = NeonViolet, activeTrackColor = NeonViolet)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- 9. Bottom Action Buttons ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { onSaveAsNew(buildUpdatedProfile()) },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
            ) {
                Text("Save as New", color = PureWhite, fontSize = 14.sp)
            }

            Button(
                onClick = { onSaveAndDeploy(buildUpdatedProfile()) },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet)
            ) {
                Text("Deploy Now ⚡", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }

    // --- Preset Dialog ---
    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            containerColor = SurfaceCard,
            title = {
                Text("Select Payload Template", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PayloadPreset.values().forEach { preset ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceCardElevated)
                                .clickable {
                                    customPayload = PayloadGenerator.generate(preset, bugHostSNI)
                                    showPresetDialog = false
                                }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(preset.title, color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(preset.description, color = SlateGray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresetDialog = false }) {
                    Text("Close", color = SlateGray)
                }
            }
        )
    }
}
