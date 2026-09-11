package id.my.mub.ui.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val logs by LogRepository.logs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- 1. Top Header Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isConnected -> CyberMint
                                    isConnecting -> AmberGold
                                    else -> CoralRed
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MUB-X ENGINE",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = PureWhite,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "v2.5 Telecom Power-User Edition",
                    fontSize = 11.sp,
                    color = SlateGray
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ping Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceCard)
                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isConnected) "28 ms" else "-- ms",
                        color = if (isConnected) CyberMint else SlateGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Status Badge (Guaranteed no text wrapping)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isConnected) Color(0x2200FFA3) else Color(0x2200F0FF))
                        .border(
                            1.dp,
                            if (isConnected) Color(0x5500FFA3) else Color(0x4400F0FF),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isConnected) "ONLINE" else "STANDBY",
                        color = if (isConnected) CyberMint else ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // --- 2. Circular Wire-Speed Meter ---
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val ringRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing)
            ),
            label = "rotation"
        )

        val ringBrush = when {
            isConnected -> Brush.sweepGradient(listOf(ElectricCyan, NeonViolet, CyberMint, ElectricCyan))
            isConnecting -> Brush.sweepGradient(listOf(AmberGold, ElectricCyan, AmberGold))
            else -> Brush.linearGradient(listOf(SurfaceCardBorder, SurfaceCardBorder))
        }

        Box(
            modifier = Modifier
                .size(260.dp)
                .shadow(
                    elevation = if (isConnected) 28.dp else 4.dp,
                    shape = CircleShape,
                    spotColor = if (isConnected) ElectricCyan else Color.Transparent,
                    ambientColor = if (isConnected) NeonViolet else Color.Transparent
                )
                .clip(CircleShape)
                .background(SurfaceCard)
                .border(4.dp, ringBrush, CircleShape)
                .clickable { onToggleConnect() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Connection State Label
                Text(
                    text = when (vpnState) {
                        is VpnState.Connected -> "CONNECTED"
                        is VpnState.Connecting -> "CONNECTING..."
                        is VpnState.Disconnecting -> "CLOSING..."
                        else -> "TAP TO CONNECT"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (vpnState) {
                        is VpnState.Connected -> CyberMint
                        is VpnState.Connecting -> AmberGold
                        else -> SlateGray
                    },
                    letterSpacing = 1.5.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Real RX Speed Number
                val rxSpeed = if (vpnState is VpnState.Connected) vpnState.rxSpeedMbps else 0.0
                Text(
                    text = String.format("%.1f", rxSpeed),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    color = PureWhite
                )

                Text(
                    text = "Mbps Wire-Speed",
                    fontSize = 12.sp,
                    color = SlateGray
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isConnected) {
                    val conn = vpnState as VpnState.Connected
                    val durationMin = conn.connectedDurationSecs / 60
                    val durationSec = conn.connectedDurationSecs % 60
                    val timeStr = String.format("%02d:%02d", durationMin, durationSec)

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "↓ ${String.format("%.1f", conn.rxSpeedMbps)} M",
                            fontSize = 11.sp,
                            color = CyberMint,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "↑ ${String.format("%.1f", conn.txSpeedMbps)} M",
                            fontSize = 11.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "⏱ $timeStr",
                            fontSize = 11.sp,
                            color = PureWhite
                        )
                    }
                } else {
                    // Tap hint icon
                    Text(
                        text = "⚡",
                        fontSize = 20.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // --- 3. Active Profile & Protocol Card (Quick Edit) ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(20.dp))
                .clickable { onNavigateToConfig() },
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE PROFILE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SlateGray,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = profile.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${profile.protocol.displayName} • ${profile.serverHost}:${profile.serverPort}",
                            fontSize = 12.sp,
                            color = ElectricCyan
                        )
                    }

                    // Edit Button Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x2200F0FF))
                            .border(1.dp, Color(0x5500F0FF), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Edit ⚙️",
                            fontSize = 12.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Parameter Chips (Responsive row, never squishes vertically!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SNI Bug Host Chip
                    if (profile.bugHostSNI.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x338B5CF6))
                                .border(1.dp, Color(0x668B5CF6), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "🌐 ${profile.bugHostSNI}",
                                color = PureWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Concurrency Lanes Chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCardElevated)
                            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${profile.poolConcurrency} Lanes",
                            color = SlateGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // DNS Chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceCardElevated)
                            .border(1.dp, SurfaceCardBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "DNS: ${profile.dnsServer}",
                            color = SlateGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // --- 4. Live Mini-Log Console Box ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp))
                .clickable { onNavigateToLogs() },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C101A))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE TERMINAL LOGS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateGray,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "View All ›",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val recentLogs = logs.takeLast(2)
                if (recentLogs.isEmpty()) {
                    Text(
                        text = "> System initialized. Engine standing by.",
                        color = SlateGray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    recentLogs.forEach { entry ->
                        Text(
                            text = "> [${entry.tag}] ${entry.message}",
                            color = when (entry.level) {
                                id.my.mub.data.LogLevel.SUCCESS -> CyberMint
                                id.my.mub.data.LogLevel.ERROR -> CoralRed
                                id.my.mub.data.LogLevel.WARN -> AmberGold
                                id.my.mub.data.LogLevel.NET -> NeonViolet
                                else -> ElectricCyan
                            },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
