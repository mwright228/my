package id.my.mub.ui.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
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
import id.my.mub.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class PingTarget(val label: String, val host: String, var latencyMs: Long? = null, var status: String = "Ready")

@Composable
fun NetworkDiagnosticsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var networkType by remember { mutableStateOf("Detecting...") }
    var localIp by remember { mutableStateOf("...") }
    var gatewayIp by remember { mutableStateOf("...") }
    var dnsServer by remember { mutableStateOf("...") }

    var pingTargets by remember {
        mutableStateOf(
            listOf(
                PingTarget("Local Gateway", "192.168.1.1"),
                PingTarget("Cloudflare DNS", "1.1.1.1"),
                PingTarget("Google DNS", "8.8.8.8"),
                PingTarget("Quad9 Secure", "9.9.9.9")
            )
        )
    }

    var isPinging by remember { mutableStateOf(false) }
    var lookupDomain by remember { mutableStateOf("cloudflare.com") }
    var lookupResult by remember { mutableStateOf<String?>(null) }
    var isLookingUp by remember { mutableStateOf(false) }

    fun refreshNetworkInfo() {
        scope.launch(Dispatchers.IO) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(activeNetwork)

                val type = when {
                    caps == null -> "Offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (802.11)"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (LTE/5G)"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> "Connected"
                }

                // Detect local IPv4 address
                var ip = "Unavailable"
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (nif in interfaces) {
                    val addrs = nif.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            ip = addr.hostAddress ?: ip
                            break
                        }
                    }
                    if (ip != "Unavailable") break
                }

                val linkProperties = cm.getLinkProperties(activeNetwork)
                val dns = linkProperties?.dnsServers?.firstOrNull()?.hostAddress ?: "1.1.1.1"
                val gateway = linkProperties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "192.168.1.1"

                withContext(Dispatchers.Main) {
                    networkType = type
                    localIp = ip
                    gatewayIp = gateway
                    dnsServer = dns
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    networkType = "Connected"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshNetworkInfo()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 20.dp)
    ) {
        // App Stealth Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NetPulse",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Network Diagnostics & Performance",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = { refreshNetworkInfo() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceCard)
                        .border(1.dp, SurfaceCardBorder, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Live Network Summary Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connection Status",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (networkType != "Offline") AccentSuccess else AccentError)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = networkType,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = SurfaceCardBorder)

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local IPv4", fontSize = 12.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(localIp, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, fontFamily = FontFamily.Monospace)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Gateway", fontSize = 12.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(gatewayIp, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, fontFamily = FontFamily.Monospace)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DNS Resolver", fontSize = 12.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(dnsServer, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, fontFamily = FontFamily.Monospace)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("MTU Size", fontSize = 12.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("1500 bytes", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Latency & Gateway Benchmarking Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Latency & Route Quality",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )

                        Button(
                            onClick = {
                                if (isPinging) return@Button
                                isPinging = true
                                scope.launch(Dispatchers.IO) {
                                    val updated = pingTargets.map { target ->
                                        try {
                                            val start = System.currentTimeMillis()
                                            val reachable = InetAddress.getByName(target.host).isReachable(1000)
                                            val elapsed = System.currentTimeMillis() - start
                                            if (reachable || elapsed > 0) {
                                                target.copy(latencyMs = elapsed.coerceAtLeast(1L), status = "Online")
                                            } else {
                                                target.copy(latencyMs = null, status = "Timeout")
                                            }
                                        } catch (e: Exception) {
                                            target.copy(latencyMs = null, status = "Unreachable")
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        pingTargets = updated
                                        isPinging = false
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) {
                            Text(if (isPinging) "Testing..." else "Run Ping", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    pingTargets.forEach { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(target.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text(target.host, fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                            }

                            if (target.latencyMs != null) {
                                Surface(
                                    color = if (target.latencyMs!! < 60) AccentSuccess.copy(alpha = 0.15f) else AccentWarning.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "${target.latencyMs} ms",
                                        color = if (target.latencyMs!! < 60) AccentSuccess else AccentWarning,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            } else {
                                Text(target.status, fontSize = 12.sp, color = TextMuted)
                            }
                        }
                        if (target != pingTargets.last()) {
                            Divider(color = SurfaceCardBorder.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }

        // DNS Lookup Utility Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "DNS Query Tool",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = lookupDomain,
                            onValueChange = { lookupDomain = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("domain.com", color = TextMuted, fontSize = 13.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = SurfaceCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (lookupDomain.isBlank()) return@Button
                                isLookingUp = true
                                scope.launch(Dispatchers.IO) {
                                    val res = try {
                                        val addrs = InetAddress.getAllByName(lookupDomain.trim())
                                        addrs.joinToString(", ") { it.hostAddress ?: "" }
                                    } catch (e: Exception) {
                                        "Resolution failed: ${e.message}"
                                    }
                                    withContext(Dispatchers.Main) {
                                        lookupResult = res
                                        isLookingUp = false
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardElevated)
                        ) {
                            Text(if (isLookingUp) "..." else "Resolve", fontSize = 13.sp, color = TextPrimary)
                        }
                    }

                    if (lookupResult != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = BgMain,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = lookupResult!!,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
