package id.my.mub.ui.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.my.mub.data.ProtocolType
import id.my.mub.ui.theme.*

data class ProtocolItem(
    val type: ProtocolType,
    val title: String,
    val badge: String,
    val description: String,
    val icon: ImageVector,
    val accentColor: Color
)

@Composable
fun ProtocolSelectionDialog(
    onDismiss: () -> Unit,
    onProtocolSelected: (ProtocolType) -> Unit
) {
    val protocolList = listOf(
        ProtocolItem(
            type = ProtocolType.VLESS_WS,
            title = "VLESS (WebSocket)",
            badge = "CDN / Cloudflare",
            description = "WebSocket reverse proxy with SNI spoofing and CDN edge routing",
            icon = Icons.Default.Cloud,
            accentColor = CyberMint
        ),
        ProtocolItem(
            type = ProtocolType.VLESS_TCP,
            title = "VLESS (Direct TCP)",
            badge = "Direct TLS",
            description = "Direct TLS stream with minimal overhead and Vision flow control",
            icon = Icons.Default.FlashOn,
            accentColor = AccentPrimary
        ),
        ProtocolItem(
            type = ProtocolType.SSH_PAYLOAD,
            title = "SSH & HTTP Injector",
            badge = "Payload Injection",
            description = "Custom carrier HTTP payload, proxy bug host, and SSH tunnel",
            icon = Icons.Default.Build,
            accentColor = Color(0xFFF59E0B) // Amber
        ),
        ProtocolItem(
            type = ProtocolType.SHADOWSOCKS_2022,
            title = "Shadowsocks (2022)",
            badge = "AEAD Cipher",
            description = "Fast, lightweight encrypted proxy with pre-shared key auth",
            icon = Icons.Default.Lock,
            accentColor = Color(0xFF8B5CF6) // Purple
        ),
        ProtocolItem(
            type = ProtocolType.ZIVPN_UDP,
            title = "ZiVPN (UDP Custom)",
            badge = "UDP Obfuscation",
            description = "Salamander XOR obfuscation with dynamic port-hopping bypass",
            icon = Icons.Default.Refresh,
            accentColor = Color(0xFF06B6D4) // Cyan
        ),
        ProtocolItem(
            type = ProtocolType.T_BRUTAL,
            title = "T-Brutal Multi-Path",
            badge = "TCP Pacing",
            description = "High-speed multi-lane parallel TCP with aggressive congestion pacing",
            icon = Icons.Default.Share,
            accentColor = Color(0xFFEC4899) // Pink
        )
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Select Protocol",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Choose a tunnel protocol to configure",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable List of Protocol Cards
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (item in protocolList) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(12.dp))
                                .clickable { onProtocolSelected(item.type) },
                            colors = CardDefaults.cardColors(containerColor = BgMain)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Protocol Icon with tinted circular background
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(item.accentColor.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        tint = item.accentColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                // Titles & Details
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(item.accentColor.copy(alpha = 0.12f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = item.badge,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = item.accentColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Text(
                                        text = item.description,
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        lineHeight = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Select",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Cancel Button
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceCardBorder)
                ) {
                    Text("Cancel", fontSize = 13.sp)
                }
            }
        }
    }
}
