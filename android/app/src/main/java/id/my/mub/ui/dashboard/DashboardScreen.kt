package id.my.mub.ui.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.ui.theme.*

@Composable
fun DashboardScreen(
    vpnState: VpnState,
    profile: VpnProfile,
    onToggleConnect: () -> Unit,
    onNavigateToLab: () -> Unit
) {
    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgObsidian)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Header Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MUB-X Engine",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
                Text(
                    text = "v2.5 Telecom Edition",
                    fontSize = 12.sp,
                    color = SlateGray
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Ping pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceCard)
                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isConnected) "24ms" else "-- ms",
                        color = if (isConnected) CyberMint else SlateGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Battery Saver Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceCard)
                        .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Realtime I/O",
                        color = ElectricCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // --- Large Glowing Circular Connect Ring ---
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val ringRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing)
            ),
            label = "rotation"
        )

        val ringBrush = if (isConnected) {
            Brush.sweepGradient(listOf(ElectricCyan, NeonViolet, CyberMint, ElectricCyan))
        } else if (isConnecting) {
            Brush.sweepGradient(listOf(NeonViolet, ElectricCyan, NeonViolet))
        } else {
            Brush.linearGradient(listOf(SurfaceCardBorder, SurfaceCardBorder))
        }

        Box(
            modifier = Modifier
                .size(260.dp)
                .shadow(
                    elevation = if (isConnected) 32.dp else 0.dp,
                    shape = CircleShape,
                    spotColor = ElectricCyan,
                    ambientColor = NeonViolet
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
                Text(
                    text = when (vpnState) {
                        is VpnState.Connected -> "CONNECTED"
                        is VpnState.Connecting -> "CONNECTING"
                        is VpnState.Disconnecting -> "CLOSING"
                        else -> "DISCONNECTED"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (vpnState) {
                        is VpnState.Connected -> CyberMint
                        is VpnState.Connecting -> ElectricCyan
                        else -> SlateGray
                    },
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                val speedText = if (vpnState is VpnState.Connected) {
                    String.format("%.1f", vpnState.rxSpeedMbps)
                } else "0.0"

                Text(
                    text = speedText,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PureWhite
                )

                Text(
                    text = "Mbps Wire-Speed",
                    fontSize = 13.sp,
                    color = SlateGray
                )

                if (isConnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "↑ 22.3 Mb/s  ↓ ${(vpnState as VpnState.Connected).rxSpeedMbps.toInt()} Mb/s",
                        fontSize = 11.sp,
                        color = ElectricCyan
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // --- Active Protocol & Bug-Host Card ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(20.dp))
                .clickable { onNavigateToLab() },
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Active Protocol:",
                            fontSize = 12.sp,
                            color = SlateGray
                        )
                        Text(
                            text = profile.protocol.displayName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                        Text(
                            text = "(${profile.protocol.badge})",
                            fontSize = 13.sp,
                            color = ElectricCyan
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0x2200F0FF)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "›", fontSize = 20.sp, color = ElectricCyan, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SNI Bug-Host Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x339D4EDD))
                            .border(1.dp, Color(0x669D4EDD), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "🌐 SNI: ${profile.bugHostSNI}",
                            color = PureWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Pool Lanes Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x22FFFFFF))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "${profile.poolConcurrency} Lanes",
                            color = SlateGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
