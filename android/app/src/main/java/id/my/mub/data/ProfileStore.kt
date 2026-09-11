package id.my.mub.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

object ProfileStore {
    private const val PREFS_NAME = "quicknotes_relay_profiles"
    private const val KEY_ACTIVE_ID = "active_profile_id"
    private const val KEY_PROFILES_JSON = "profiles_json"
    private const val KEY_PROFILES_ENCRYPTED = "profiles_json_enc"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "mubx_profile_store_v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(ensureKey() != null) { "Unable to initialize Android Keystore profile encryption" }
        migratePlaintextProfiles()
    }

    fun getActiveProfileId(): String = prefs.getString(KEY_ACTIVE_ID, "") ?: ""

    fun setActiveProfileId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun getActiveProfile(): VpnProfile {
        val all = getAllProfiles()
        val activeId = getActiveProfileId()
        return all.find { it.id == activeId } ?: all.firstOrNull() ?: VpnProfile()
    }

    fun getAllProfiles(): List<VpnProfile> {
        val jsonStr = readEncryptedProfiles() ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            buildList { for (i in 0 until arr.length()) add(profileFromJson(arr.getJSONObject(i))) }
        } catch (e: Exception) {
            throw IllegalStateException("Stored VPN profiles could not be decrypted or parsed", e)
        }
    }

    fun saveProfile(profile: VpnProfile) {
        val current = getAllProfiles().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        saveAll(current)
        if (getActiveProfileId().isEmpty()) setActiveProfileId(profile.id)
    }

    fun deleteProfile(id: String) {
        val current = getAllProfiles().toMutableList()
        current.removeAll { it.id == id }
        saveAll(current)
        if (getActiveProfileId() == id) setActiveProfileId(current.firstOrNull()?.id ?: "")
    }

    private fun saveAll(profiles: List<VpnProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(profileToJson(it)) }
        val encrypted = requireNotNull(encrypt(arr.toString())) { "Unable to encrypt VPN profiles with Android Keystore" }
        check(prefs.edit().putString(KEY_PROFILES_ENCRYPTED, encrypted).remove(KEY_PROFILES_JSON).commit()) {
            "Unable to persist encrypted VPN profiles"
        }
    }

    private fun migratePlaintextProfiles() {
        val encrypted = prefs.getString(KEY_PROFILES_ENCRYPTED, null)
        val plaintext = prefs.getString(KEY_PROFILES_JSON, null)
        if (encrypted.isNullOrEmpty() && !plaintext.isNullOrEmpty()) {
            val sealed = requireNotNull(encrypt(plaintext)) { "Unable to encrypt existing VPN profiles during migration" }
            check(prefs.edit().putString(KEY_PROFILES_ENCRYPTED, sealed).remove(KEY_PROFILES_JSON).commit()) {
                "Unable to complete encrypted VPN profile migration"
            }
        } else if (!encrypted.isNullOrEmpty() && plaintext != null) {
            check(prefs.edit().remove(KEY_PROFILES_JSON).commit()) { "Unable to remove legacy plaintext VPN profiles" }
        }
    }

    private fun readEncryptedProfiles(): String? {
        val sealed = prefs.getString(KEY_PROFILES_ENCRYPTED, null)
        return if (sealed.isNullOrEmpty()) null else decrypt(sealed)
    }

    private fun ensureKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    private fun getKey(): SecretKey? = ensureKey()

    private fun encrypt(plainText: String): String? {
        val key = getKey() ?: return null
        return try {
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun decrypt(sealed: String): String? {
        val key = getKey() ?: return null
        return try {
            val parts = sealed.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != IV_BYTES || ciphertext.isEmpty()) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun profileToJson(p: VpnProfile): JSONObject = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("serverHost", p.serverHost); put("serverIp", p.serverIp)
        put("serverPort", p.serverPort); put("bugHostSNI", p.bugHostSNI); put("userUUID", p.userUUID)
        put("protocol", p.protocol.name); put("poolConcurrency", p.poolConcurrency); put("brutalRateMbps", p.brutalRateMbps)
        put("allowInsecureTLS", p.allowInsecureTLS); put("customPayload", p.customPayload); put("udpObfsPassword", p.udpObfsPassword)
        put("udpPortHopRange", p.udpPortHopRange); put("dnsServer", p.dnsServer); put("dnsSecondary", p.dnsSecondary)
        put("udpForwarding", p.udpForwarding); put("sshUser", p.sshUser); put("sshPassword", p.sshPassword)
        put("proxyHost", p.proxyHost); put("proxyPort", p.proxyPort); put("wsPath", p.wsPath); put("wsHost", p.wsHost)
        put("ssCipher", p.ssCipher); put("vlessFlow", p.vlessFlow); put("realityPublicKey", p.realityPublicKey)
        put("realityShortId", p.realityShortId); put("killSwitchEnabled", p.killSwitchEnabled)
    }

    private fun profileFromJson(obj: JSONObject): VpnProfile {
        val proto = try { ProtocolType.valueOf(obj.optString("protocol", ProtocolType.VLESS_WS.name)) } catch (_: Exception) { ProtocolType.VLESS_WS }
        return VpnProfile(
            id = obj.optString("id", UUID.randomUUID().toString()), name = obj.optString("name", "Unnamed Node"),
            serverHost = obj.optString("serverHost", ""), serverIp = obj.optString("serverIp", ""), serverPort = obj.optInt("serverPort", 443),
            bugHostSNI = obj.optString("bugHostSNI", ""), userUUID = obj.optString("userUUID", ""), protocol = proto,
            poolConcurrency = obj.optInt("poolConcurrency", 2), brutalRateMbps = obj.optInt("brutalRateMbps", 50),
            allowInsecureTLS = obj.optBoolean("allowInsecureTLS", false), customPayload = obj.optString("customPayload", ""),
            udpObfsPassword = obj.optString("udpObfsPassword", ""), udpPortHopRange = obj.optString("udpPortHopRange", ""),
            dnsServer = obj.optString("dnsServer", "1.1.1.1"), dnsSecondary = obj.optString("dnsSecondary", "8.8.8.8"),
            udpForwarding = obj.optBoolean("udpForwarding", true), sshUser = obj.optString("sshUser", ""), sshPassword = obj.optString("sshPassword", ""),
            proxyHost = obj.optString("proxyHost", ""), proxyPort = obj.optInt("proxyPort", 0), wsPath = obj.optString("wsPath", "/vless-ws"),
            wsHost = obj.optString("wsHost", ""), ssCipher = obj.optString("ssCipher", "2022-blake3-aes-128-gcm"),
            vlessFlow = obj.optString("vlessFlow", ""), realityPublicKey = obj.optString("realityPublicKey", ""),
            realityShortId = obj.optString("realityShortId", ""), killSwitchEnabled = obj.optBoolean("killSwitchEnabled", false)
        )
    }
}
