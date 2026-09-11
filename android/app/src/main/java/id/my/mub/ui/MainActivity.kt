package id.my.mub.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import id.my.mub.data.LogLevel
import id.my.mub.data.LogRepository
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.service.MubxVpnService
import id.my.mub.ui.logs.LogsScreen
import id.my.mub.ui.notes.NotesScreen
import id.my.mub.ui.relay.RelayDashboardScreen
import id.my.mub.ui.theme.*

enum class AppScreen {
    NOTES,      // Camouflage First Screen: 100% functional Notes & Memo app
    RELAY,      // Minimalist One-Button Proxy Dashboard (unlocked discreetly)
    LOGS        // Detailed Diagnostics / Console Logs
}

class MainActivity : ComponentActivity() {

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            LogRepository.log("RELAY", "Relay permission was declined by user", LogLevel.WARN)
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
                // Default screen is NOTES for stealth disguise in restricted countries
                var currentScreen by remember { mutableStateOf(AppScreen.NOTES) }

                // Hardware back press handling
                BackHandler(enabled = currentScreen != AppScreen.NOTES) {
                    currentScreen = when (currentScreen) {
                        AppScreen.LOGS -> AppScreen.RELAY
                        AppScreen.RELAY -> AppScreen.NOTES
                        AppScreen.NOTES -> AppScreen.NOTES
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BgMain
                ) {
                    when (currentScreen) {
                        AppScreen.NOTES -> {
                            NotesScreen(
                                onOpenRelay = {
                                    currentScreen = AppScreen.RELAY
                                }
                            )
                        }
                        AppScreen.RELAY -> {
                            RelayDashboardScreen(
                                vpnState = vpnState,
                                currentProfile = activeProfile,
                                onBackToNotes = {
                                    currentScreen = AppScreen.NOTES
                                },
                                onToggleRelay = {
                                    if (vpnState is VpnState.Connected || vpnState is VpnState.Connecting) {
                                        stopVpnService()
                                    } else {
                                        MubxVpnService.setProfile(activeProfile)
                                        requestAndStartVpn()
                                    }
                                },
                                onOpenConsole = {
                                    currentScreen = AppScreen.LOGS
                                },
                                onUpdateProfile = { updated ->
                                    activeProfile = updated
                                    MubxVpnService.setProfile(updated)
                                }
                            )
                        }
                        AppScreen.LOGS -> {
                            LogsScreen(
                                onBack = {
                                    currentScreen = AppScreen.RELAY
                                }
                            )
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
