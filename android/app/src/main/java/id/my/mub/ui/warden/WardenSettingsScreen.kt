package id.my.mub.ui.warden

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.ui.theme.*

data class SettingRowItem(
    val icon: ImageVector,
    val label: String,
    val state: Boolean,
    val onToggle: () -> Unit
)

@Composable
fun WardenSettingsScreen() {
    var tunMode by remember { mutableStateOf(true) }
    var killSwitch by remember { mutableStateOf(true) }
    var autoConnect by remember { mutableStateOf(false) }
    var ipv6 by remember { mutableStateOf(false) }
    var mtu by remember { mutableStateOf(1420) }

    val networkRows = listOf(
        SettingRowItem(Icons.Default.Speed, "TUN mode (gVisor)", tunMode) { tunMode = !tunMode },
        SettingRowItem(Icons.Default.Block, "Kill switch", killSwitch) { killSwitch = !killSwitch },
        SettingRowItem(Icons.Default.PowerSettingsNew, "Auto-connect on boot", autoConnect) { autoConnect = !autoConnect },
        SettingRowItem(Icons.Default.Radio, "IPv6", ipv6) { ipv6 = !ipv6 }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Settings",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = WardenText
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Network Section
        WardenSectionLabel(title = "Network")
        WardenCard(padding = PaddingValues(0.dp)) {
            networkRows.forEachIndexed { i, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(
                            imageVector = row.icon,
                            contentDescription = null,
                            tint = WardenMutedDim,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = row.label,
                            fontSize = 12.5.sp,
                            color = WardenText
                        )
                    }

                    WardenToggle(
                        on = row.state,
                        onToggle = row.onToggle,
                        color = WardenMint
                    )
                }

                if (i < networkRows.size - 1) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WardenBorder))
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // MTU Section
        WardenSectionLabel(title = "MTU")
        WardenCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Packet size",
                    fontSize = 12.5.sp,
                    color = WardenText
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WardenSurface2)
                            .clickable { if (mtu > 1200) mtu -= 10 }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("−", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = WardenMutedDim)
                    }

                    Text(
                        text = mtu.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = WardenText
                    )

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(WardenSurface2)
                            .clickable { if (mtu < 1500) mtu += 10 }
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = WardenMutedDim)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // About Section
        WardenSectionLabel(title = "About")
        WardenCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = WardenMutedDim,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Warden v2.4 · configs stay on-device",
                        fontSize = 12.5.sp,
                        color = WardenText
                    )
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = WardenMutedDim,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}
