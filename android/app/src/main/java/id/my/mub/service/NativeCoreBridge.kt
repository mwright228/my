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
        val vpn = MubxVpnService.instance ?: run {
            LogRepository.log("PROTECT", "VPN service unavailable while protecting socket fd $fd", LogLevel.ERROR)
            return false
        }
        val ok = vpn.protect(fd)
        if (!ok) LogRepository.log("PROTECT", "Failed to protect socket fd $fd", LogLevel.ERROR)
        return ok
    }

    @JvmStatic
    fun onNativeLog(tag: String, msg: String) {
        val level = when {
            tag.contains("ERR", true) -> LogLevel.ERROR
            tag.contains("WARN", true) -> LogLevel.WARN
            tag.contains("SUCCESS", true) -> LogLevel.SUCCESS
            tag.contains("NET", true) || tag.contains("PROTECT", true) -> LogLevel.NET
            else -> LogLevel.INFO
        }
        LogRepository.log(tag, msg, level)
        android.util.Log.d("MUBX-Core-$tag", msg)
    }

    suspend fun startTunnel(profile: VpnProfile): Result<Int> = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext Result.failure(IllegalStateException("Native VPN core is unavailable"))
        try {
            val host = profile.serverIp.ifBlank { profile.serverHost }
            if (host.isBlank()) return@withContext Result.failure(IllegalArgumentException("Server host/IP is required"))
            if (profile.protocol == id.my.mub.data.ProtocolType.SSH_PAYLOAD) {
                if (!profile.allowInsecureTLS && profile.sshHostKeySHA256.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Secure SSH requires an SSH host-key SHA256 fingerprint"))
                }
                nativeSetSSHHostKeySHA256(profile.sshHostKeySHA256)
            } else {
                nativeSetSSHHostKeySHA256("")
            }
            val dialAddr = if (profile.protocol == id.my.mub.data.ProtocolType.SSH_PAYLOAD && profile.proxyHost.isNotBlank()) {
                "${profile.proxyHost}:${if (profile.proxyPort > 0) profile.proxyPort else 80}"
            } else "$host:${profile.serverPort}"
            val effectiveSni = profile.bugHostSNI.ifBlank { profile.serverHost }
            val effectiveHostHeader = profile.wsHost.ifBlank { profile.serverHost }
            val token = if (profile.protocol == id.my.mub.data.ProtocolType.SSH_PAYLOAD) "${profile.sshUser}:${profile.sshPassword}" else profile.userUUID
            if (profile.protocol != id.my.mub.data.ProtocolType.SSH_PAYLOAD && token.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Protocol credential is required"))
            }
            val rawMode = profile.protocol == id.my.mub.data.ProtocolType.T_BRUTAL &&
                (profile.customPayload.equals("raw", true) || profile.wsPath.equals("raw", true))
            val payload = when (profile.protocol) {
                id.my.mub.data.ProtocolType.T_BRUTAL -> profile.wsPath.ifBlank { profile.customPayload.ifBlank { "/tbrutal" } }
                id.my.mub.data.ProtocolType.VLESS_WS,
                id.my.mub.data.ProtocolType.VMESS_WS,
                id.my.mub.data.ProtocolType.TROJAN_WS -> profile.wsPath.ifBlank { "/vless-ws" }
                id.my.mub.data.ProtocolType.SHADOWTLS_V3 -> profile.ssCipher.ifBlank { "2022-blake3-aes-256-gcm" }
                else -> profile.customPayload
            }
            val obfsKey = when (profile.protocol) {
                id.my.mub.data.ProtocolType.VLESS_REALITY -> profile.realityPublicKey
                id.my.mub.data.ProtocolType.TUIC -> profile.tuicPassword
                id.my.mub.data.ProtocolType.SHADOWSOCKS_2022 -> profile.ssCipher.ifBlank { "2022-blake3-aes-128-gcm" }
                id.my.mub.data.ProtocolType.SHADOWTLS_V3 -> profile.udpObfsPassword
                else -> profile.udpObfsPassword
            }
            val secondValue = when (profile.protocol) {
                id.my.mub.data.ProtocolType.VLESS_REALITY -> profile.realityShortId
                else -> profile.udpPortHopRange
            }
            if (profile.protocol == id.my.mub.data.ProtocolType.VLESS_REALITY && (obfsKey.isBlank() || secondValue.isBlank())) {
                return@withContext Result.failure(IllegalArgumentException("VLESS Reality requires public key and short ID"))
            }
            if (profile.protocol == id.my.mub.data.ProtocolType.TUIC && obfsKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("TUIC requires a password"))
            }
            val useTls = profile.protocol != id.my.mub.data.ProtocolType.T_BRUTAL || profile.serverPort == 443 || profile.serverPort == 8443
            val port = nativeStartTunnel(
                profile.protocol.name, dialAddr, effectiveSni, effectiveHostHeader, token,
                profile.poolConcurrency, profile.brutalRateMbps, useTls, profile.allowInsecureTLS,
                rawMode, obfsKey, secondValue, profile.dnsServer, payload
            )
            if (port <= 0) return@withContext Result.failure(Exception("Connection to server failed (code $port). Verify server address, port, and credentials."))
            try {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
                LogRepository.log("TUNNEL", "Local SOCKS5 proxy active & verified on 127.0.0.1:$port", LogLevel.SUCCESS)
                Result.success(port)
            } catch (sockErr: Exception) {
                runCatching { nativeStopTunnel() }
                Result.failure(IllegalStateException("SOCKS proxy on port $port failed connectivity check: ${sockErr.message}"))
            }
        } catch (e: Throwable) {
            LogRepository.log("TUNNEL", "Tunnel start error: ${e.message}", LogLevel.ERROR)
            Result.failure(e)
        }
    }

    suspend fun stopTunnel() = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext
        runCatching { nativeStopTunnel() }.onFailure { LogRepository.log("TUNNEL", "Native tunnel stop error: ${it.message}", LogLevel.WARN) }
    }

    fun forceStop() {
        if (!nativeLoaded) return
        runCatching { nativeStopTunRouter() }.onFailure { LogRepository.log("ROUTER", "Emergency TUN stop error: ${it.message}", LogLevel.WARN) }
        runCatching { nativeStopTunnel() }.onFailure { LogRepository.log("TUNNEL", "Emergency tunnel stop error: ${it.message}", LogLevel.WARN) }
    }

    suspend fun startTunRouter(tunFd: Int, socksPort: Int, dnsServer: String = "1.1.1.1:53"): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext false
        runCatching { nativeStartTunRouter(tunFd, socksPort, dnsServer) }
            .onFailure { LogRepository.log("ROUTER", "Native TUN router start failed: ${it.message}", LogLevel.ERROR) }
            .getOrDefault(false)
    }

    suspend fun stopTunRouter() = withContext(Dispatchers.IO) {
        if (!nativeLoaded) return@withContext
        runCatching { nativeStopTunRouter() }.onFailure { LogRepository.log("ROUTER", "Native TUN router stop error: ${it.message}", LogLevel.WARN) }
    }

    fun getTelemetry(): RealTelemetry {
        if (!nativeLoaded) return RealTelemetry(0L, 0L, 0)
        return try {
            val parts = nativeGetTelemetry().split("|")
            RealTelemetry(parts.getOrNull(0)?.toLongOrNull() ?: 0L, parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2)?.toIntOrNull() ?: 0)
        } catch (_: Throwable) { RealTelemetry(0L, 0L, 0) }
    }

    suspend fun probeBugHost(url: String, sni: String, timeoutMs: Int = 3000): BugHostProbeResult = withContext(Dispatchers.IO) {
        try {
            val raw = if (nativeLoaded) nativeProbeBugHost(url, sni, timeoutMs) else throw IllegalStateException("Native core unavailable")
            val parts = raw.split("|")
            BugHostProbeResult(url, parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2) ?: "", parts.getOrNull(3)?.split(",")?.filter { it.isNotBlank() } ?: emptyList(), parts.getOrNull(4)?.takeIf { it.isNotBlank() })
        } catch (_: Exception) {
            try {
                val start = System.currentTimeMillis()
                val client = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                client.connectTimeout = timeoutMs; client.readTimeout = timeoutMs; client.instanceFollowRedirects = false
                BugHostProbeResult(url, client.responseCode, System.currentTimeMillis() - start, "", emptyList(), null)
            } catch (netEx: Exception) { BugHostProbeResult(url, 0, 0, "", emptyList(), netEx.message) }
        }
    }

    private external fun nativeSetSSHHostKeySHA256(fingerprint: String)
    private external fun nativeStartTunnel(protocol: String, serverAddr: String, sni: String, hostHeader: String, token: String, poolSize: Int, rateMbps: Int, useTLS: Boolean, insecureTLS: Boolean, rawMode: Boolean, obfsKey: String, portHopRange: String, dnsServer: String, customPayload: String): Int
    private external fun nativeStopTunnel()
    private external fun nativeStartTunRouter(tunFd: Int, socksPort: Int, dnsServer: String): Boolean
    private external fun nativeStopTunRouter()
    private external fun nativeGetTelemetry(): String
    private external fun nativeProbeBugHost(url: String, sni: String, timeoutMs: Int): String
}
