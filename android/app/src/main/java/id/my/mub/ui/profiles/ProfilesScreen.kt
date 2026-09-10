package id.my.mub.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import id.my.mub.ui.theme.*

@Composable
fun ProfilesScreen(
    currentProfile: VpnProfile,
    onSelectProfile: (VpnProfile) -> Unit,
    onAddProfile: (VpnProfile) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    // Initial sample list of profiles representing the MUB-X fleet
    var profiles by remember {
        mutableStateOf(
            listOf(
                currentProfile,
                VpnProfile(
                    name = "Jazz Bug-Host Direct",
                    serverHost = "filter.ncnd.jazz.com.pk",
                    serverIp = "212.60.151.69",
                    serverPort = 80,
                    bugHostSNI = "filter.ncnd.jazz.com.pk",
                    protocol = ProtocolType.SSH_PAYLOAD,
                    poolConcurrency = 2,
                    brutalRateMbps = 40,
                    allowInsecureTLS = true
                ),
                VpnProfile(
                    name = "Vodafone Ultra Turbo",
                    serverHost = "gr.mub.my.id",
                    serverIp = "212.60.151.69",
                    serverPort = 443,
                    bugHostSNI = "images.vodafone.co.uk",
                    protocol = ProtocolType.T_BRUTAL,
                    poolConcurrency = 8,
                    brutalRateMbps = 150,
                    allowInsecureTLS = true
                ),
                VpnProfile(
                    name = "ZiVPN UDP Anti-DPI Bypass",
                    serverHost = "gr.mub.my.id",
                    serverIp = "212.60.151.69",
                    serverPort = 5667,
                    bugHostSNI = "",
                    protocol = ProtocolType.ZIVPN_UDP,
                    poolConcurrency = 1,
                    brutalRateMbps = 60,
                    allowInsecureTLS = true,
                    udpObfsPassword = "zivpn",
                    udpPortHopRange = "6000:19999"
                ),
                VpnProfile(
                    name = "Gaming Low-Ping UDP",
                    serverHost = "gr.mub.my.id",
                    serverIp = "212.60.151.69",
                    serverPort = 8443,
                    bugHostSNI = "gr.mub.my.id",
                    protocol = ProtocolType.HYSTERIA_2,
                    poolConcurrency = 4,
                    brutalRateMbps = 100,
                    allowInsecureTLS = false
                ),
                VpnProfile(
                    name = "AmneziaWG Steath WireGuard",
                    serverHost = "gr.mub.my.id",
                    serverIp = "212.60.151.69",
                    serverPort = 51820,
                    bugHostSNI = "",
                    protocol = ProtocolType.AMNEZIA_WG,
                    poolConcurrency = 1,
                    brutalRateMbps = 0,
                    allowInsecureTLS = false
                )
            )
        )
    }

    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // --- Header Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Profiles & Vault",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
                Text(
                    text = "${profiles.size} Nodes Configured",
                    fontSize = 12.sp,
                    color = SlateGray
                )
            }

            Button(
                onClick = { showImportDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = NeonViolet),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(text = "+ Import", color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Subscription Sync Banner ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Subscription Active",
                        color = PureWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Synced from gr.mub.my.id/sub/...",
                        color = SlateGray,
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x2200F0FF))
                        .border(1.dp, Color(0x5500F0FF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable { /* Trigger re-sync */ }
                ) {
                    Text(text = "Sync Now", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Profiles List ---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(profiles, key = { it.id }) { item ->
                val isSelected = item.id == currentProfile.id

                ProfileCard(
                    profile = item,
                    isSelected = isSelected,
                    onSelect = {
                        onSelectProfile(item)
                    },
                    onDelete = {
                        if (profiles.size > 1) {
                            profiles = profiles.filter { it.id != item.id }
                            onDeleteProfile(item.id)
                        }
                    }
                )
            }
        }
    }

    // --- Import Modal Dialog ---
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = SurfaceCard,
            title = {
                Text(
                    text = "Import Config or Subscription",
                    color = PureWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        text = "Paste your subscription URL (e.g., https://gr.mub.my.id/sub/<token>/links.txt) or raw config URL (vless://, tbrutal://, ss://):",
                        color = SlateGray,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("vless:// or https://...", color = SlateGray, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = SurfaceCardBorder,
                            cursorColor = ElectricCyan
                        ),
                        singleLine = false,
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = importInput.trim()
                        if (input.isNotBlank()) {
                            coroutineScope.launch {
                                if ((input.startsWith("http://", true) || input.startsWith("https://", true)) && (input.contains("/sub/") || input.contains(".txt") || input.contains("links"))) {
                                    val fetched = ConfigParser.fetchSubscription(input)
                                    if (fetched.isNotEmpty()) {
                                        profiles = profiles + fetched
                                        onAddProfile(fetched.first())
                                    }
                                } else {
                                    val lines = input.lines()
                                    val newNodes = mutableListOf<VpnProfile>()
                                    for (l in lines) {
                                        ConfigParser.parseUri(l)?.let { newNodes.add(it) }
                                    }
                                    if (newNodes.isNotEmpty()) {
                                        profiles = profiles + newNodes
                                        onAddProfile(newNodes.first())
                                    }
                                }
                                showImportDialog = false
                                importInput = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Text("Import", color = BgObsidian, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("Cancel", color = SlateGray)
                }
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: VpnProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isSelected) ElectricCyan else SurfaceCardBorder
    val borderWidth = if (isSelected) 1.5.dp else 1.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF171E33) else SurfaceCard
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Radio indicator
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (isSelected) ElectricCyan else SlateGray, CircleShape)
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .background(ElectricCyan)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = profile.name,
                            color = PureWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${profile.serverHost}:${profile.serverPort}",
                            color = SlateGray,
                            fontSize = 12.sp
                        )
                    }
                }

                // Active badge
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x3300FFA3))
                            .border(1.dp, Color(0x6600FFA3), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "ACTIVE",
                            color = CyberMint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metadata pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22FFFFFF))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = profile.protocol.displayName,
                        color = PureWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Bug-host SNI pill
                if (profile.bugHostSNI.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x339D4EDD))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SNI: ${profile.bugHostSNI}",
                            color = PureWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Concurrency & Rate
                if (profile.protocol == ProtocolType.T_BRUTAL) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x2200F0FF))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${profile.poolConcurrency}x · ${profile.brutalRateMbps}M",
                            color = ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
