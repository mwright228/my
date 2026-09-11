package id.my.mub.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ProfileStore {
    private const val PREFS_NAME = "quicknotes_relay_profiles"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEY_PROFILES_JSON = "profiles_json"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (getAllProfiles().isEmpty()) {
            // Seed default initial node if empty
            val defaultProfile = VpnProfile(
                id = UUID.randomUUID().toString(),
                name = "GR-Premium-1",
                serverHost = "gr.mub.my.id",
                serverPort = 443,
                protocol = ProtocolType.VLESS_WS,
                bugHostSNI = "gr.mub.my.id",
                userUUID = "12345678-1234-1234-1234-123456789abc",
                poolConcurrency = 2,
                brutalRateMbps = 50,
                dnsServer = "1.1.1.1"
            )
            saveProfile(defaultProfile)
            setActiveProfileId(defaultProfile.id)
        }
    }

    fun getActiveProfileId(): String {
        return prefs.getString(KEY_ACTIVE_ID, "") ?: ""
    }

    fun setActiveProfileId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun getActiveProfile(): VpnProfile {
        val all = getAllProfiles()
        val activeId = getActiveProfileId()
        return all.find { it.id == activeId } ?: all.firstOrNull() ?: VpnProfile()
    }

    fun getAllProfiles(): List<VpnProfile> {
        val jsonStr = prefs.getString(KEY_PROFILES_JSON, null) ?: return emptyList()
        val list = mutableListOf<VpnProfile>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(profileFromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveProfile(profile: VpnProfile) {
        val current = getAllProfiles().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        saveAll(current)
        if (getActiveProfileId().isEmpty()) {
            setActiveProfileId(profile.id)
        }
    }

    fun deleteProfile(id: String) {
        val current = getAllProfiles().toMutableList()
        current.removeAll { it.id == id }
        saveAll(current)
        if (getActiveProfileId() == id) {
            val next = current.firstOrNull()
            setActiveProfileId(next?.id ?: "")
        }
    }

    private fun saveAll(profiles: List<VpnProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(profileToJson(p))
        }
        prefs.edit().putString(KEY_PROFILES_JSON, arr.toString()).apply()
    }

    private fun profileToJson(p: VpnProfile): JSONObject {
        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("serverHost", p.serverHost)
            put("serverIp", p.serverIp)
            put("serverPort", p.serverPort)
            put("bugHostSNI", p.bugHostSNI)
            put("userUUID", p.userUUID)
            put("protocol", p.protocol.name)
            put("poolConcurrency", p.poolConcurrency)
            put("brutalRateMbps", p.brutalRateMbps)
            put("allowInsecureTLS", p.allowInsecureTLS)
            put("customPayload", p.customPayload)
            put("udpObfsPassword", p.udpObfsPassword)
            put("udpPortHopRange", p.udpPortHopRange)
            put("dnsServer", p.dnsServer)
            put("dnsSecondary", p.dnsSecondary)
            put("sshUser", p.sshUser)
            put("sshPassword", p.sshPassword)
            put("proxyHost", p.proxyHost)
            put("proxyPort", p.proxyPort)
            put("wsPath", p.wsPath)
            put("wsHost", p.wsHost)
            put("ssCipher", p.ssCipher)
            put("vlessFlow", p.vlessFlow)
        }
    }

    private fun profileFromJson(obj: JSONObject): VpnProfile {
        val protoName = obj.optString("protocol", ProtocolType.VLESS_WS.name)
        val proto = try {
            ProtocolType.valueOf(protoName)
        } catch (e: Exception) {
            ProtocolType.VLESS_WS
        }

        return VpnProfile(
            id = obj.optString("id", UUID.randomUUID().toString()),
            name = obj.optString("name", "Unnamed Node"),
            serverHost = obj.optString("serverHost", ""),
            serverIp = obj.optString("serverIp", ""),
            serverPort = obj.optInt("serverPort", 443),
            bugHostSNI = obj.optString("bugHostSNI", ""),
            userUUID = obj.optString("userUUID", ""),
            protocol = proto,
            poolConcurrency = obj.optInt("poolConcurrency", 2),
            brutalRateMbps = obj.optInt("brutalRateMbps", 50),
            allowInsecureTLS = obj.optBoolean("allowInsecureTLS", false),
            customPayload = obj.optString("customPayload", ""),
            udpObfsPassword = obj.optString("udpObfsPassword", ""),
            udpPortHopRange = obj.optString("udpPortHopRange", ""),
            dnsServer = obj.optString("dnsServer", "1.1.1.1"),
            dnsSecondary = obj.optString("dnsSecondary", "8.8.8.8"),
            sshUser = obj.optString("sshUser", ""),
            sshPassword = obj.optString("sshPassword", ""),
            proxyHost = obj.optString("proxyHost", ""),
            proxyPort = obj.optInt("proxyPort", 0),
            wsPath = obj.optString("wsPath", "/vless-ws"),
            wsHost = obj.optString("wsHost", ""),
            ssCipher = obj.optString("ssCipher", "2022-blake3-aes-128-gcm"),
            vlessFlow = obj.optString("vlessFlow", "")
        )
    }
}
