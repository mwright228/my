package id.my.mub.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProtocolType(val displayName: String, val badge: String) {
    T_BRUTAL("T-Brutal v2.0", "Wire-Speed Pacing"),
    ZIVPN_UDP("ZiVPN UDP / Custom", "Hysteria v1 + Salamander Obfs"),
    VLESS_TCP("VLESS Direct TCP", "Direct SNI Bypass"),
    VLESS_WS("VLESS-WS", "CDN WebSocket"),
    HYSTERIA_2("Hysteria 2", "UDP QUIC Brutal"),
    SHADOWSOCKS_2022("Shadowsocks 2022", "AEAD blake3-aes"),
    SHADOWTLS_V3("ShadowTLS v3", "TLS Decoy Camouflage"),
    AMNEZIA_WG("AmneziaWG", "Obfuscated WireGuard"),
    SSH_PAYLOAD("SSH Chameleon", "HTTP Injector / Split")
}

data class VpnProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "MUB-X Primary Node",
    val serverHost: String = "gr.mub.my.id",
    val serverIp: String = "212.60.151.69",
    val serverPort: Int = 443,
    val bugHostSNI: String = "images.vodafone.co.uk",
    val userUUID: String = "af6332d2-9baf-4c1b-b54d-520f31744c1c",
    val protocol: ProtocolType = ProtocolType.T_BRUTAL,
    val poolConcurrency: Int = 4,
    val brutalRateMbps: Int = 80,
    val allowInsecureTLS: Boolean = true,
    val customPayload: String = "",
    val udpObfsPassword: String = "zivpn",
    val udpPortHopRange: String = "6000:19999",
    val dnsServer: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val udpForwarding: Boolean = true,
    val sshUser: String = "",
    val sshPassword: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
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

enum class LogLevel {
    INFO, SUCCESS, WARN, ERROR, NET
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val message: String,
    val level: LogLevel = LogLevel.INFO
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}

data class RealTelemetry(
    val rxBytes: Long,
    val txBytes: Long,
    val activeConns: Int
)
