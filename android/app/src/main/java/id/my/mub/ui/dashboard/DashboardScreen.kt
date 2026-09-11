package id.my.mub.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
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
import id.my.mub.data.LogRepository
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.ui.theme.*

@Composable
fun DashboardScreen(
    vpnState: VpnState,
    profile: VpnProfile,
    onToggleConnect: () -> Unit,
    onNavigateToConfig: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting
    val isError = vpnState is VpnState.Error
    val logs by LogRepository.logs.collectAsState()

    val hasConfig = profile.serverHost.isNotBlank() || profile.serverIp.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Secure Relay",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = "Custom Proxy Engine",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }

            // Connection Status Pill
            Surface(
                color = when {
                    isConnected -> AccentSuccess.copy(alpha = 0.15f)
                    isConnecting -> AccentWarning.copy(alpha = 0.15f)
                    isError -> AccentError.copy(alpha = 0.15f)
                    else -> SurfaceCardElevated
                },
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when {
                        isConnected -> AccentSuccess.copy(alpha = 0.4f)
                        isConnecting -> AccentWarning.copy(alpha = 0.4f)
                        isError -> AccentError.copy(alpha = 0.4f)
                        else -> SurfaceCardBorder
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnected -> AccentSuccess
                                    isConnecting -> AccentWarning
                                    isError -> AccentError
                                    else -> TextMuted
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (vpnState) {
                            is VpnState.Connected -> "Active"
                            is VpnState.Connecting -> "Connecting"
                            is VpnState.Error -> "Failed"
                            is VpnState.Disconnecting -> "Stopping"
                            else -> "Idle"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            isConnected -> AccentSuccess
                            isConnecting -> AccentWarning
                            isError -> AccentError
                            else -> TextSecondary
                        }
                    )
                }
            }
        }

        // Error message card if present
        if (isError) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, AccentError.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                colors = CardDefaults.cardColors(containerColor = AccentError.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = AccentError,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = (vpnState as VpnState.Error).message,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        // Active Configuration Card
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
                        text = "Active Node",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )

                    Text(
                        text = "Edit",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AccentPrimary,
                        modifier = Modifier.clickable { onNavigateToConfig() }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (hasConfig) {
                    Text(
                        text = profile.name.ifBlank { "Custom Server" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = AccentPrimary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = profile.protocol.displayName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = AccentPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        val displayHost = profile.serverHost.ifBlank { profile.serverIp }
                        Text(
                            text = "$displayHost:${profile.serverPort}",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "No server configured",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Text(
                                text = "Add manual configuration to connect",
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }

                        Button(
                            onClick = { onNavigateToConfig() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                        ) {
                            Text("Configure", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Clean Main Action Card
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isConnected) "Relay Connection Active" else "Relay Connection Ready",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isConnected) "Traffic encrypted and routed" else "Tap below to initialize route",
                    fontSize = 12.sp,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = { onToggleConnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            isConnected -> AccentError
                            isConnecting -> AccentWarning
                            else -> AccentPrimary
                        }
                    )
                ) {
                    Text(
                        text = when {
                            isConnected -> "Stop Relay"
                            isConnecting -> "Connecting..."
                            else -> "Start Relay"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PureWhite
                    )
                }
            }
        }

        // Minimalist Real Data Transfer Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Transfer Statistics",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )

                Spacer(modifier = Modifier.height(12.dp))

                val connectedState = vpnState as? VpnState.Connected
                val rxSpeed = connectedState?.let { formatSpeed(it.rxSpeedMbps) } ?: "0.0 KB/s"
                val txSpeed = connectedState?.let { formatSpeed(it.txSpeedMbps) } ?: "0.0 KB/s"
                val totalRx = connectedState?.let { formatBytes(it.totalRxBytes) } ?: "0 B"
                val totalTx = connectedState?.let { formatBytes(it.totalTxBytes) } ?: "0 B"

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Download", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = rxSpeed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Total: $totalRx",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text("Upload", fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = txSpeed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Total: $totalTx",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }
        }

        // Mini Log Stream Preview
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "System Log",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )

                    Text(
                        text = "View All",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AccentPrimary,
                        modifier = Modifier.clickable { onNavigateToLogs() }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = BgMain,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        val recentLogs = logs.takeLast(3)
                        if (recentLogs.isEmpty()) {
                            Text("No recent log events", fontSize = 11.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        } else {
                            recentLogs.forEach { entry ->
                                Text(
                                    text = "[${entry.tag}] ${entry.message}",
                                    fontSize = 11.sp,
                                    color = when (entry.level) {
                                        id.my.mub.data.LogLevel.ERROR -> AccentError
                                        id.my.mub.data.LogLevel.WARN -> AccentWarning
                                        id.my.mub.data.LogLevel.SUCCESS -> AccentSuccess
                                        else -> TextSecondary
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun formatSpeed(mbps: Double): String {
    val kbps = mbps * 125.0 // Convert Mbps to KB/s
    return if (kbps >= 1024) {
        String.format("%.1f MB/s", kbps / 1024.0)
    } else {
        String.format("%.1f KB/s", kbps)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
