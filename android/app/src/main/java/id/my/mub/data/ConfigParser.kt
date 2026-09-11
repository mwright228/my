package id.my.mub.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ConfigParser {
    suspend fun fetchSubscription(subUrl: String): List<VpnProfile> = withContext(Dispatchers.IO) {
        val profiles = mutableListOf<VpnProfile>()
        val trimmedUrl = subUrl.trim()
        if (!trimmedUrl.startsWith("https://", ignoreCase = true)) return@withContext emptyList()
        try {
            val conn = (URL(trimmedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "MubX-Android/2.5")
            }
            try {
                if (conn.responseCode in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        tryDecodeBase64(reader.readText().trim()).lines().forEach { line ->
                            val value = line.trim()
                            if (value.isNotBlank() && !value.startsWith("#")) parseUri(value)?.let(profiles::add)
                        }
                    }
                }
            } finally { conn.disconnect() }
        } catch (_: Exception) { }
        profiles
    }

    fun parseUri(rawUri: String): VpnProfile? {
        val trimmed = rawUri.trim()
        return when {
            trimmed.startsWith("tbrutal://", true) -> parseTBrutal(trimmed)
            trimmed.startsWith("zivpn://", true) -> parseZivpn(trimmed)
            trimmed.startsWith("hysteria2://", true) || trimmed.startsWith("hy2://", true) -> parseHysteria2(trimmed)
            trimmed.startsWith("vless://", true) -> parseVless(trimmed)
            trimmed.startsWith("vmess://", true) -> parseVmess(trimmed)
            trimmed.startsWith("trojan://", true) -> parseTrojan(trimmed)
            trimmed.startsWith("ss://", true) -> parseShadowsocks(trimmed)
            trimmed.startsWith("tuic://", true) -> parseTuic(trimmed)
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> parseHttpChameleon(trimmed)
            else -> null
        }
    }

    private fun parseTBrutal(raw: String): VpnProfile? = try {
        val uri = Uri.parse(raw); val host = uri.host.orEmpty()
        VpnProfile(name = uri.fragment ?: "MUBX-T-Brutal", serverHost = host, serverIp = host,
            serverPort = if (uri.port > 0) uri.port else 443, bugHostSNI = uri.getQueryParameter("sni") ?: host,
            userUUID = uri.userInfo.orEmpty(), protocol = ProtocolType.T_BRUTAL,
            poolConcurrency = uri.getQueryParameter("conns")?.toIntOrNull()?.coerceIn(1, 32) ?: 4,
            brutalRateMbps = uri.getQueryParameter("rate")?.toIntOrNull()?.coerceAtLeast(0) ?: 80,
            wsPath = uri.getQueryParameter("path") ?: "/tbrutal", customPayload = uri.getQueryParameter("path") ?: "/tbrutal")
    } catch (_: Exception) { null }

    private fun parseZivpn(raw: String): VpnProfile? = try {
        val uri = Uri.parse(raw); val host = uri.host.orEmpty()
        VpnProfile(name = uri.fragment ?: "MUBX-ZivPN-UDP", serverHost = host, serverIp = host,
            serverPort = if (uri.port > 0) uri.port else 5667, userUUID = uri.userInfo.orEmpty(), protocol = ProtocolType.ZIVPN_UDP,
            poolConcurrency = 1, brutalRateMbps = 60, udpObfsPassword = uri.getQueryParameter("obfs") ?: "zivpn",
            udpPortHopRange = uri.getQueryParameter("mport") ?: "6000:19999")
    } catch (_: Exception) { null }

    private fun parseHysteria2(raw: String): VpnProfile? = try {
        val uri = Uri.parse(raw); val host = uri.host.orEmpty()
        VpnProfile(name = uri.fragment ?: "MUBX-Hysteria2", serverHost = host, serverIp = host,
            serverPort = if (uri.port > 0) uri.port else 4433, bugHostSNI = uri.getQueryParameter("sni") ?: host,
            userUUID = uri.userInfo.orEmpty(), protocol = ProtocolType.HYSTERIA_2, poolConcurrency = 4,
            brutalRateMbps = 100, udpPortHopRange = uri.getQueryParameter("mport") ?: "",
            allowInsecureTLS = uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("insecure").equals("true", true))
    } catch (_: Exception) { null }

    private fun parseVless(raw: String): VpnProfile? = try {
        val uri = Uri.parse(raw); val host = uri.host.orEmpty(); val type = uri.getQueryParameter("type") ?: "tcp"; val security = uri.getQueryParameter("security").orEmpty()
        val isWs = type.equals("ws", true); val isReality = security.equals("reality", true)
        val proto = when { isReality -> ProtocolType.VLESS_REALITY; isWs -> ProtocolType.VLESS_WS; else -> ProtocolType.VLESS_TCP }
        if (isReality && (uri.getQueryParameter("pbk").isNullOrBlank() || uri.getQueryParameter("sid").isNullOrBlank())) return null
        VpnProfile(name = uri.fragment ?: "MUBX-VLESS", serverHost = host, serverIp = host,
            serverPort = if (uri.port > 0) uri.port else 443, bugHostSNI = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host,
            userUUID = uri.userInfo.orEmpty(), protocol = proto, poolConcurrency = 2, brutalRateMbps = 0,
            wsPath = uri.getQueryParameter("path") ?: "/vless-ws", wsHost = uri.getQueryParameter("host") ?: "",
            vlessFlow = uri.getQueryParameter("flow") ?: "", allowInsecureTLS = uri.getQueryParameter("allowInsecure") == "1" || uri.getQueryParameter("allowInsecure").equals("true", true),
            realityPublicKey = uri.getQueryParameter("pbk") ?: uri.getQueryParameter("publicKey") ?: "",
            realityShortId = uri.getQueryParameter("sid") ?: uri.getQueryParameter("shortId") ?: "")
    } catch (_: Exception) { null }

    private fun parseVmess(raw: String): VpnProfile? = try {
        val encoded = raw.substringAfter("vmess://").trim(); val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP)))
        val host = json.optString("add", ""); val transport = json.optString("net", "ws")
        if (host.isBlank() || !transport.equals("ws", true)) return null
        VpnProfile(name = json.optString("ps", "MUBX-VMess"), serverHost = host, serverIp = host, serverPort = json.optInt("port", 443),
            bugHostSNI = json.optString("sni", json.optString("host", host)), userUUID = json.optString("id", ""), protocol = ProtocolType.VMESS_WS,
            poolConcurrency = 2, brutalRateMbps = 0, wsPath = json.optString("path", "/vless-ws").ifBlank { "/vless-ws" },
            wsHost = json.optString("host", ""), allowInsecureTLS = json.optBoolean("tlsAllowInsecure", json.optBoolean("insecure", false)))
    } catch (_: Exception) { null }

    private fun parseTrojan(raw: String): VpnProfile? = try {
        val uri = Uri.parse(raw); val host = uri.host.orEmpty(); val type = uri.getQueryParameter("type") ?: "ws"
        if (!type.equals("ws", true)) return null
        VpnProfile(name = uri.fragment ?: "MUBX-Trojan", serverHost = host, serverIp = host, serverPort = if (uri.port > 0) uri.port else 443,
            bugHostSNI = uri.getQueryParameter("sni") ?: host, userUUID = uri.userInfo.orEmpty(), protocol = ProtocolType.TROJAN_WS,
            poolConcurrency = 2, brutalRateMbps = 0, wsPath = uri.getQueryParameter("path") ?: "/vless-ws", wsHost = uri.getQueryParameter("host") ?: "",
            allowInsecureTLS = uri.getQueryParameter("allowInsecure") == "1" || uri.getQueryParameter("allowInsecure").equals("true", true))
    } catch (_: Exception) { null }

    private fun parseShadowsocks(raw: String): VpnProfile? = try {
        val uri = Uri.parse(raw); val host = uri.host.orEmpty(); val fragment = uri.fragment ?: "MUBX-Shadowsocks"; val plugin = Uri.decode(uri.getQueryParameter("plugin") ?: "")
        val isShadowTls = plugin.contains("shadow-tls", true); val is2022 = fragment.contains("2022", true) || fragment.contains("SS22", true)
        if (plugin.isNotBlank() && !isShadowTls) return null
        var sni = host; var path = ""; var pluginPassword = ""
        plugin.split(';').forEach { part -> val key = part.substringBefore('=').trim(); val value = part.substringAfter('=', '').trim(); when { key.equals("host", true) -> sni=value; key.equals("path", true) -> path=value; key.equals("password", true) -> pluginPassword=value } }
        val rawUserInfo = uri.userInfo.orEmpty(); var cipher = if (is2022 || isShadowTls) "2022-blake3-aes-256-gcm" else "aes-256-gcm"; var pass = rawUserInfo
        if (rawUserInfo.isNotBlank() && !rawUserInfo.contains(':')) runCatching { String(Base64.decode(rawUserInfo, Base64.DEFAULT or Base64.URL_SAFE)) }.getOrNull()?.let { if (it.contains(':')) { cipher=it.substringBefore(':'); pass=it.substringAfter(':') } else if (it.isNotBlank()) pass=it }
        else if (rawUserInfo.contains(':')) { cipher=rawUserInfo.substringBefore(':'); pass=rawUserInfo.substringAfter(':') }
        VpnProfile(name=fragment,serverHost=host,serverIp=host,serverPort=if(uri.port>0)uri.port else 443,bugHostSNI=sni,userUUID=pass,ssCipher=cipher,
            protocol=when { isShadowTls -> ProtocolType.SHADOWTLS_V3; is2022 -> ProtocolType.SHADOWSOCKS_2022; else -> ProtocolType.SHADOWSOCKS },poolConcurrency=2,customPayload=path,udpObfsPassword=pluginPassword)
    } catch (_: Exception) { null }

    private fun parseTuic(raw: String): VpnProfile? = try {
        val uri=Uri.parse(raw); val user=uri.userInfo.orEmpty(); val uuid=user.substringBefore(':'); val password=uri.getQueryParameter("password") ?: user.substringAfter(':','\u0000').takeIf { it != "\u0000" }.orEmpty()
        if (uuid.isBlank() || password.isBlank()) return null
        VpnProfile(name=uri.fragment ?: "MUBX-TUIC",serverHost=uri.host.orEmpty(),serverIp=uri.host.orEmpty(),serverPort=if(uri.port>0)uri.port else 8444,
            bugHostSNI=uri.getQueryParameter("sni") ?: uri.host.orEmpty(),userUUID=uuid,tuicPassword=password,protocol=ProtocolType.TUIC,poolConcurrency=4,brutalRateMbps=100,
            allowInsecureTLS=uri.getQueryParameter("allowInsecure") == "1" || uri.getQueryParameter("allowInsecure").equals("true", true))
    } catch (_: Exception) { null }

    private fun parseHttpChameleon(raw: String): VpnProfile? = try { val uri=Uri.parse(raw); VpnProfile(name=uri.fragment ?: "MUBX-Chameleon",serverHost=uri.host.orEmpty(),serverIp=uri.host.orEmpty(),serverPort=if(uri.port>0)uri.port else 8080,userUUID=uri.userInfo.orEmpty(),protocol=ProtocolType.SSH_PAYLOAD,poolConcurrency=2,brutalRateMbps=40) } catch (_: Exception) { null }

    private fun tryDecodeBase64(input:String):String=try{val clean=input.replace("\r","").replace("\n","").trim();val decoded=String(Base64.decode(clean,Base64.DEFAULT or Base64.URL_SAFE));if(decoded.contains("://"))decoded else input}catch(_:Exception){input}
}
