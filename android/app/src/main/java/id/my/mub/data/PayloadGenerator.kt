package id.my.mub.data

enum class PayloadPreset(val title: String, val description: String) {
    NORMAL("Normal HTTP Connect", "Standard HTTP proxy tunnel header"),
    FRONT_INJECT("Front Inject", "Injects decoy HTTP GET before CONNECT request"),
    BACK_INJECT("Back Inject", "Sends CONNECT first, followed by decoy GET request"),
    WEBSOCKET_CDN("WebSocket CDN", "WebSocket upgrade headers for Cloudflare / Fastly CDN"),
    SPLIT_DECOY("Split Decoy", "Splits handshake packet to bypass DPI deep packet inspection"),
    DELAY_SPLIT("Delay Split", "Delays second packet segment to defeat stateful carrier firewalls")
}

object PayloadGenerator {

    /**
     * Common macro chips for HTTP Custom / HTTP Injector power users.
     */
    val MACRO_CHIPS = listOf(
        "[host]",
        "[port]",
        "[host_port]",
        "[crlf]",
        "[split]",
        "[delay_split]",
        "[protocol]",
        "[ua]",
        "[raw]"
    )

    fun generate(preset: PayloadPreset, bugHost: String, targetPort: Int = 443): String {
        val host = if (bugHost.isNotBlank()) bugHost else "images.vodafone.co.uk"
        return when (preset) {
            PayloadPreset.NORMAL -> {
                "CONNECT [host_port] [protocol][crlf]Host: $host[crlf]X-Online-Host: $host[crlf]X-Forward-Host: $host[crlf]Connection: Keep-Alive[crlf][crlf]"
            }
            PayloadPreset.FRONT_INJECT -> {
                "GET http://$host/ [protocol][crlf]Host: $host[crlf]Connection: Keep-Alive[crlf][split]CONNECT [host_port] [protocol][crlf][crlf]"
            }
            PayloadPreset.BACK_INJECT -> {
                "CONNECT [host_port] [protocol][crlf][split]GET http://$host/ [protocol][crlf]Host: $host[crlf]Connection: Keep-Alive[crlf][crlf]"
            }
            PayloadPreset.WEBSOCKET_CDN -> {
                "GET / HTTP/1.1[crlf]Host: $host[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]Sec-WebSocket-Key: [raw][crlf][crlf]"
            }
            PayloadPreset.SPLIT_DECOY -> {
                "CONNECT [host_port] [protocol][crlf]Host: $host[crlf][split]Connection: Keep-Alive[crlf][crlf]"
            }
            PayloadPreset.DELAY_SPLIT -> {
                "CONNECT [host_port] [protocol][crlf]Host: $host[delay_split]Connection: Keep-Alive[crlf][crlf]"
            }
        }
    }
}
