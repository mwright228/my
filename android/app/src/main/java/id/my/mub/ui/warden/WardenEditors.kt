package id.my.mub.ui.warden

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
fun WardenXrayEditor(
    initialProfile: VpnProfile? = null,
    onClose: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    var protocol by remember { mutableStateOf(if (initialProfile?.protocol == ProtocolType.SHADOWSOCKS_2022) "Shadowsocks" else "VLESS") }
    var transport by remember { mutableStateOf(if (initialProfile?.protocol == ProtocolType.VLESS_TCP) "TCP" else "WS") }
    var security by remember { mutableStateOf(if (initialProfile?.serverPort == 443 || initialProfile?.serverPort == 8443) "TLS" else "None") }

    var remarks by remember { mutableStateOf(initialProfile?.name ?: "warden-new-profile") }
    var address by remember { mutableStateOf(initialProfile?.serverHost ?: "104.21.44.12") }
    var port by remember { mutableStateOf(initialProfile?.serverPort?.toString() ?: "443") }
    var uuid by remember { mutableStateOf(initialProfile?.userUUID ?: UUID.randomUUID().toString()) }
    var flow by remember { mutableStateOf(initialProfile?.vlessFlow ?: "none") }
    var password by remember { mutableStateOf(initialProfile?.userUUID ?: "warden-pass-21") }
    var method by remember { mutableStateOf(initialProfile?.ssCipher ?: "2022-blake3-aes-128-gcm") }

    var path by remember { mutableStateOf(initialProfile?.wsPath ?: "/vless-ws") }
    var hostHeader by remember { mutableStateOf(initialProfile?.wsHost ?: "") }
    var sni by remember { mutableStateOf(initialProfile?.bugHostSNI ?: "") }
    var allowInsecureTLS by remember {
        mutableStateOf(
            initialProfile?.allowInsecureTLS ?: true
        )
    }

    val protocols = listOf("VLESS", "VMess", "Trojan", "Shadowsocks")
    val transports = listOf("TCP", "WS", "gRPC", "HTTP/2", "XHTTP", "mKCP")
    val securities = listOf("Reality", "TLS", "AnyTLS", "None")

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = WardenScreen),
            border = androidx.compose.foundation.BorderStroke(1.dp, WardenBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (initialProfile != null) "Edit Xray profile" else "New Xray profile",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = WardenText
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = WardenMutedDim, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable Form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Protocol", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(bottom = 3.dp))
                    WardenSelectRow(options = protocols, selected = protocol, onSelect = { protocol = it }, color = WardenIndigo)

                    Text("Transport", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(vertical = 3.dp))
                    WardenSelectRow(options = transports, selected = transport, onSelect = { transport = it }, color = WardenMint)

                    Text("Security", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(vertical = 3.dp))
                    WardenSelectRow(options = securities, selected = security, onSelect = { security = it }, color = WardenViolet)

                    Spacer(modifier = Modifier.height(8.dp))

                    // Dynamic Fields based on user specification
                    WardenField(label = "Remarks", value = remarks, onValueChange = { remarks = it })
                    WardenField(label = "Address", value = address, onValueChange = { address = it })
                    WardenField(label = "Port", value = port, onValueChange = { port = it })

                    when (protocol) {
                        "VLESS" -> {
                            WardenField(label = "UUID", value = uuid, onValueChange = { uuid = it })
                            WardenField(label = "Flow", value = flow, onValueChange = { flow = it })
                        }
                        "VMess" -> {
                            WardenField(label = "UUID", value = uuid, onValueChange = { uuid = it })
                            WardenField(label = "Alter ID", value = "0")
                        }
                        "Trojan" -> {
                            WardenField(label = "Password", value = password, onValueChange = { password = it })
                        }
                        "Shadowsocks" -> {
                            WardenField(label = "Password", value = password, onValueChange = { password = it })
                            WardenField(label = "Method", value = method, onValueChange = { method = it })
                        }
                    }

                    if (transport in listOf("WS", "HTTP/2", "XHTTP")) {
                        WardenField(label = "Path", value = path, onValueChange = { path = it })
                        WardenField(label = "Host header", value = hostHeader, onValueChange = { hostHeader = it })
                    } else if (transport == "gRPC") {
                        WardenField(label = "Service name", value = "warden-grpc")
                    } else if (transport == "mKCP") {
                        WardenField(label = "Seed", value = "warden-seed-01")
                    }

                    if (security == "Reality") {
                        WardenField(label = "SNI", value = sni.ifBlank { "www.microsoft.com" }, onValueChange = { sni = it })
                        WardenField(label = "Public key", value = "8sV9mQ1x...kZ2p")
                        WardenField(label = "Short ID", value = "4a2e")
                        WardenField(label = "Fingerprint", value = "chrome")
                    } else if (security == "TLS") {
                        WardenField(label = "SNI", value = sni.ifBlank { address }, onValueChange = { sni = it })
                        WardenField(label = "ALPN", value = "h2, http/1.1")
                        WardenField(label = "Fingerprint", value = "chrome")
                    } else if (security == "AnyTLS") {
                        WardenField(label = "SNI", value = sni.ifBlank { "cdn.warden.app" }, onValueChange = { sni = it })
                        WardenField(label = "Fingerprint", value = "chrome")
                        WardenField(label = "Padding scheme", value = "default")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Allow Insecure TLS toggle (Critical for BugHost / Carrier SNI spoofing)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(WardenSurface2)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Allow Insecure TLS", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = WardenText)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Skip certificate verification. Required when BugHost/SNI does not match the server's certificate.",
                                fontSize = 10.sp,
                                color = WardenMutedDim
                            )
                        }
                        WardenToggle(on = allowInsecureTLS, onToggle = { allowInsecureTLS = !allowInsecureTLS })
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                WardenSaveButton(
                    label = if (initialProfile != null) "Update profile" else "Save profile",
                    onClick = {
                        val portInt = port.toIntOrNull() ?: 443
                        val pType = when (protocol) {
                            "Shadowsocks" -> ProtocolType.SHADOWSOCKS_2022
                            else -> if (transport == "WS") ProtocolType.VLESS_WS else ProtocolType.VLESS_TCP
                        }
                        val prof = (initialProfile ?: VpnProfile()).copy(
                            name = remarks.ifBlank { "warden-profile" },
                            serverHost = address.trim(),
                            serverIp = address.trim(),
                            serverPort = portInt,
                            protocol = pType,
                            userUUID = if (protocol == "Shadowsocks") password.trim() else uuid.trim(),
                            bugHostSNI = sni.trim(),
                            wsPath = path.trim(),
                            wsHost = hostHeader.trim(),
                            vlessFlow = flow.trim(),
                            ssCipher = method.trim(),
                            allowInsecureTLS = allowInsecureTLS
                        )
                        onSave(prof)
                    },
                    color = WardenMint
                )
            }
        }
    }
}

@Composable
fun WardenSSHEditor(
    initialProfile: VpnProfile? = null,
    onClose: () -> Unit,
    onSave: (VpnProfile) -> Unit
) {
    var modeSel by remember { mutableStateOf("SSH · TLS") }
    var auth by remember { mutableStateOf("Password") }

    var label by remember { mutableStateOf(initialProfile?.name ?: "my-ssh-server") }
    var host by remember { mutableStateOf(initialProfile?.serverHost ?: "ssh.provider.net") }
    var port by remember { mutableStateOf(initialProfile?.serverPort?.toString() ?: "443") }
    var username by remember { mutableStateOf(initialProfile?.sshUser ?: "myuser21") }
    var password by remember { mutableStateOf(initialProfile?.sshPassword ?: "") }

    val modes = listOf("SSH · Direct", "SSH · TLS", "SSH · WS", "Dropbear")
    val authTypes = listOf("Password", "Private key")

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = WardenScreen),
            border = androidx.compose.foundation.BorderStroke(1.dp, WardenBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add SSH account",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = WardenText
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = WardenMutedDim, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("Mode", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(bottom = 3.dp))
                    WardenSelectRow(options = modes, selected = modeSel, onSelect = { modeSel = it }, color = WardenViolet)

                    Text("Authentication", fontSize = 10.sp, color = WardenMutedDim, modifier = Modifier.padding(vertical = 3.dp))
                    WardenSelectRow(options = authTypes, selected = auth, onSelect = { auth = it }, color = WardenIndigo)

                    Spacer(modifier = Modifier.height(8.dp))

                    WardenField(label = "Label", value = label, onValueChange = { label = it })
                    WardenField(label = "Host", value = host, onValueChange = { host = it })
                    WardenField(label = "Port", value = port, onValueChange = { port = it })
                    WardenField(label = "Username", value = username, onValueChange = { username = it })
                    WardenField(
                        label = if (auth == "Password") "Password" else "Private key",
                        value = password,
                        onValueChange = { password = it }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                WardenSaveButton(
                    label = "Save account",
                    onClick = {
                        val portInt = port.toIntOrNull() ?: 443
                        val prof = (initialProfile ?: VpnProfile()).copy(
                            name = label.ifBlank { "my-ssh-server" },
                            serverHost = host.trim(),
                            serverIp = host.trim(),
                            serverPort = portInt,
                            protocol = ProtocolType.SSH_PAYLOAD,
                            sshUser = username.trim(),
                            sshPassword = password.trim()
                        )
                        onSave(prof)
                    },
                    color = WardenViolet
                )
            }
        }
    }
}
