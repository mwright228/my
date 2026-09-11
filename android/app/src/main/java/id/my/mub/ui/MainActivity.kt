package id.my.mub.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import id.my.mub.data.LogRepository
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.service.MubxVpnService
import id.my.mub.ui.config.ConfigEditorScreen
import id.my.mub.ui.dashboard.DashboardScreen
import id.my.mub.ui.diagnostics.NetworkDiagnosticsScreen
import id.my.mub.ui.logs.LogsScreen
import id.my.mub.ui.theme.*

enum class NavigationTab(val title: String, val icon: String) {
    DIAGNOSTICS("Network", "📊"),
    RELAY("Relay", "⚡"),
    CONFIG("Config", "⚙️"),
    LOGS("Console", "📜")
}

class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            LogRepository.log("RELAY", "Relay permission was declined by user", id.my.mub.data.LogLevel.WARN)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        checkAndRequestBatteryOptimization()

        setContent {
            MubxVpnTheme {
                val vpnState by MubxVpnService.vpnState.collectAsState()
                var activeProfile by remember { mutableStateOf(MubxVpnService.currentProfile) }
                // First screen is DIAGNOSTICS for complete stealth camouflage
                var selectedTab by remember { mutableStateOf(NavigationTab.DIAGNOSTICS) }

                Scaffold(
                    containerColor = BgMain,
                    bottomBar = {
                        NetPulseBottomBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            NavigationTab.DIAGNOSTICS -> {
                                NetworkDiagnosticsScreen()
                            }
                            NavigationTab.RELAY -> {
                                DashboardScreen(
                                    vpnState = vpnState,
                                    profile = activeProfile,
                                    onToggleConnect = {
                                        if (vpnState is VpnState.Connected || vpnState is VpnState.Connecting) {
                                            stopVpnService()
                                        } else {
                                            MubxVpnService.setProfile(activeProfile)
                                            requestAndStartVpn()
                                        }
                                    },
                                    onNavigateToConfig = {
                                        selectedTab = NavigationTab.CONFIG
                                    },
                                    onNavigateToLogs = {
                                        selectedTab = NavigationTab.LOGS
                                    }
                                )
                            }
                            NavigationTab.CONFIG -> {
                                ConfigEditorScreen(
                                    currentProfile = activeProfile,
                                    onSaveAndDeploy = { updated ->
                                        activeProfile = updated
                                        MubxVpnService.setProfile(updated)
                                        selectedTab = NavigationTab.RELAY
                                    },
                                    onSaveAsNew = { newProf ->
                                        activeProfile = newProf
                                        MubxVpnService.setProfile(newProf)
                                        selectedTab = NavigationTab.RELAY
                                    }
                                )
                            }
                            NavigationTab.LOGS -> {
                                LogsScreen(
                                    onBack = {
                                        selectedTab = NavigationTab.RELAY
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPrepareLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, MubxVpnService::class.java).apply {
            action = MubxVpnService.ACTION_CONNECT
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, MubxVpnService::class.java).apply {
            action = MubxVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun checkAndRequestBatteryOptimization() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (ignored: Exception) {
                }
            }
        }
    }
}

@Composable
fun NetPulseBottomBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgMain)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceCard)
                .border(1.dp, SurfaceCardBorder, RoundedCornerShape(16.dp))
                .padding(vertical = 4.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationTab.values().forEach { tab ->
                val isSelected = tab == selectedTab
                val contentColor = if (isSelected) AccentPrimary else TextSecondary
                val bgTab = if (isSelected) AccentPrimary.copy(alpha = 0.12f) else Color.Transparent

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgTab)
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = tab.icon, fontSize = 14.sp)
                        if (isSelected) {
                            Text(
                                text = tab.title,
                                color = contentColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
