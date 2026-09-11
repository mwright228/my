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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.ConfigParser
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(
    currentProfile: VpnProfile,
    onSelectProfile: (VpnProfile) -> Unit,
    onAddProfile: (VpnProfile) -> Unit,
    onEditProfile: (VpnProfile) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    // User saved profiles list (starts empty or with user's configured profile)
    var profiles by remember {
        mutableStateOf(
            if (currentProfile.serverHost.isNotBlank() || currentProfile.serverIp.isNotBlank()) {
                listOf(currentProfile)
            } else {
                emptyList()
            }
        )
    }

    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // --- Header Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Profiles Vault",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
                Text(
                    text = "${profiles.size} Nodes Available",
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

        Spacer(modifier = Modifier.height(16.dp))

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
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Config Import & Subscription",
                        color = PureWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Supports vless://, tbrutal://, zivpn://, ss://, http://",
                        color = SlateGray,
                        fontSize = 11.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x2200F0FF))
                        .border(1.dp, Color(0x5500F0FF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .clickable { showImportDialog = true }
                ) {
                    Text(text = "Paste Link", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Profiles List ---
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (profiles.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No Configurations Saved", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Import a link above or create a new node in the Config tab.", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
            } else {
                items(profiles, key = { it.id }) { item ->
                val isSelected = item.id == currentProfile.id

                ProfileCard(
                    profile = item,
                    isSelected = isSelected,
                    onSelect = {
                        onSelectProfile(item)
                    },
                    onEdit = {
                        onEditProfile(item)
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
                        text = "Paste your subscription URL or raw config link (vless://, tbrutal://, zivpn://, ss://, vmess://):",
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
                        maxLines = 4
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
}

@Composable
fun ProfileCard(
    profile: VpnProfile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
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
            containerColor = if (isSelected) Color(0xFF151D2E) else SurfaceCard
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                            .size(18.dp)
                            .clip(CircleShape)
                            .border(2.dp, if (isSelected) ElectricCyan else SlateGray, CircleShape)
                            .padding(2.dp),
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
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${profile.serverHost}:${profile.serverPort}",
                            color = SlateGray,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Edit Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCardElevated)
                            .clickable { onEdit() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Edit", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Metadata pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceCardElevated)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = profile.protocol.displayName,
                        color = PureWhite,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Bug-host SNI pill
                if (profile.bugHostSNI.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x338B5CF6))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "SNI: ${profile.bugHostSNI}",
                            color = PureWhite,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
