package id.my.mub.ui.bughost

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.service.NativeCoreBridge
import id.my.mub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun BugHostLabScreen(
    currentProfile: VpnProfile,
    onSaveProfile: (VpnProfile) -> Unit,
    onNavigateBack: () -> Unit
) {
    var selectedProtocol by remember { mutableStateOf(currentProfile.protocol) }
    var bugHostInput by remember { mutableStateOf(currentProfile.bugHostSNI) }
    var pacingRate by remember { mutableFloatStateOf(currentProfile.brutalRateMbps.toFloat()) }
    var isWireSpeed by remember { mutableStateOf(currentProfile.brutalRateMbps == 0) }
    var poolSize by remember { mutableFloatStateOf(currentProfile.poolConcurrency.toFloat()) }

    var frontDomainInject by remember { mutableStateOf(true) }
    var splitDecoy by remember { mutableStateOf(false) }
    var delaySplit by remember { mutableStateOf(false) }

    var udpObfs by remember { mutableStateOf(currentProfile.udpObfsPassword) }
    var udpPortRange by remember { mutableStateOf(currentProfile.udpPortHopRange) }
    var fakeDns53 by remember { mutableStateOf(false) }

    var probeResult by remember { mutableStateOf("200 OK - Whitelisted") }
    var isProbing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceCard)
                    .clickable { onNavigateBack() },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "←", color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = "Bug-Host & Protocol Tuning",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PureWhite
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- 1. Protocol Switcher ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Protocol Switcher", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val protocols = listOf(
                        ProtocolType.T_BRUTAL to "T-Brutal",
                        ProtocolType.ZIVPN_UDP to "ZiVPN UDP",
                        ProtocolType.HYSTERIA_2 to "Hysteria 2",
                        ProtocolType.VLESS_TCP to "VLESS TCP",
                        ProtocolType.SSH_PAYLOAD to "SSH Payload"
                    )

                    protocols.forEach { (proto, label) ->
                        val isSelected = selectedProtocol == proto
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonViolet else Color(0x15FFFFFF))
                                .clickable { selectedProtocol = proto }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) PureWhite else SlateGray
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- UDP Anti-DPI Obfuscation Panel (ZiVPN / Hysteria) ---
        if (selectedProtocol == ProtocolType.ZIVPN_UDP || selectedProtocol == ProtocolType.HYSTERIA_2) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "UDP Anti-DPI Obfuscation (ZiVPN / Hysteria)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = ElectricCyan
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(text = "Salamander Obfs Password (XOR Scramble)", color = SlateGray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = udpObfs,
                        onValueChange = { udpObfs = it },
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
                    Text(text = "UDP Port Range (Multi-Port DNAT Bypass)", color = SlateGray, fontSize = 12.sp)
                    OutlinedTextField(
                        value = udpPortRange,
                        onValueChange = { udpPortRange = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g., 6000:19999 or 5667", color = SlateGray, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = SurfaceCardBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fakeDns53, onCheckedChange = { fakeDns53 = it })
                        Text(text = "Tunnel over Port 53 (Fake DNS Datagram Header)", color = PureWhite, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        // --- 2. Bug-Host SNI Field ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Bug-Host SNI", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = bugHostInput,
                    onValueChange = { bugHostInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. images.vodafone.co.uk", color = SlateGray) },
                    trailingIcon = {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x3300FFA3))
                                .clickable {
                                    isProbing = true
                                    coroutineScope.launch {
                                        val res = NativeCoreBridge.probeBugHost("https://$bugHostInput", bugHostInput)
                                        probeResult = if (res.isWhitelisted) "${res.statusCode} OK - Whitelisted" else "Status: ${res.statusCode}"
                                        isProbing = false
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isProbing) "Probing..." else probeResult,
                                fontSize = 11.sp,
                                color = CyberMint,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = SurfaceCardBorder,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- 3. Brutal Pacing Rate ---
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
                    Text(text = "Brutal Pacing Rate", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Wire-speed", fontSize = 12.sp, color = SlateGray)
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = isWireSpeed,
                            onCheckedChange = {
                                isWireSpeed = it
                                if (it) pacingRate = 0f
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = ElectricCyan
                            )
                        )
                    }
                }

                if (!isWireSpeed) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        value = pacingRate,
                        onValueChange = { pacingRate = it },
                        valueRange = 10f..200f,
                        steps = 18,
                        colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                    )
                    Text(
                        text = "${pacingRate.toInt()} Mbps",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- 4. TCP Connection Pool ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "TCP Connection Pool", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
                    Text(text = "${poolSize.toInt()} Concurrency Lanes", fontSize = 13.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = poolSize,
                    onValueChange = { poolSize = it },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(thumbColor = NeonViolet, activeTrackColor = NeonViolet)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- 5. Carrier Payload Engine ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Carrier Payload Engine", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = frontDomainInject, onCheckedChange = { frontDomainInject = it })
                    Text(text = "Front-Domain Inject", color = PureWhite, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = splitDecoy, onCheckedChange = { splitDecoy = it })
                    Text(text = "Split Decoy ([split])", color = PureWhite, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = delaySplit, onCheckedChange = { delaySplit = it })
                    Text(text = "Delay-Split ([delay_split])", color = PureWhite, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Save & Deploy Button ---
        Button(
            onClick = {
                val updated = currentProfile.copy(
                    protocol = selectedProtocol,
                    bugHostSNI = bugHostInput.trim(),
                    poolConcurrency = poolSize.toInt(),
                    brutalRateMbps = if (isWireSpeed) 0 else pacingRate.toInt(),
                    udpObfsPassword = udpObfs.trim(),
                    udpPortHopRange = udpPortRange.trim()
                )
                onSaveProfile(updated)
                onNavigateBack()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = NeonViolet)
        ) {
            Text(text = "Save & Deploy Configuration", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PureWhite)
        }
    }
}
