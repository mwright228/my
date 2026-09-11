package id.my.mub.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProtocolType(val displayName: String, val badge: String) {
    VLESS_WS("VLESS (WebSocket)", "CDN / TLS"),
    VLESS_TCP("VLESS (Direct TCP)", "Direct TLS"),
    VLESS_REALITY("VLESS (Reality)", "Reality / uTLS"),
    VMESS_WS("VMess (WebSocket)", "VMess / WS"),
    TROJAN_WS("Trojan (WebSocket)", "Trojan / WS"),
    SHADOWSOCKS("Shadowsocks", "AEAD Cipher"),
    SHADOWSOCKS_2022("Shadowsocks 2022", "AEAD / BLAKE3"),
    ZIVPN_UDP("ZiVPN (UDP Custom)", "UDP Obfuscation"),
    SSH_PAYLOAD("SSH / HTTP Injector", "Payload Injection"),
    T_BRUTAL("T-Brutal", "Congestion Pacing"),
    HYSTERIA_2("Hysteria 2", "QUIC Protocol"),
    TUIC("TUIC", "QUIC Protocol"),
    SHADOWTLS_V3("ShadowTLS", "TLS Camouflage"),
    AMNEZIA_WG("AmneziaWG", "WireGuard Obfs")
}

data class VpnProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "My Configuration",
    val serverHost: String = "",
    val serverIp: String = "",
    val serverPort: Int = 443,
    val bugHostSNI: String = "",
    val userUUID: String = "",
    val protocol: ProtocolType = ProtocolType.VLESS_WS,
    val poolConcurrency: Int = 2,
    val brutalRateMbps: Int = 50,
    val allowInsecureTLS: Boolean = false,
    val customPayload: String = "",
    val udpObfsPassword: String = "",
    val udpPortHopRange: String = "",
    val dnsServer: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val udpForwarding: Boolean = true,
    val sshUser: String = "",
    val sshPassword: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val wsPath: String = "/vless-ws",
    val wsHost: String = "",
    val ssCipher: String = "2022-blake3-aes-128-gcm",
    val vlessFlow: String = "",
    val tuicPassword: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val killSwitchEnabled: Boolean = false
)

sealed class VpnState {
    object Disconnected : VpnState()
    object Connecting : VpnState()
    data class Connected(
        val rxSpeedMbps: Double,
        val txSpeedMbps: Double,
        val pingMs: Long,
        val activeLanes: Int,
        val totalRxBytes: Long = 0L,
        val totalTxBytes: Long = 0L,
        val connectedDurationSecs: Long = 0L
    ) : VpnState()
    object Disconnecting : VpnState()
    data class Error(val message: String) : VpnState()
}

data class BugHostProbeResult(
    val url: String,
    val statusCode: Int = 0,
    val latencyMs: Long = 0,
    val certCN: String = "",
    val certSANs: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    val isWhitelisted: Boolean
        get() = statusCode in 200..399 && errorMessage == null
}

enum class LogLevel { INFO, SUCCESS, WARN, ERROR, NET }

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}

data class RealTelemetry(val rxBytes: Long, val txBytes: Long, val activeConns: Int)
