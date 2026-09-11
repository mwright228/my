package id.my.mub.ui.warden

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.window.Dialog
import id.my.mub.data.ConfigParser
import id.my.mub.data.ProfileStore
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.ui.theme.*
import java.util.UUID

@Composable
fun WardenServersScreen(
    activeProfile: VpnProfile,
    onSelectProfile: (VpnProfile) -> Unit
) {
    val context = LocalContext.current
    var showXrayEditor by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteInput by remember { mutableStateOf("") }

    // User-saved profiles from store
    var storedProfiles by remember { mutableStateOf(ProfileStore.getAllProfiles()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Screen Title
        Text(
            text = "Servers",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = WardenText
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Xray Profiles — import, scan, or manually add
        run {
            // Quick action bar: Paste link, Scan QR, Manual
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.ContentPaste,
                        label = "Paste link",
                        onClick = {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipMgr?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                            if (clipText.isNotBlank()) {
                                val parsed = ConfigParser.parseUri(clipText)
                                if (parsed != null) {
                                    ProfileStore.saveProfile(parsed)
                                    ProfileStore.setActiveProfileId(parsed.id)
                                    storedProfiles = ProfileStore.getAllProfiles()
                                    onSelectProfile(parsed)
                                    Toast.makeText(context, "Imported: ${parsed.name}", Toast.LENGTH_SHORT).show()
                                } else {
                                    pasteInput = clipText
                                    showPasteDialog = true
                                }
                            } else {
                                showPasteDialog = true
                            }
                        }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.QrCode,
                        label = "Scan QR",
                        onClick = {
                            showPasteDialog = true
                        }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.Add,
                        label = "Manual",
                        color = WardenMint,
                        onClick = {
                            editingProfile = null
                            showXrayEditor = true
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subscription Bar
            WardenCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = WardenMutedDim,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "subscribe://sub.warden.link/a91f...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WardenMutedDim
                        )
                    }

                    Text(
                        text = "Sync",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WardenIndigo,
                        modifier = Modifier
                            .clickable {
                                storedProfiles = ProfileStore.getAllProfiles()
                                Toast.makeText(context, "Profiles synced (${storedProfiles.size} active)", Toast.LENGTH_SHORT).show()
                            }
                            .padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section: Saved profiles
            WardenSectionLabel(
                title = "Saved profiles",
                rightText = "${storedProfiles.size} profiles"
            )

            // Custom Stored Profiles (if any)
            if (storedProfiles.isNotEmpty()) {
                for (prof in storedProfiles) {
                    val isActive = prof.id == activeProfile.id
                    val protoColor = when (prof.protocol) {
                        ProtocolType.VLESS_WS, ProtocolType.VLESS_TCP -> WardenIndigo
                        ProtocolType.SHADOWSOCKS_2022 -> WardenMint
                        ProtocolType.SSH_PAYLOAD -> WardenAmber
                        else -> WardenViolet
                    }

                    WardenCard(
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .clickable {
                                ProfileStore.setActiveProfileId(prof.id)
                                onSelectProfile(prof)
                            },
                        padding = PaddingValues(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("⚡", fontSize = 18.sp)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = prof.name.ifBlank { prof.serverHost },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = WardenText,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        WardenChip(text = prof.protocol.displayName, color = protoColor)
                                        if (prof.bugHostSNI.isNotBlank() && !prof.bugHostSNI.equals(prof.serverHost, ignoreCase = true)) {
                                            WardenChip(text = "SNI: ${prof.bugHostSNI}", color = WardenAmber)
                                        }
                                        if (prof.allowInsecureTLS) {
                                            WardenChip(text = "Insecure", color = WardenRed)
                                        }
                                    }
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        editingProfile = prof
                                        showXrayEditor = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit Profile",
                                        tint = WardenMint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        ProfileStore.deleteProfile(prof.id)
                                        storedProfiles = ProfileStore.getAllProfiles()
                                        if (isActive) {
                                            val next = storedProfiles.firstOrNull() ?: VpnProfile()
                                            onSelectProfile(next)
                                        }
                                        Toast.makeText(context, "Deleted ${prof.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Profile",
                                        tint = WardenMutedDim,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                if (isActive) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Active",
                                        tint = WardenMint,
                                        modifier = Modifier.size(20.dp).padding(start = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Empty state when no profiles yet
            if (storedProfiles.isEmpty()) {
                WardenCard(padding = PaddingValues(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔒", fontSize = 32.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No profiles yet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WardenText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Paste a VLESS / VMess / Trojan URI or add one manually to get started.",
                            fontSize = 11.5.sp,
                            color = WardenMutedDim
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // Modal Editors
    if (showXrayEditor) {
        WardenXrayEditor(
            initialProfile = editingProfile,
            onClose = {
                showXrayEditor = false
                editingProfile = null
            },
            onSave = { saved ->
                ProfileStore.saveProfile(saved)
                ProfileStore.setActiveProfileId(saved.id)
                storedProfiles = ProfileStore.getAllProfiles()
                onSelectProfile(saved)
                showXrayEditor = false
                editingProfile = null
                Toast.makeText(context, "Saved profile: ${saved.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }


    // Paste / Enter URI Dialog
    if (showPasteDialog) {
        Dialog(onDismissRequest = { showPasteDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = WardenScreen),
                border = androidx.compose.foundation.BorderStroke(1.dp, WardenBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Import Profile or URI", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WardenText)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        label = { Text("vless://, vmess://, ss://, trojan://") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WardenMint,
                            unfocusedBorderColor = WardenBorder,
                            focusedContainerColor = WardenSurface2,
                            unfocusedContainerColor = WardenSurface2,
                            focusedTextColor = WardenText,
                            unfocusedTextColor = WardenText
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showPasteDialog = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = WardenSurface2)
                        ) {
                            Text("Cancel", color = WardenMutedDim)
                        }
                        Button(
                            onClick = {
                                val parsed = ConfigParser.parseUri(pasteInput.trim())
                                if (parsed != null) {
                                    ProfileStore.saveProfile(parsed)
                                    ProfileStore.setActiveProfileId(parsed.id)
                                    storedProfiles = ProfileStore.getAllProfiles()
                                    onSelectProfile(parsed)
                                    showPasteDialog = false
                                    Toast.makeText(context, "Imported ${parsed.name}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not parse URI format", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = WardenMint)
                        ) {
                            Text("Import", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

}
