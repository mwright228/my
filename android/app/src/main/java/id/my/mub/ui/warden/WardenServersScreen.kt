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
import androidx.compose.ui.platform.LocalClipboardManager
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

data class ServerPreset(
    val city: String,
    val tag: String,
    val protocol: String,
    val ms: Int,
    val load: Int,
    val flag: String,
    val host: String = "104.21.44.12",
    val port: Int = 443,
    val protoType: ProtocolType = ProtocolType.VLESS_WS
)

val DEFAULT_SERVERS = listOf(
    ServerPreset("Frankfurt", "#3", "VLESS · Reality", 42, 4, "🇩🇪", "de.warden.link", 443, ProtocolType.VLESS_TCP),
    ServerPreset("Amsterdam", "#2", "Trojan · WS", 61, 5, "🇳🇱", "nl.warden.link", 443, ProtocolType.VLESS_WS),
    ServerPreset("London", "#2", "VLESS · XHTTP", 55, 4, "🇬🇧", "uk.warden.link", 443, ProtocolType.VLESS_WS),
    ServerPreset("Paris", "#1", "Shadowsocks · 2022", 70, 3, "🇫🇷", "fr.warden.link", 8443, ProtocolType.SHADOWSOCKS_2022),
    ServerPreset("Singapore", "#1", "Hysteria2", 88, 3, "🇸🇬", "sg.warden.link", 443, ProtocolType.VLESS_WS),
    ServerPreset("Tokyo", "#4", "TUIC v5", 120, 2, "🇯🇵", "jp.warden.link", 443, ProtocolType.VLESS_WS),
    ServerPreset("Toronto", "#1", "VLESS · AnyTLS", 132, 2, "🇨🇦", "ca.warden.link", 443, ProtocolType.VLESS_TCP),
    ServerPreset("New York", "#1", "VMess · gRPC", 145, 3, "🇺🇸", "us.warden.link", 443, ProtocolType.VLESS_WS)
)

data class SshAccount(
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val mode: String,
    val days: Int,
    val flag: String
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
    var mode by remember { mutableStateOf("xray") } // "xray" or "ssh"
    var showXrayEditor by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var showSshEditor by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteInput by remember { mutableStateOf("") }

    // User-saved profiles from store
    var storedProfiles by remember { mutableStateOf(ProfileStore.getAllProfiles()) }

    // SSH state
    var sshAccounts by remember {
        mutableStateOf(
            listOf(
                SshAccount("SG-Free-03", "sg3.sshcloud.net", 443, "freessh21", "SSH · TLS", 6, "🇸🇬"),
                SshAccount("DE-Premium-1", "de1.sshwarden.net", 22, "warden_de1", "SSH · WS", 28, "🇩🇪"),
                SshAccount("US-Dropbear", "us-drop.sshwarden.net", 443, "drop_us02", "Dropbear", 3, "🇺🇸")
            )
        )
    }

    var udpgwOn by remember { mutableStateOf(true) }
    var remoteProxies by remember {
        mutableStateOf(
            listOf(
                RemoteProxyItem("Cloudflare edge · 443", "104.16.123.96:443"),
                RemoteProxyItem("Cloudflare edge · 80", "104.16.123.96:80"),
                RemoteProxyItem("Local Squid relay", "0.0.0.0:8080")
            )
        )
    }
    var activeProxyIdx by remember { mutableStateOf(0) }
    var showAddProxyDialog by remember { mutableStateOf(false) }
    var newProxyLabel by remember { mutableStateOf("") }
    var newProxyValue by remember { mutableStateOf("") }

    var payloadScheme by remember { mutableStateOf("HTTP") }
    var payloadHttp by remember {
        mutableStateOf("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]")
    }
    var payloadHttps by remember {
        mutableStateOf("CONNECT [host_port] HTTP/1.1[crlf]Host: [host][crlf]Connection: Keep-Alive[crlf][crlf]")
    }
    var routeViaRemoteProxy by remember { mutableStateOf(true) }
    var frontSni by remember { mutableStateOf("zoom.us") }

    var portForwardType by remember { mutableStateOf("Dynamic (SOCKS)") }
    var portForwardPort by remember { mutableStateOf("1080") }

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

        // Mode Selector: Xray profiles vs SSH accounts
        WardenSelectRow(
            options = listOf("Xray profiles", "SSH accounts"),
            selected = if (mode == "xray") "Xray profiles" else "SSH accounts",
            onSelect = { mode = if (it == "Xray profiles") "xray" else "ssh" },
            color = WardenMint
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (mode == "xray") {
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

            // Section: Smart select
            WardenSectionLabel(
                title = "Smart select",
                rightText = "${storedProfiles.size + DEFAULT_SERVERS.size} profiles"
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
                                            WardenChip(text = "Insecure", color = WardenRose)
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

            // Presets Server List
            for (srv in DEFAULT_SERVERS) {
                val isActive = activeProfile.serverHost == srv.host && activeProfile.name.contains(srv.city)
                val protoColor = when {
                    srv.protocol.contains("Reality") || srv.protocol.contains("XHTTP") || srv.protocol.contains("TUIC") -> WardenIndigo
                    srv.protocol.contains("WS") || srv.protocol.contains("2022") -> WardenMint
                    srv.protocol.contains("Hysteria") -> WardenAmber
                    else -> WardenViolet
                }

                WardenCard(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clickable {
                            val presetProf = VpnProfile(
                                id = UUID.randomUUID().toString(),
                                name = "${srv.city} ${srv.tag}",
                                serverHost = srv.host,
                                serverIp = srv.host,
                                serverPort = srv.port,
                                protocol = srv.protoType,
                                bugHostSNI = srv.host,
                                wsPath = "/vless-ws",
                                wsHost = srv.host,
                                userUUID = UUID.randomUUID().toString()
                            )
                            ProfileStore.saveProfile(presetProf)
                            ProfileStore.setActiveProfileId(presetProf.id)
                            storedProfiles = ProfileStore.getAllProfiles()
                            onSelectProfile(presetProf)
                            Toast.makeText(context, "Selected: ${srv.city} ${srv.tag}", Toast.LENGTH_SHORT).show()
                        },
                    padding = PaddingValues(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(srv.flag, fontSize = 18.sp)
                            Column {
                                Text(
                                    text = "${srv.city} ${srv.tag}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WardenText
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                WardenChip(text = srv.protocol, color = protoColor)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${srv.ms}ms",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = WardenText
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                WardenLoadBars(load = srv.load, color = protoColor)
                            }

                            if (isActive) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = WardenMint,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = WardenMutedDim,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

        } else {
            // SSH Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.PersonAdd,
                        label = "Add account",
                        color = WardenViolet,
                        onClick = { showSshEditor = true }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    WardenQuickAction(
                        icon = Icons.Default.Refresh,
                        label = "Free trial",
                        color = WardenViolet,
                        onClick = {
                            val newTrial = SshAccount("Trial-Auto-SG", "trial.sshcloud.net", 443, "trial_usr", "SSH · TLS", 30, "🇸🇬")
                            sshAccounts = listOf(newTrial) + sshAccounts
                            Toast.makeText(context, "30-Day Free Trial SSH added", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SSH Accounts List
            WardenSectionLabel(
                title = "Your SSH accounts",
                rightText = "${sshAccounts.size} accounts"
            )
            WardenCard(padding = PaddingValues(0.dp)) {
                sshAccounts.forEachIndexed { i, a ->
                    val isLow = a.days <= 5
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
                                    sshPassword = "password"
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(a.flag, fontSize = 18.sp)
                            Column {
                                Text(a.label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                                Text("${a.user}@${a.host}:${a.port}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = WardenMutedDim)
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            WardenChip(text = a.mode, color = WardenViolet)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Icon(Icons.Default.AccessTime, contentDescription = null, tint = if (isLow) WardenRed else WardenMutedDim, modifier = Modifier.size(10.dp))
                                Text("${a.days}d left", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (isLow) WardenRed else WardenMutedDim)
                            }
                        }
                    }

                    if (i < sshAccounts.size - 1) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // UDPGW Card
            WardenCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = WardenIndigo, modifier = Modifier.size(14.dp))
                        Text("UDP support (UDPGW)", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                    }
                    WardenToggle(on = udpgwOn, onToggle = { udpgwOn = !udpgwOn }, color = WardenIndigo)
                }
                if (udpgwOn) {
                    Spacer(modifier = Modifier.height(10.dp))
                    WardenField(label = "UDPGW server", value = "127.0.0.1:7300")
                    WardenField(label = "Accept non-udpgw traffic", value = "On (fallback to TCP-only apps)")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Remote Proxy Manager
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

                Spacer(modifier = Modifier.height(8.dp))

                remoteProxies.forEachIndexed { i, p ->
                    val isActive = i == activeProxyIdx
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
                            if (isActive) {
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
                                if (activeProxyIdx >= remoteProxies.size) {
                                    activeProxyIdx = 0
                                }
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

            Spacer(modifier = Modifier.height(14.dp))

            // Payload Editor Card
            WardenCard {
                Text("SSH payload editor", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                Spacer(modifier = Modifier.height(6.dp))

                WardenSelectRow(
                    options = listOf("HTTP", "HTTPS"),
                    selected = payloadScheme,
                    onSelect = { payloadScheme = it },
                    color = WardenMint
                )

                Spacer(modifier = Modifier.height(6.dp))
                WardenField(label = "SNI / front domain", value = frontSni, onValueChange = { frontSni = it })

                Text("Payload — editable", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(bottom = 3.dp))
                val currentPayload = if (payloadScheme == "HTTP") payloadHttp else payloadHttps
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

                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                Spacer(modifier = Modifier.height(12.dp))

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

            // Port Forwarding Card
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

    if (showSshEditor) {
        WardenSSHEditor(
            onClose = { showSshEditor = false },
            onSave = { saved ->
                ProfileStore.saveProfile(saved)
                ProfileStore.setActiveProfileId(saved.id)
                storedProfiles = ProfileStore.getAllProfiles()
                onSelectProfile(saved)
                showSshEditor = false
                Toast.makeText(context, "Saved SSH: ${saved.name}", Toast.LENGTH_SHORT).show()
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

    // Add Remote Proxy Dialog
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
                    WardenField(label = "Label", value = newProxyLabel, onValueChange = { newProxyLabel = it })
                    WardenField(label = "Host : Port", value = newProxyValue, onValueChange = { newProxyValue = it })
                    Spacer(modifier = Modifier.height(14.dp))
                    WardenSaveButton(
                        label = "Save proxy",
                        onClick = {
                            if (newProxyValue.isNotBlank()) {
                                remoteProxies = remoteProxies + RemoteProxyItem(newProxyLabel.ifBlank { "Proxy" }, newProxyValue.trim())
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
