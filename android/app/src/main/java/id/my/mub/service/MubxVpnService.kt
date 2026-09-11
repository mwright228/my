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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
        fun setProfile(profile: VpnProfile) { currentProfile = profile }
    }

    private val vpnInterface = AtomicReference<ParcelFileDescriptor?>(null)
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var telemetryJob: Job? = null
    private var connectJob: Job? = null
    private val cleanupInProgress = AtomicBoolean(false)
    private val disconnectRequested = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogRepository.log("VPN", "MubxVpnService instantiated", LogLevel.INFO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                try { startForegroundNotification() } catch (e: Exception) {
                    LogRepository.log("VPN", "startForeground failed: ${e.message}", LogLevel.WARN)
                    try {
                        val fallback = NotificationCompat.Builder(this, MubxApplication.VPN_CHANNEL_ID)
                            .setContentTitle("VPN Active").setSmallIcon(android.R.drawable.stat_notify_sync)
                            .setPriority(NotificationCompat.PRIORITY_LOW).build()
                        startForeground(NOTIFICATION_ID, fallback)
                    } catch (e2: Exception) {
                        LogRepository.log("VPN", "startForeground failed completely: ${e2.message}", LogLevel.ERROR)
                        stopSelf(); return START_NOT_STICKY
                    }
                }
                disconnectRequested.set(false)
                connect()
            }
            ACTION_DISCONNECT -> disconnect()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val disconnectIntent = PendingIntent.getService(this, 1, Intent(this, MubxVpnService::class.java).apply { action = ACTION_DISCONNECT }, PendingIntent.FLAG_IMMUTABLE)
        val hostDisplay = currentProfile.serverHost.ifBlank { currentProfile.serverIp }
        val notification: Notification = NotificationCompat.Builder(this, MubxApplication.VPN_CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_service_title))
            .setContentText(if (hostDisplay.isNotBlank()) "VPN active • $hostDisplay" else "VPN active")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.disconnect), disconnectIntent)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun connect() {
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            _vpnState.value = VpnState.Connecting
            try {
                ensureActive()
                val profile = currentProfile
                val targetHost = profile.serverHost.ifBlank { profile.serverIp }
                if (targetHost.isBlank()) throw IllegalArgumentException("Server host/IP is required")
                val resolvedIp = withContext(Dispatchers.IO) {
                    try { java.net.InetAddress.getAllByName(targetHost).firstOrNull()?.hostAddress ?: targetHost }
                    catch (e: Exception) { LogRepository.log("VPN", "Host pre-resolution note: ${e.message}", LogLevel.WARN); targetHost }
                }
                ensureActive()
                if (disconnectRequested.get()) throw CancellationException("disconnect requested")
                val effectiveProfile = profile.copy(serverIp = resolvedIp, bugHostSNI = profile.bugHostSNI.ifBlank { profile.serverHost })
                val socksPort = NativeCoreBridge.startTunnel(effectiveProfile).getOrThrow()
                ensureActive()
                if (disconnectRequested.get()) throw CancellationException("disconnect requested")
                val dns1 = profile.dnsServer.ifBlank { "1.1.1.1" }
                val dns2 = profile.dnsSecondary.ifBlank { "8.8.8.8" }
                val builder = Builder().apply {
                    setSession("MUB-X VPN")
                    addAddress("172.19.0.1", 30)
                    addDnsServer(dns1)
                    addDnsServer(dns2)
                    addRoute("0.0.0.0", 0)
                    setMtu(1500)
                    setBlocking(true)
                    // Android's true block-without-VPN/always-on lockdown is a
                    // system setting and cannot be enabled by this service.
                }
                val established = builder.establish() ?: throw IllegalStateException("Failed to establish VpnService TUN interface")
                vpnInterface.set(established)
                val tunFd = established.fd
                if (tunFd < 0) throw IllegalStateException("Invalid TUN file descriptor")
                ensureActive()
                if (disconnectRequested.get()) throw CancellationException("disconnect requested")
                if (!NativeCoreBridge.startTunRouter(tunFd, socksPort, "$dns1:53")) throw IllegalStateException("TUN router failed to start")
                ensureActive()
                if (disconnectRequested.get()) throw CancellationException("disconnect requested")
                _vpnState.value = VpnState.Connected(0.0, 0.0, 0L, profile.poolConcurrency, 0L, 0L, 0L)
                LogRepository.log("VPN", "TUN interface active and router confirmed running", LogLevel.SUCCESS)
                startTelemetryMonitor()
            } catch (e: CancellationException) {
                cleanupResources()
                if (!disconnectRequested.get()) _vpnState.value = VpnState.Disconnected
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
            var lastRx = 0L; var lastTx = 0L; var durationSecs = 0L; var initial = true
            while (isActive) {
                delay(1000); durationSecs++
                val telem = NativeCoreBridge.getTelemetry()
                if (initial) { lastRx = telem.rxBytes; lastTx = telem.txBytes; initial = false; continue }
                val rxDelta = (telem.rxBytes - lastRx).coerceAtLeast(0L); val txDelta = (telem.txBytes - lastTx).coerceAtLeast(0L)
                lastRx = telem.rxBytes; lastTx = telem.txBytes
                val state = _vpnState.value
                if (state is VpnState.Connected) _vpnState.value = state.copy(
                    rxSpeedMbps = (rxDelta * 8.0) / 1_000_000.0,
                    txSpeedMbps = (txDelta * 8.0) / 1_000_000.0,
                    activeLanes = if (telem.activeConns > 0) telem.activeConns else currentProfile.poolConcurrency,
                    totalRxBytes = telem.rxBytes, totalTxBytes = telem.txBytes, connectedDurationSecs = durationSecs
                )
            }
        }
    }

    private fun disconnect() {
        if (!disconnectRequested.compareAndSet(false, true)) return
        connectJob?.cancel(); connectJob = null
        serviceScope.launch(Dispatchers.IO) {
            _vpnState.value = VpnState.Disconnecting
            telemetryJob?.cancel(); telemetryJob = null
            cleanupResources(); _vpnState.value = VpnState.Disconnected
            LogRepository.log("VPN", "Tunnel disconnected cleanly", LogLevel.INFO)
            withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }

    private suspend fun cleanupResources() {
        if (!cleanupInProgress.compareAndSet(false, true)) return
        try {
            NativeCoreBridge.stopTunRouter(); NativeCoreBridge.stopTunnel(); vpnInterface.getAndSet(null)?.let { runCatching { it.close() } }
        } catch (e: Throwable) { LogRepository.log("VPN", "Cleanup error: ${e.message}", LogLevel.WARN) }
        finally { cleanupInProgress.set(false) }
    }

    override fun onDestroy() {
        telemetryJob?.cancel(); telemetryJob = null; connectJob?.cancel(); connectJob = null; disconnectRequested.set(true)
        if (instance == this) instance = null
        Thread { NativeCoreBridge.forceStop(); vpnInterface.getAndSet(null)?.let { runCatching { it.close() } } }.start()
        _vpnState.value = VpnState.Disconnected
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
