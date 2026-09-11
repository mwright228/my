package id.my.mub.ui.warden

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.ProtocolType
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.ui.theme.*
import java.util.Locale

@Composable
fun WardenHomeScreen(
    vpnState: VpnState,
    activeProfile: VpnProfile,
    onToggleConnection: () -> Unit,
    onBackToNotes: () -> Unit
) {
    val isConnected = vpnState is VpnState.Connected
    val isConnecting = vpnState is VpnState.Connecting
    val color = if (isConnected) WardenMint else if (isConnecting) WardenAmber else WardenMutedDim

    val infiniteTransition = rememberInfiniteTransition(label = "spinRing")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Header: ShieldCheck + Warden title + Back to Notes stealth icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = WardenMint,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Warden",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = WardenText,
                    letterSpacing = (-0.2).sp
                )
            }

            // Discreet Back to Notes camouflage button
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onBackToNotes() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, tint = WardenMutedDim, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Notes", fontSize = 12.sp, color = WardenMutedDim)
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Center Big Power Button (186dp diameter)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Spinning gradient ring when connected
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .then(
                        if (isConnected) {
                            Modifier
                                .rotate(rotationAngle)
                                .background(
                                    Brush.sweepGradient(
                                        listOf(WardenMint, WardenIndigo, WardenMint)
                                    )
                                )
                        } else {
                            Modifier
                                .background(Color.Transparent)
                                .border(1.dp, WardenBorder, CircleShape)
                        }
                    )
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                // Inner Circular Button
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    if (isConnected) Color(0xFF122019) else WardenSurface,
                                    WardenScreen
                                )
                            )
                        )
                        .clickable { onToggleConnection() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = color,
                            modifier = Modifier.size(34.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = when {
                                isConnected -> "CONNECTED"
                                isConnecting -> "CONNECTING..."
                                else -> "TAP TO CONNECT"
                            },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = color,
                            letterSpacing = 0.3.sp
                        )

                        if (isConnected) {
                            val conn = vpnState as? VpnState.Connected
                            val secs = conn?.connectedDurationSecs ?: 0L
                            val h = secs / 3600
                            val m = (secs % 3600) / 60
                            val s = secs % 60
                            val timeStr = String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                            Text(
                                text = timeStr,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WardenMutedDim,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Live Throughput Meters
        val connState = vpnState as? VpnState.Connected
        val rx = connState?.rxSpeedMbps ?: 0.0
        val tx = connState?.txSpeedMbps ?: 0.0
        val rxStr = if (rx >= 1.0) String.format(Locale.US, "%.1f MB/s", rx) else String.format(Locale.US, "%.0f KB/s", rx * 1024)
        val txStr = if (tx >= 1.0) String.format(Locale.US, "%.1f MB/s", tx) else String.format(Locale.US, "%.0f KB/s", tx * 1024)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isConnected) rxStr else "0.0 MB/s",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = WardenText,
                    fontWeight = FontWeight.SemiBold
                )
                Text("↓ download", fontSize = 10.5.sp, color = WardenMutedDim)
            }

            Spacer(modifier = Modifier.width(30.dp))
            Box(modifier = Modifier.width(1.dp).height(24.dp).background(WardenBorder))
            Spacer(modifier = Modifier.width(30.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isConnected) txStr else "0.0 KB/s",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    color = WardenText,
                    fontWeight = FontWeight.SemiBold
                )
                Text("↑ upload", fontSize = 10.5.sp, color = WardenMutedDim)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Active Server Card
        WardenSectionLabel(title = "Active server")
        WardenCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🇩🇪", fontSize = 20.sp)
                    Column {
                        Text(
                            text = activeProfile.name.ifBlank { "Frankfurt #3" },
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WardenText
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        val protoColor = when (activeProfile.protocol) {
                            ProtocolType.VLESS_WS -> WardenMint
                            ProtocolType.VLESS_TCP -> WardenIndigo
                            ProtocolType.SSH_PAYLOAD -> WardenAmber
                            ProtocolType.SHADOWSOCKS_2022 -> WardenMint
                            else -> WardenViolet
                        }
                        WardenChip(text = activeProfile.protocol.displayName, color = protoColor)
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    val ping = connState?.pingMs ?: 42L
                    Text(
                        text = "${ping}ms",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        color = WardenText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    WardenLoadBars(load = 4, color = WardenMint)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Kill Switch & DNS Card
        WardenSectionLabel(title = "Kill switch & DNS")
        WardenCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = WardenMint, modifier = Modifier.size(13.dp))
                    Text("Kill switch armed", fontSize = 11.5.sp, color = WardenMuted)
                }

                Box(modifier = Modifier.width(1.dp).height(16.dp).background(WardenBorder))

                Row(
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = WardenIndigo, modifier = Modifier.size(13.dp))
                    Text("DoH · 1.1.1.1", fontSize = 11.5.sp, color = WardenMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
