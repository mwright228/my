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

// SSH account model — user populates their own, no hardcoded defaults
data class SshAccount(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String = "",
    val mode: String, // e.g. "SSH · TLS", "SSH · WS", "Dropbear"
    val flag: String = "🌐"
)

data class RemoteProxyItem(
    val label: String,
    val value: String
)

@Composable
fun WardenServersScreen(
    activeProfile: VpnProfile,
    onSelectProfile: (VpnProfile) -> Unit
) {
    val context = LocalContext.current

    // Tab: "xray" or "ssh"
    var mode by remember { mutableStateOf("xray") }

    // Xray state
    var showXrayEditor by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteInput by remember { mutableStateOf("") }
    var storedProfiles by remember { mutableStateOf(ProfileStore.getAllProfiles()) }

    // SSH state — starts EMPTY, user adds their own accounts
    var sshAccounts by remember { mutableStateOf<List<SshAccount>>(emptyList()) }
    var showSshEditor by remember { mutableStateOf(false) }
    var editingSsh by remember { mutableStateOf<SshAccount?>(null) }

    // UDPGW
    var udpgwOn by remember { mutableStateOf(false) }
    var udpgwServer by remember { mutableStateOf("127.0.0.1:7300") }

    // Remote proxy
    var remoteProxies by remember { mutableStateOf<List<RemoteProxyItem>>(emptyList()) }
    var activeProxyIdx by remember { mutableStateOf(0) }
    var showAddProxyDialog by remember { mutableStateOf(false) }
    var newProxyLabel by remember { mutableStateOf("") }
    var newProxyValue by remember { mutableStateOf("") }

    // Payload editor (ZiVPN style)
    var payloadScheme by remember { mutableStateOf("HTTP") }
    var payloadHttp by remember {
        mutableStateOf("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]")
    }
    var payloadHttps by remember {
        mutableStateOf("CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]")
    }
    var frontSni by remember { mutableStateOf("") }
    var routeViaRemoteProxy by remember { mutableStateOf(false) }

    // Port forwarding
    var portForwardType by remember { mutableStateOf("Dynamic (SOCKS)") }
    var portForwardPort by remember { mutableStateOf("1080") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Servers",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = WardenText
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Tab selector
        WardenSelectRow(
            options = listOf("Xray / V2Ray", "SSH / Payload"),
            selected = if (mode == "xray") "Xray / V2Ray" else "SSH / Payload",
            onSelect = { mode = if (it == "Xray / V2Ray") "xray" else "ssh" },
            color = WardenMint
        )

        Spacer(modifier = Modifier.height(10.dp))

        // ── XRAY TAB ──────────────────────────────────────────────────────────
        if (mode == "xray") {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.ContentPaste,
                        label = "Paste URI",
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
                        onClick = { showPasteDialog = true }
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

            // Subscription sync bar
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
                            text = "Subscription URL…",
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
                                Toast.makeText(context, "Synced (${storedProfiles.size} profiles)", Toast.LENGTH_SHORT).show()
                            }
                            .padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            WardenSectionLabel(
                title = "Profiles",
                rightText = "${storedProfiles.size} saved"
            )

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
                                        contentDescription = "Edit",
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
                                        contentDescription = "Delete",
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
            } else {
                WardenCard(padding = PaddingValues(24.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔒", fontSize = 30.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No profiles yet",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WardenText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Paste a VLESS / VMess / Trojan / SS URI above, or tap Manual to add one.",
                            fontSize = 11.sp,
                            color = WardenMutedDim
                        )
                    }
                }
            }

        // ── SSH / PAYLOAD TAB ─────────────────────────────────────────────────
        } else {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.PersonAdd,
                        label = "Add account",
                        color = WardenViolet,
                        onClick = {
                            editingSsh = null
                            showSshEditor = true
                        }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.ImportExport,
                        label = "Import SSH",
                        color = WardenViolet,
                        onClick = { showPasteDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            WardenSectionLabel(
                title = "SSH accounts",
                rightText = "${sshAccounts.size} accounts"
            )

            if (sshAccounts.isNotEmpty()) {
                WardenCard(padding = PaddingValues(0.dp)) {
                    sshAccounts.forEachIndexed { i, a ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val sshProf = VpnProfile(
                                        id = UUID.randomUUID().toString(),
                                        name = a.label,
                                        serverHost = a.host,
                                        serverIp = a.host,
                                        serverPort = a.port,
                                        protocol = ProtocolType.SSH_PAYLOAD,
                                        sshUser = a.user,
                                        sshPassword = a.password
                                    )
                                    ProfileStore.saveProfile(sshProf)
                                    ProfileStore.setActiveProfileId(sshProf.id)
                                    onSelectProfile(sshProf)
                                    Toast.makeText(context, "Active: ${a.label}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(a.flag, fontSize = 18.sp)
                                Column {
                                    Text(
                                        a.label,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = WardenText
                                    )
                                    Text(
                                        "${a.user}@${a.host}:${a.port}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = WardenMutedDim
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                WardenChip(text = a.mode, color = WardenViolet)
                                IconButton(
                                    onClick = {
                                        editingSsh = a
                                        showSshEditor = true
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = WardenMint, modifier = Modifier.size(14.dp))
                                }
                                IconButton(
                                    onClick = { sshAccounts = sshAccounts.filter { it.id != a.id } },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WardenMutedDim, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        if (i < sshAccounts.size - 1) {
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                        }
                    }
                }
            } else {
                WardenCard(padding = PaddingValues(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("🔑", fontSize = 28.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("No SSH accounts", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap \"Add account\" to enter your SSH server credentials.",
                            fontSize = 11.sp,
                            color = WardenMutedDim
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // UDPGW card
            WardenCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = WardenIndigo, modifier = Modifier.size(14.dp))
                        Column {
                            Text("UDP support (UDPGW)", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                            Text("Enables UDP / game traffic over SSH", fontSize = 10.sp, color = WardenMutedDim)
                        }
                    }
                    WardenToggle(on = udpgwOn, onToggle = { udpgwOn = !udpgwOn }, color = WardenIndigo)
                }
                if (udpgwOn) {
                    Spacer(modifier = Modifier.height(10.dp))
                    WardenField(label = "UDPGW server address", value = udpgwServer, onValueChange = { udpgwServer = it })
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Remote proxy manager
            WardenCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Remote proxy servers", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                    Row(
                        modifier = Modifier.clickable { showAddProxyDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = WardenMint, modifier = Modifier.size(13.dp))
                        Text("Add", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = WardenMint)
                    }
                }

                if (remoteProxies.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No proxy servers added. Tap Add to enter a Cloudflare / Squid / HTTP proxy.",
                        fontSize = 10.5.sp,
                        color = WardenMutedDim
                    )
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                    remoteProxies.forEachIndexed { i, p ->
                        val isActiveProxy = i == activeProxyIdx
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { activeProxyIdx = i },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isActiveProxy) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = WardenMint, modifier = Modifier.size(14.dp))
                                } else {
                                    Spacer(modifier = Modifier.size(14.dp))
                                }
                                Column {
                                    Text(p.label, fontSize = 11.5.sp, color = WardenText)
                                    Text(p.value, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = WardenMutedDim)
                                }
                            }
                            IconButton(
                                onClick = {
                                    remoteProxies = remoteProxies.filterIndexed { idx, _ -> idx != i }
                                    if (activeProxyIdx >= remoteProxies.size) activeProxyIdx = 0
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WardenMutedDim, modifier = Modifier.size(13.dp))
                            }
                        }
                        if (i < remoteProxies.size - 1) {
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ZiVPN-style payload editor
            WardenCard {
                Text("HTTP payload editor", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                Spacer(modifier = Modifier.height(2.dp))
                Text("ZiVPN-style custom payload for SSH injection", fontSize = 10.sp, color = WardenMutedDim)
                Spacer(modifier = Modifier.height(10.dp))

                WardenSelectRow(
                    options = listOf("HTTP", "HTTPS"),
                    selected = payloadScheme,
                    onSelect = { payloadScheme = it },
                    color = WardenMint
                )

                Spacer(modifier = Modifier.height(8.dp))
                WardenField(
                    label = "SNI / front domain (bug host)",
                    value = frontSni,
                    onValueChange = { frontSni = it }
                )

                val currentPayload = if (payloadScheme == "HTTP") payloadHttp else payloadHttps
                Text("Payload template", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(bottom = 4.dp))
                OutlinedTextField(
                    value = currentPayload,
                    onValueChange = {
                        if (payloadScheme == "HTTP") payloadHttp = it else payloadHttps = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WardenMint,
                        unfocusedBorderColor = WardenBorder,
                        focusedContainerColor = WardenSurface2,
                        unfocusedContainerColor = WardenSurface2,
                        focusedTextColor = WardenMint,
                        unfocusedTextColor = WardenMint
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Text(
                    text = "tokens: [host] [port] [host_port] [crlf] [cr] [lf] [split]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    color = WardenMutedDim,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Route payload via remote proxy", fontSize = 11.5.sp, color = WardenText)
                    WardenToggle(on = routeViaRemoteProxy, onToggle = { routeViaRemoteProxy = !routeViaRemoteProxy })
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Port forwarding
            WardenCard {
                Text("Port forwarding", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                Spacer(modifier = Modifier.height(6.dp))
                WardenSelectRow(
                    options = listOf("Local", "Remote", "Dynamic (SOCKS)"),
                    selected = portForwardType,
                    onSelect = { portForwardType = it },
                    color = WardenIndigo
                )
                Spacer(modifier = Modifier.height(6.dp))
                WardenField(label = "Local bind port", value = portForwardPort, onValueChange = { portForwardPort = it })
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    // ── DIALOGS ────────────────────────────────────────────────────────────────

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
                Toast.makeText(context, "Saved: ${saved.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showSshEditor) {
        WardenSSHEditor(
            onClose = {
                showSshEditor = false
                editingSsh = null
            },
            onSave = { saved ->
                ProfileStore.saveProfile(saved)
                ProfileStore.setActiveProfileId(saved.id)
                storedProfiles = ProfileStore.getAllProfiles()
                onSelectProfile(saved)
                val displayAccount = SshAccount(
                    label = saved.name,
                    host = saved.serverHost,
                    port = saved.serverPort,
                    user = saved.sshUser,
                    password = saved.sshPassword,
                    mode = "SSH · Custom",
                    flag = "🌐"
                )
                sshAccounts = if (editingSsh != null) {
                    sshAccounts.map { if (it.id == editingSsh!!.id) displayAccount.copy(id = editingSsh!!.id) else it }
                } else {
                    sshAccounts + displayAccount
                }
                showSshEditor = false
                editingSsh = null
                Toast.makeText(context, "Saved SSH: ${saved.name}", Toast.LENGTH_SHORT).show()
            }
        )
    }

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
                    Text("Import Profile URI", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WardenText)
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
                                    Toast.makeText(context, "Could not parse URI", Toast.LENGTH_SHORT).show()
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

    if (showAddProxyDialog) {
        Dialog(onDismissRequest = { showAddProxyDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = WardenScreen),
                border = androidx.compose.foundation.BorderStroke(1.dp, WardenBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Add Remote Proxy", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WardenText)
                    Spacer(modifier = Modifier.height(10.dp))
                    WardenField(label = "Label (e.g. Cloudflare)", value = newProxyLabel, onValueChange = { newProxyLabel = it })
                    WardenField(label = "Host:Port (e.g. 104.16.0.1:443)", value = newProxyValue, onValueChange = { newProxyValue = it })
                    Spacer(modifier = Modifier.height(14.dp))
                    WardenSaveButton(
                        label = "Save proxy",
                        onClick = {
                            if (newProxyValue.isNotBlank()) {
                                remoteProxies = remoteProxies + RemoteProxyItem(
                                    newProxyLabel.ifBlank { "Proxy ${remoteProxies.size + 1}" },
                                    newProxyValue.trim()
                                )
                                newProxyLabel = ""
                                newProxyValue = ""
                                showAddProxyDialog = false
                            }
                        }
                    )
                }
            }
        }
    }
}
