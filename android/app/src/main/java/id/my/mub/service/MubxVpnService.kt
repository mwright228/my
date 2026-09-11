package id.my.mub.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import id.my.mub.MubxApplication
import id.my.mub.R
import id.my.mub.data.LogLevel
import id.my.mub.data.LogRepository
import id.my.mub.data.VpnProfile
import id.my.mub.data.VpnState
import id.my.mub.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MubxVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "id.my.mub.action.CONNECT"
        const val ACTION_DISCONNECT = "id.my.mub.action.DISCONNECT"
        const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        var instance: MubxVpnService? = null
            private set

        var currentProfile: VpnProfile = VpnProfile()
            private set

        fun setProfile(profile: VpnProfile) {
            currentProfile = profile
        }

        private val BANKING_SECURITY_PACKAGES = listOf(
            // Global Fintech & Wallets
            "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile",
            "com.binance.dev",
            "com.revolut.revolut",
            "com.wise.android",
            // Regional Banking & Wallets (JazzCash, EasyPaisa, SadaPay, NayaPay, Top Banks)
            "com.techlogix.mobilinkcustomer",
            "pk.com.telenor.phoenix",
            "com.sadapay.app",
            "com.nayapay.app",
            "com.hbl.mobilebanking",
            "com.innovative.meezan",
            "com.mcb.mobile",
            "com.ubl.digital",
            "com.faysalbank.digibank",
            "com.alfa.bankalfalah",
            "com.abpl.mobilebanking",
            // International Banks
            "com.chase.sig.android",
            "com.infonow.bofa",
            "com.wf.wellsfargomobile",
            "com.citi.citimobile",
            "com.barclays.android.barclaysmobilebanking"
        )
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var telemetryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogRepository.log("VPN", "MubxVpnService instantiated", LogLevel.INFO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                startForegroundNotification()
                connect()
            }
            ACTION_DISCONNECT -> {
                disconnect()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1, Intent(this, MubxVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )

        val hostDisplay = currentProfile.serverHost.ifBlank { currentProfile.serverIp }
        val notification: Notification = NotificationCompat.Builder(this, MubxApplication.VPN_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_service_title))
            .setContentText(if (hostDisplay.isNotBlank()) "Active • $hostDisplay" else "Network monitor active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), disconnectIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun connect() {
        serviceScope.launch {
            _vpnState.value = VpnState.Connecting
            LogRepository.log("RELAY", "Initializing relay connection for: ${currentProfile.name}", LogLevel.INFO)
            try {
                // 1. Launch multi-protocol Go Core connection pool
                val socksPort = NativeCoreBridge.startTunnel(currentProfile).getOrThrow()

                // 2. Establish Android TUN Interface (tun0)
                val dns1 = currentProfile.dnsServer.ifBlank { "1.1.1.1" }
                val dns2 = currentProfile.dnsSecondary.ifBlank { "8.8.8.8" }

                val builder = Builder().apply {
                    setSession("NetPulse Service")
                    addAddress("172.19.0.1", 30)
                    addDnsServer(dns1)
                    addDnsServer(dns2)
                    addRoute("0.0.0.0", 0)
                    setMtu(1500)
                    setBlocking(true)

                    if (currentProfile.killSwitchEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setMetered(false)
                    }

                    // Banking Security Bypass: Exclude banking apps from VPN routing
                    for (pkg in BANKING_SECURITY_PACKAGES) {
                        try {
                            addDisallowedApplication(pkg)
                        } catch (ignored: Exception) {
                            // App not installed on device; safely skip
                        }
                    }
                }

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    throw IllegalStateException("Failed to establish VpnService TUN interface")
                }

                // 3. Hand off Layer 3 TUN file descriptor to native Go packet router
                val tunFd = vpnInterface?.fd ?: -1
                if (tunFd >= 0) {
                    NativeCoreBridge.startTunRouter(tunFd, socksPort, "$dns1:53")
                }

                _vpnState.value = VpnState.Connected(
                    rxSpeedMbps = 0.0,
                    txSpeedMbps = 0.0,
                    pingMs = 0L,
                    activeLanes = currentProfile.poolConcurrency,
                    totalRxBytes = 0L,
                    totalTxBytes = 0L,
                    connectedDurationSecs = 0L
                )

                LogRepository.log("VPN", "TUN interface tun0 active. Wire routing running.", LogLevel.SUCCESS)
                startTelemetryMonitor()
            } catch (e: Exception) {
                LogRepository.log("VPN", "Tunnel connection failed: ${e.message}", LogLevel.ERROR)
                _vpnState.value = VpnState.Error(e.message ?: "Tunnel connection failed")
                disconnect()
            }
        }
    }

    private fun startTelemetryMonitor() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            var lastRx = 0L
            var lastTx = 0L
            var durationSecs = 0L
            var initial = true

            while (isActive) {
                delay(1000)
                durationSecs++

                val telem = NativeCoreBridge.getTelemetry()
                if (initial) {
                    lastRx = telem.rxBytes
                    lastTx = telem.txBytes
                    initial = false
                    continue
                }

                val rxDelta = (telem.rxBytes - lastRx).coerceAtLeast(0L)
                val txDelta = (telem.txBytes - lastTx).coerceAtLeast(0L)
                lastRx = telem.rxBytes
                lastTx = telem.txBytes

                val rxMbps = (rxDelta * 8.0) / 1_000_000.0
                val txMbps = (txDelta * 8.0) / 1_000_000.0

                val state = _vpnState.value
                if (state is VpnState.Connected) {
                    _vpnState.value = state.copy(
                        rxSpeedMbps = rxMbps,
                        txSpeedMbps = txMbps,
                        activeLanes = if (telem.activeConns > 0) telem.activeConns else currentProfile.poolConcurrency,
                        totalRxBytes = telem.rxBytes,
                        totalTxBytes = telem.txBytes,
                        connectedDurationSecs = durationSecs
                    )
                }
            }
        }
    }

    private fun disconnect() {
        serviceScope.launch {
            _vpnState.value = VpnState.Disconnecting
            telemetryJob?.cancel()

            try {
                NativeCoreBridge.stopTunRouter()
                NativeCoreBridge.stopTunnel()
                vpnInterface?.close()
                vpnInterface = null
            } catch (ignored: Exception) {
            }

            LogRepository.log("VPN", "Tunnel disconnected cleanly", LogLevel.INFO)
            _vpnState.value = VpnState.Disconnected
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        disconnect()
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }
}
