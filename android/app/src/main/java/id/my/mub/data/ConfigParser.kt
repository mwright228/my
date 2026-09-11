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

    /**
     * Fetches subscription links from a URL (e.g. https://domain/sub/<token>/links.txt)
     * Supports both plain newline-separated text and base64-encoded subscription formats.
     */
    suspend fun fetchSubscription(subUrl: String): List<VpnProfile> = withContext(Dispatchers.IO) {
        val profiles = mutableListOf<VpnProfile>()
        val trimmedUrl = subUrl.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext emptyList()
        }
        try {
            val url = URL(trimmedUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "MubX-Android/2.5")

            if (conn.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val rawContent = reader.readText().trim()
                reader.close()

                // Check if content is base64 encoded
                val decoded = tryDecodeBase64(rawContent)
                val lines = decoded.lines()

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                        parseUri(trimmed)?.let { profiles.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        profiles
    }

    /**
     * Parses a single URI string into a strongly-typed VpnProfile.
     * Fully compatible with all MUB-X server link outputs.
     */
    fun parseUri(rawUri: String): VpnProfile? {
        val trimmed = rawUri.trim()
        return when {
            trimmed.startsWith("tbrutal://", ignoreCase = true) -> parseTBrutal(trimmed)
            trimmed.startsWith("zivpn://", ignoreCase = true) -> parseZivpn(trimmed)
            trimmed.startsWith("hysteria2://", ignoreCase = true) || trimmed.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(trimmed)
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
            trimmed.startsWith("tuic://", ignoreCase = true) -> parseTuic(trimmed)
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> parseHttpChameleon(trimmed)
            else -> null
        }
    }

    private fun parseTBrutal(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val token = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 443
            val sni = uri.getQueryParameter("sni") ?: host
            val rate = uri.getQueryParameter("rate")?.toIntOrNull() ?: 80
            val conns = uri.getQueryParameter("conns")?.toIntOrNull() ?: 4
            val fragment = uri.fragment ?: "MUBX-T-Brutal"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = sni,
                userUUID = token,
                protocol = ProtocolType.T_BRUTAL,
                poolConcurrency = conns,
                brutalRateMbps = rate
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseZivpn(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val pass = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 5667
            val obfs = uri.getQueryParameter("obfs") ?: "zivpn"
            val mport = uri.getQueryParameter("mport") ?: "6000:19999"
            val fragment = uri.fragment ?: "MUBX-ZivPN-UDP"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = "",
                userUUID = pass,
                protocol = ProtocolType.ZIVPN_UDP,
                poolConcurrency = 1,
                brutalRateMbps = 60,
                udpObfsPassword = obfs,
                udpPortHopRange = mport
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHysteria2(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val pass = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 4433
            val sni = uri.getQueryParameter("sni") ?: host
            val mport = uri.getQueryParameter("mport") ?: ""
            val fragment = uri.fragment ?: "MUBX-Hysteria2"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = sni,
                userUUID = pass,
                protocol = ProtocolType.HYSTERIA_2,
                poolConcurrency = 4,
                brutalRateMbps = 100,
                udpPortHopRange = mport
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVless(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val uuid = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 443
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: host
            val type = uri.getQueryParameter("type") ?: "tcp"
            val fragment = uri.fragment ?: "MUBX-VLESS"

            val proto = if (type.contains("ws", ignoreCase = true)) {
                ProtocolType.VLESS_WS
            } else {
                ProtocolType.VLESS_TCP
            }

            val path = uri.getQueryParameter("path") ?: "/vless-ws"
            val headerHost = uri.getQueryParameter("host") ?: ""
            val flow = uri.getQueryParameter("flow") ?: "none"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = sni,
                userUUID = uuid,
                protocol = proto,
                poolConcurrency = 2,
                brutalRateMbps = 0,
                wsPath = path,
                wsHost = headerHost,
                vlessFlow = flow
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(raw: String): VpnProfile? {
        return try {
            val b64 = raw.substringAfter("vmess://").trim()
            val decodedJson = String(Base64.decode(b64, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP))
            val json = JSONObject(decodedJson)
            val add = json.optString("add", "")
            val port = json.optInt("port", 443)
            val id = json.optString("id", "")
            val sni = json.optString("sni", json.optString("host", add))
            val ps = json.optString("ps", "MUBX-VMess")

            VpnProfile(
                name = ps,
                serverHost = add,
                serverIp = add,
                serverPort = port,
                bugHostSNI = sni,
                userUUID = id,
                protocol = ProtocolType.VLESS_WS,
                poolConcurrency = 2,
                brutalRateMbps = 0
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTrojan(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val pass = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 443
            val sni = uri.getQueryParameter("sni") ?: host
            val fragment = uri.fragment ?: "MUBX-Trojan"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = sni,
                userUUID = pass,
                protocol = ProtocolType.VLESS_TCP,
                poolConcurrency = 2,
                brutalRateMbps = 0
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseShadowsocks(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 443
            val plugin = uri.getQueryParameter("plugin") ?: ""
            val fragment = uri.fragment ?: "MUBX-Shadowsocks"

            val isShadowTls = plugin.contains("shadow-tls", ignoreCase = true)
            val is2022 = fragment.contains("2022", ignoreCase = true) || fragment.contains("SS22", ignoreCase = true)

            // Extract plugin options (e.g. host=xxx;path=yyy;password=zzz)
            var extractedSni = host
            var extractedPath = ""
            var extractedPass = ""
            if (plugin.isNotBlank()) {
                val decodedPlugin = Uri.decode(plugin)
                for (part in decodedPlugin.split(";")) {
                    val key = part.substringBefore("=").trim()
                    val value = part.substringAfter("=").trim()
                    when {
                        key.equals("host", ignoreCase = true) -> extractedSni = value
                        key.equals("path", ignoreCase = true) -> extractedPath = value
                        key.equals("password", ignoreCase = true) -> extractedPass = value
                    }
                }
            }

            val proto = when {
                isShadowTls -> ProtocolType.SHADOWTLS_V3
                is2022 -> ProtocolType.SHADOWSOCKS_2022
                else -> ProtocolType.SHADOWSOCKS_2022
            }

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = extractedSni,
                userUUID = uri.userInfo ?: "",
                protocol = proto,
                poolConcurrency = 2,
                brutalRateMbps = 0,
                customPayload = extractedPath,
                udpObfsPassword = if (extractedPass.isNotBlank()) extractedPass else "zivpn"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTuic(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val userInfo = uri.userInfo ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 8444
            val sni = uri.getQueryParameter("sni") ?: host
            val fragment = uri.fragment ?: "MUBX-TUIC"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = sni,
                userUUID = userInfo.substringBefore(":"),
                protocol = ProtocolType.HYSTERIA_2,
                poolConcurrency = 4,
                brutalRateMbps = 100
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHttpChameleon(raw: String): VpnProfile? {
        return try {
            val uri = Uri.parse(raw)
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 8080
            val fragment = uri.fragment ?: "MUBX-Chameleon"

            VpnProfile(
                name = fragment,
                serverHost = host,
                serverIp = host,
                serverPort = port,
                bugHostSNI = "",
                userUUID = uri.userInfo ?: "",
                protocol = ProtocolType.SSH_PAYLOAD,
                poolConcurrency = 2,
                brutalRateMbps = 40
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun tryDecodeBase64(input: String): String {
        return try {
            val clean = input.replace("\r", "").replace("\n", "").trim()
            val bytes = Base64.decode(clean, Base64.DEFAULT or Base64.URL_SAFE)
            val str = String(bytes)
            if (str.contains("://")) str else input
        } catch (e: Exception) {
            input
        }
    }
}
