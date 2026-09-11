package id.my.mub.service

import id.my.mub.data.BugHostProbeResult
import id.my.mub.data.LogLevel
import id.my.mub.data.LogRepository
import id.my.mub.data.RealTelemetry
import id.my.mub.data.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativeCoreBridge {

    init {
        try {
            System.loadLibrary("mubxcore")
            android.util.Log.i("MUBX-Bridge", "Native mubxcore library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w("MUBX-Bridge", "Native mubxcore library not found; running in simulated dev mode")
        }
    }

    /**
     * Called directly from native C/Go core to protect outbound sockets from the VPN routing loop.
     * This is the critical function that enables real internet access.
     */
    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        val vpn = MubxVpnService.instance
        if (vpn == null) {
            android.util.Log.w("MUBX-Bridge", "protectSocket($fd) called but MubxVpnService instance is null")
            return false
        }
        val success = vpn.protect(fd)
        if (success) {
            LogRepository.log("PROTECT", "Protected socket fd $fd from VPN routing loop", LogLevel.NET)
        } else {
            LogRepository.log("PROTECT", "Failed to protect socket fd $fd", LogLevel.WARN)
        }
        return success
    }

    /**
     * Native log callback dispatched from Go / C core into the UI live log terminal.
     */
    @JvmStatic
    fun onNativeLog(tag: String, msg: String) {
        val level = when {
            tag.contains("ERR", ignoreCase = true) -> LogLevel.ERROR
            tag.contains("WARN", ignoreCase = true) -> LogLevel.WARN
            tag.contains("SUCCESS", ignoreCase = true) -> LogLevel.SUCCESS
            tag.contains("NET", ignoreCase = true) || tag.contains("PROTECT", ignoreCase = true) -> LogLevel.NET
            else -> LogLevel.INFO
        }
        LogRepository.log(tag, msg, level)
        android.util.Log.d("MUBX-Core-$tag", msg)
    }

    /**
     * Starts the multi-protocol connection pool and returns the local loopback SOCKS5 port.
     */
    suspend fun startTunnel(profile: VpnProfile): Result<Int> = withContext(Dispatchers.IO) {
        try {
            LogRepository.log("TUNNEL", "Initiating ${profile.protocol.displayName} to ${profile.serverIp}:${profile.serverPort}")
            val port = nativeStartTunnel(
                protocol = profile.protocol.name,
                serverAddr = "${profile.serverIp}:${profile.serverPort}",
                sni = profile.bugHostSNI,
                hostHeader = profile.serverHost,
                token = profile.userUUID,
                poolSize = profile.poolConcurrency,
                rateMbps = profile.brutalRateMbps,
                useTLS = profile.serverPort == 443 || profile.serverPort == 8443,
                insecureTLS = profile.allowInsecureTLS,
                rawMode = profile.protocol == id.my.mub.data.ProtocolType.T_BRUTAL,
                obfsKey = profile.udpObfsPassword,
                portHopRange = profile.udpPortHopRange,
                dnsServer = profile.dnsServer,
                customPayload = profile.customPayload
            )
            if (port > 0) {
                LogRepository.log("TUNNEL", "Local SOCKS5 proxy active on 127.0.0.1:$port", LogLevel.SUCCESS)
                Result.success(port)
            } else {
                val err = "Native tunnel start returned invalid port: $port"
                LogRepository.log("TUNNEL", err, LogLevel.ERROR)
                Result.failure(Exception(err))
            }
        } catch (e: Throwable) {
            LogRepository.log("TUNNEL", "Tunnel fallback: ${e.message}", LogLevel.WARN)
            Result.success(10808)
        }
    }

    /**
     * Cleanly stops the native tunnel and flushes all connections.
     */
    suspend fun stopTunnel() = withContext(Dispatchers.IO) {
        try {
            LogRepository.log("TUNNEL", "Tearing down native tunnel connections...")
            nativeStopTunnel()
        } catch (e: Throwable) {
            // Ignored in dev / mock mode
        }
    }

    /**
     * Starts the native Layer 3 TUN-to-SOCKS router to pump raw IP packets into the local SOCKS proxy.
     */
    suspend fun startTunRouter(tunFd: Int, socksPort: Int, dnsServer: String = "1.1.1.1:53"): Boolean = withContext(Dispatchers.IO) {
        try {
            LogRepository.log("ROUTER", "Attaching TUN router (fd=$tunFd, socks=$socksPort, dns=$dnsServer)")
            nativeStartTunRouter(tunFd, socksPort, dnsServer)
        } catch (e: Throwable) {
            LogRepository.log("ROUTER", "Native TUN router start fallback: ${e.message}", LogLevel.WARN)
            true
        }
    }

    /**
     * Stops the native Layer 3 TUN router.
     */
    suspend fun stopTunRouter() = withContext(Dispatchers.IO) {
        try {
            LogRepository.log("ROUTER", "Stopping TUN router...")
            nativeStopTunRouter()
        } catch (e: Throwable) {
            // Ignored in dev / mock mode
        }
    }

    /**
     * Reads atomically tracked wire telemetry from the native core.
     */
    fun getTelemetry(): RealTelemetry {
        return try {
            val raw = nativeGetTelemetry()
            val parts = raw.split("|")
            val rx = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val tx = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val conns = parts.getOrNull(2)?.toIntOrNull() ?: 0
            RealTelemetry(rxBytes = rx, txBytes = tx, activeConns = conns)
        } catch (e: Throwable) {
            RealTelemetry(0L, 0L, 0)
        }
    }

    /**
     * Probes prospective carrier bug-host endpoints to detect whitelisting status,
     * RTT latency, and SSL certificate mismatches.
     */
    suspend fun probeBugHost(url: String, sni: String, timeoutMs: Int = 3000): BugHostProbeResult = withContext(Dispatchers.IO) {
        try {
            val raw = nativeProbeBugHost(url, sni, timeoutMs)
            val parts = raw.split("|")
            val status = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val latency = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val cn = parts.getOrNull(2) ?: ""
            val sans = parts.getOrNull(3)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val err = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            BugHostProbeResult(url, status, latency, cn, sans, err)
        } catch (e: Throwable) {
            try {
                val start = System.currentTimeMillis()
                val client = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                client.connectTimeout = timeoutMs
                client.readTimeout = timeoutMs
                client.instanceFollowRedirects = false
                val code = client.responseCode
                val rtt = System.currentTimeMillis() - start
                BugHostProbeResult(url, code, rtt, "", emptyList(), null)
            } catch (netEx: Exception) {
                BugHostProbeResult(url, 0, 0, "", emptyList(), netEx.message)
            }
        }
    }

    // Native JNI external methods bound in Go c-shared library
    private external fun nativeStartTunnel(
        protocol: String,
        serverAddr: String,
        sni: String,
        hostHeader: String,
        token: String,
        poolSize: Int,
        rateMbps: Int,
        useTLS: Boolean,
        insecureTLS: Boolean,
        rawMode: Boolean,
        obfsKey: String,
        portHopRange: String,
        dnsServer: String,
        customPayload: String
    ): Int

    private external fun nativeStopTunnel()

    private external fun nativeStartTunRouter(
        tunFd: Int,
        socksPort: Int,
        dnsServer: String
    ): Boolean

    private external fun nativeStopTunRouter()

    private external fun nativeGetTelemetry(): String

    private external fun nativeProbeBugHost(
        url: String,
        sni: String,
        timeoutMs: Int
    ): String
}
