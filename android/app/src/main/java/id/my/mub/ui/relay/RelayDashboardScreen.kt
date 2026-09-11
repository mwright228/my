package id.my.mub.ui.relay

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.ConfigParser
import id.my.mub.data.ProfileStore
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

    var allProfiles by remember { mutableStateOf(ProfileStore.getAllProfiles()) }
    var activeProfile by remember { mutableStateOf(currentProfile) }

    // Dialog states
    var showProtocolSelection by remember { mutableStateOf(false) }
    var addingWithProtocol by remember { mutableStateOf<ProtocolType?>(null) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshProfiles() {
        val loaded = ProfileStore.getAllProfiles()
        allProfiles = loaded
        val current = ProfileStore.getActiveProfile()
        activeProfile = current
        onUpdateProfile(current)
    }

    if (showProtocolSelection) {
        ProtocolSelectionDialog(
            onDismiss = { showProtocolSelection = false },
            onProtocolSelected = { selectedProto ->
                showProtocolSelection = false
                addingWithProtocol = selectedProto
            }
        )
    }

    val protoToAdd = addingWithProtocol
    if (protoToAdd != null) {
        ProtocolConfigDialog(
            initialProtocol = protoToAdd,
            initialProfile = null,
            onDismiss = { addingWithProtocol = null },
            onSave = { newProfile ->
                ProfileStore.saveProfile(newProfile)
                ProfileStore.setActiveProfileId(newProfile.id)
                refreshProfiles()
                addingWithProtocol = null
                statusMessage = "Added ${newProfile.name}"
            }
        )
    }

    val currentEditing = editingProfile
    if (currentEditing != null) {
        ProtocolConfigDialog(
            initialProtocol = currentEditing.protocol,
            initialProfile = currentEditing,
            onDismiss = { editingProfile = null },
            onSave = { updatedProfile ->
                ProfileStore.saveProfile(updatedProfile)
                refreshProfiles()
                editingProfile = null
                statusMessage = "Updated ${updatedProfile.name}"
            }
        )
    }

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
                    text = "Relay Manager",
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
        },
        bottomBar = {
            // Action Bar (Style B Mockup: Paste Config, Add Manually, View Logs)
            Surface(
                color = SurfaceCard,
                tonalElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Paste Config
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                            if (!clipText.isNullOrBlank()) {
                                val parsed = ConfigParser.parseUri(clipText)
                                if (parsed != null) {
                                    ProfileStore.saveProfile(parsed)
                                    ProfileStore.setActiveProfileId(parsed.id)
                                    refreshProfiles()
                                    statusMessage = "Imported: ${parsed.name}"
                                } else {
                                    statusMessage = "Invalid configuration URI"
                                }
                            } else {
                                statusMessage = "Clipboard is empty"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BgMain),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Paste Config", fontSize = 12.sp, color = TextPrimary)
                    }

                    // 2. Add Manually
                    Button(
                        onClick = { showProtocolSelection = true },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Manually", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    // 3. View Logs
                    Button(
                        onClick = onOpenConsole,
                        colors = ButtonDefaults.buttonColors(containerColor = BgMain),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("View Logs", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Status Snackbar / Message
            if (statusMessage != null) {
                Text(
                    text = statusMessage ?: "",
                    fontSize = 12.sp,
                    color = CyberMint,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // 1. Hero Card: Connected Host & Big Tactile Switch (Style B Mockup)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isConnected -> SuccessGreen
                                                isConnecting -> WarningAmber
                                                else -> ErrorRed
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        isConnected -> "Connected"
                                        isConnecting -> "Connecting..."
                                        else -> "Disconnected"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when {
                                        isConnected -> SuccessGreen
                                        isConnecting -> WarningAmber
                                        else -> TextSecondary
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = activeProfile.serverHost.ifBlank { activeProfile.serverIp }.ifBlank { "No Host Selected" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Tactile Toggle Switch
                        Switch(
                            checked = isConnected || isConnecting,
                            onCheckedChange = { onToggleRelay() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SuccessGreen,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = BgMain
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Download / Upload Rate Tiles (Style B Mockup)
                    val connState = vpnState as? VpnState.Connected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Download Tile
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgMain)
                                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("DOWNLOAD", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                                    Text("↓", fontSize = 12.sp, color = CyberMint)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (connState != null) String.format("%.2f MB/s", connState.rxSpeedMbps / 8.0) else "0.00 MB/s",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }

                        // Upload Tile
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BgMain)
                                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("UPLOAD", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
                                    Text("↑", fontSize = 12.sp, color = AccentPrimary)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (connState != null) String.format("%.2f MB/s", connState.txSpeedMbps / 8.0) else "0.00 MB/s",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Node List Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SAVED NODES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "${allProfiles.size} available",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Node List Items (Style B Mockup)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(allProfiles, key = { it.id }) { node ->
                    val isSelected = node.id == activeProfile.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                ProfileStore.setActiveProfileId(node.id)
                                refreshProfiles()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) SurfaceCard.copy(alpha = 0.95f) else SurfaceCard.copy(alpha = 0.6f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) AccentPrimary else SurfaceCardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = node.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Protocol Badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(BgMain)
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = node.protocol.displayName.split(" ").first(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberMint
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${node.serverHost}:${node.serverPort}",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Actions: Active check, Edit, Delete
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                IconButton(
                                    onClick = { editingProfile = node },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                if (allProfiles.size > 1) {
                                    IconButton(
                                        onClick = {
                                            ProfileStore.deleteProfile(node.id)
                                            refreshProfiles()
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = ErrorRed.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
