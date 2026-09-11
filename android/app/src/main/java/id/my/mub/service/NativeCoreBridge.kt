package id.my.mub.service

import id.my.mub.data.BugHostProbeResult
import id.my.mub.data.LogLevel
import id.my.mub.data.LogRepository
import id.my.mub.data.RealTelemetry
import id.my.mub.data.VpnProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NativeCoreBridge {

    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("mubxcore")
            nativeLoaded = true
            android.util.Log.i("MUBX-Bridge", "Native mubxcore library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("MUBX-Bridge", "Native mubxcore library is required but could not be loaded", e)
        }
    }

    @JvmStatic
    fun protectSocket(fd: Int): Boolean {
        val vpn = MubxVpnService.instance
        if (vpn == null) {
            LogRepository.log("PROTECT", "VPN service unavailable while protecting socket fd $fd", LogLevel.ERROR)
            return false
        }
        val success = vpn.protect(fd)
        if (!success) {
            LogRepository.log("PROTECT", "Failed to protect socket fd $fd", LogLevel.ERROR)
        }
        return success
    }

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

    suspend fun startTunnel(profile: VpnProfile): Result<Int> = withContext(Dispatchers.IO) {
        if (!nativeLoaded) {
            return@withContext Result.failure(IllegalStateException("Native VPN core is unavailable"))
        }
        try {
            val host = if (profile.serverIp.isNotBlank()) profile.serverIp else profile.serverHost
            if (host.isBlank()) {
                val err = "Server host/IP is required. Please check your configuration."
                LogRepository.log("TUNNEL", err, LogLevel.ERROR)
                return@withContext Result.failure(IllegalArgumentException(err))
            }

            val dialAddr = if (profile.protocol == id.my.mub.data.ProtocolType.SSH_PAYLOAD && profile.proxyHost.isNotBlank()) {
                val pPort = if (profile.proxyPort > 0) profile.proxyPort else 80
                "${profile.proxyHost}:$pPort"
            } else {
                "$host:${profile.serverPort}"
            }
            val effectiveSni = if (profile.bugHostSNI.isNotBlank()) profile.bugHostSNI else profile.serverHost
            val effectiveHostHeader = if (profile.wsHost.isNotBlank()) profile.wsHost else profile.serverHost
            val effectiveToken = if (profile.protocol == id.my.mub.data.ProtocolType.SHADOWSOCKS_2022 || profile.protocol == id.my.mub.data.ProtocolType.SHADOWTLS_V3) {
                profile.userUUID
            } else if (profile.protocol == id.my.mub.data.ProtocolType.SSH_PAYLOAD) {
                "${profile.sshUser}:${profile.sshPassword}"
            } else {
                profile.userUUID
            }

            val isRawMode = profile.protocol == id.my.mub.data.ProtocolType.T_BRUTAL &&
                    (profile.customPayload.equals("raw", ignoreCase = true) || profile.wsPath.equals("raw", ignoreCase = true))

            val effectivePayload = when (profile.protocol) {
                id.my.mub.data.ProtocolType.SHADOWTLS_V3 -> profile.ssCipher.ifBlank { "2022-blake3-aes-256-gcm" }
                id.my.mub.data.ProtocolType.T_BRUTAL -> if (profile.wsPath.isNotBlank()) profile.wsPath else if (profile.customPayload.isNotBlank()) profile.customPayload else "/tbrutal"
                id.my.mub.data.ProtocolType.VLESS_WS -> if (profile.wsPath.isNotBlank()) profile.wsPath else if (profile.customPayload.isNotBlank()) profile.customPayload else "/vless-ws"
                else -> profile.customPayload
            }

            val effectiveObfsKey = when (profile.protocol) {
                id.my.mub.data.ProtocolType.SHADOWSOCKS_2022 -> profile.ssCipher.ifBlank { "2022-blake3-aes-128-gcm" }
                id.my.mub.data.ProtocolType.SHADOWTLS_V3 -> profile.udpObfsPassword
                else -> profile.udpObfsPassword
            }

            LogRepository.log("TUNNEL", "Initiating ${profile.protocol.displayName} to $dialAddr")
            val port = nativeStartTunnel(
                protocol = profile.protocol.name,
                serverAddr = dialAddr,
                sni = effectiveSni,
                hostHeader = effectiveHostHeader,
                token = effectiveToken,
                poolSize = profile.poolConcurrency,
                rateMbps = profile.brutalRateMbps,
                useTLS = profile.serverPort == 443 || profile.serverPort == 8443,
                insecureTLS = profile.allowInsecureTLS,
                rawMode = isRawMode,
                obfsKey = effectiveObfsKey,
                portHopRange = profile.udpPortHopRange,
                dnsServer = profile.dnsServer,
                customPayload = effectivePayload
            )
            if (port > 0) {
                try {
                    java.net.Socket().use { testSock ->
                        testSock.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
                    }
                    LogRepository.log("TUNNEL", "Local SOCKS5 proxy active & verified on 127.0.0.1:$port", LogLevel.SUCCESS)
                    Result.success(port)
                } catch (sockErr: Exception) {
                    val err = "SOCKS proxy on port $port failed connectivity check: ${sockErr.message}"
                    LogRepository.log("TUNNEL", err, LogLevel.ERROR)
                    runCatching { nativeStopTunnel() }
                    Result.failure(IllegalStateException(err))
                }
            } else {
                val err = "Connection to server failed (code $port). Verify server address, port, and credentials."
                LogRepository.log("TUNNEL", err, LogLevel.ERROR)
                Result.failure(Exception(err))
            }
        } catch (e: Throwable) {
            LogRepository.log("TUNNEL", "Tunnel start error: ${e.message}", LogLevel.ERROR)
            Result.failure(e)
        }
    }

    suspend fun stopTunnel() = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext
        try {
            LogRepository.log("TUNNEL", "Tearing down native tunnel connections...")
            nativeStopTunnel()
        } catch (e: Throwable) {
            LogRepository.log("TUNNEL", "Native tunnel stop error: ${e.message}", LogLevel.WARN)
        }
    }

    suspend fun startTunRouter(tunFd: Int, socksPort: Int, dnsServer: String = "1.1.1.1:53"): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLoaded) {
            LogRepository.log("ROUTER", "Native VPN core is unavailable", LogLevel.ERROR)
            return@withContext false
        }
        try {
            LogRepository.log("ROUTER", "Attaching TUN router (fd=$tunFd, socks=$socksPort, dns=$dnsServer)")
            val started = nativeStartTunRouter(tunFd, socksPort, dnsServer)
            if (!started) {
                LogRepository.log("ROUTER", "Native TUN router rejected startup", LogLevel.ERROR)
            }
            started
        } catch (e: Throwable) {
            LogRepository.log("ROUTER", "Native TUN router start failed: ${e.message}", LogLevel.ERROR)
            false
        }
    }

    suspend fun stopTunRouter() = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext
        try {
            LogRepository.log("ROUTER", "Stopping TUN router...")
            nativeStopTunRouter()
        } catch (e: Throwable) {
            LogRepository.log("ROUTER", "Native TUN router stop error: ${e.message}", LogLevel.WARN)
        }
    }

    fun getTelemetry(): RealTelemetry {
        if (!nativeLoaded) return RealTelemetry(0L, 0L, 0)
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

    suspend fun probeBugHost(url: String, sni: String, timeoutMs: Int = 3000): BugHostProbeResult = withContext(Dispatchers.IO) {
        try {
            val raw = if (nativeLoaded) nativeProbeBugHost(url, sni, timeoutMs) else throw IllegalStateException("Native core unavailable")
            val parts = raw.split("|")
            val status = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val latency = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val cn = parts.getOrNull(2) ?: ""
            val sans = parts.getOrNull(3)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val err = parts.getOrNull(4)?.takeIf { it.isNotBlank() }
            BugHostProbeResult(url, status, latency, cn, sans, err)
        } catch (e: Exception) {
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

    private external fun nativeStartTunnel(protocol: String, serverAddr: String, sni: String, hostHeader: String, token: String, poolSize: Int, rateMbps: Int, useTLS: Boolean, insecureTLS: Boolean, rawMode: Boolean, obfsKey: String, portHopRange: String, dnsServer: String, customPayload: String): Int
    private external fun nativeStopTunnel()
    private external fun nativeStartTunRouter(tunFd: Int, socksPort: Int, dnsServer: String): Boolean
    private external fun nativeStopTunRouter()
    private external fun nativeGetTelemetry(): String
    private external fun nativeProbeBugHost(url: String, sni: String, timeoutMs: Int): String
}
