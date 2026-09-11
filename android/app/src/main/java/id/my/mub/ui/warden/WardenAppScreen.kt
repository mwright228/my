package id.my.mub.ui.warden

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.ui.theme.*

enum class WardenTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.PowerSettingsNew),
    SERVERS("Servers", Icons.Default.Dns),
    TUNNEL("Tunnel", Icons.Default.AltRoute),
    LOGS("Logs", Icons.Default.Terminal),
    SETTINGS("Settings", Icons.Default.Settings)
}

@Composable
fun WardenAppScreen(
    vpnState: VpnState,
    activeProfile: VpnProfile,
    onToggleVpn: () -> Unit,
    onBackToNotes: () -> Unit,
    onUpdateProfile: (VpnProfile) -> Unit
) {
    var currentTab by remember { mutableStateOf(WardenTab.HOME) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        containerColor = WardenScreen,
        bottomBar = {
            // Warden Bottom Navigation Bar matching React spec
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(WardenScreen.copy(alpha = 0.96f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(WardenBorder)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WardenTab.values().forEach { tab ->
                        val isActive = tab == currentTab
                        val tint = if (isActive) WardenMint else WardenMutedDim
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clickable { currentTab = tab }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = tint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tab.label,
                                fontSize = 9.5.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                color = tint
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(WardenScreen)
        ) {
            when (currentTab) {
                WardenTab.HOME -> {
                    WardenHomeScreen(
                        vpnState = vpnState,
                        activeProfile = activeProfile,
                        onToggleConnection = onToggleVpn,
                        onBackToNotes = onBackToNotes
                    )
                }
                WardenTab.SERVERS -> {
                    WardenServersScreen(
                        activeProfile = activeProfile,
                        onSelectProfile = onUpdateProfile
                    )
                }
                WardenTab.TUNNEL -> {
                    WardenTunnelScreen()
                }
                WardenTab.LOGS -> {
                    WardenLogsScreen()
                }
                WardenTab.SETTINGS -> {
                    WardenSettingsScreen()
                }
            }
        }
    }
}
