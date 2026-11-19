package com.example.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.data.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadProfiles(): List<ServerProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<ServerProfile>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val host = obj.optString("host")
            val port = obj.optInt("port", 8000)
            val username = obj.optString("username", "admin")
            val name = obj.optString("name", "")
            if (id.isBlank() || host.isBlank()) continue
            val password = readEncryptedPassword(id)
            results.add(
                ServerProfile(
                    id = id,
                    name = name,
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                )
            )
        }
        return results
    }

    fun saveProfile(profile: ServerProfile) {
        val current = loadProfiles().filterNot { it.id == profile.id }.toMutableList()
        current.add(0, profile)
        persistProfileList(current)
        if (profile.password.isNotEmpty()) {
            writeEncryptedPassword(profile.id, profile.password)
        }
    }

    fun deleteProfile(id: String) {
        val remaining = loadProfiles().filterNot { it.id == id }
        persistProfileList(remaining)
        prefs.edit().remove("pwd_$id").apply()
    }

    fun createProfileId(host: String, port: Int, username: String): String {
        val raw = "${host.trim()}:$port|${username.trim()}"
        return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun persistProfileList(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("username", p.username)
            )
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    private fun writeEncryptedPassword(id: String, password: String) {
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.edit().putString("pwd_$id", payload).apply()
        }
    }

    private fun readEncryptedPassword(id: String): String {
        val payload = prefs.getString("pwd_$id", null) ?: return ""
        val parts = payload.split(':')
        if (parts.size != 2) return ""
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "pocketlink_secure_profiles"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_ALIAS = "pocketlink_profile_aes_key"
    }
}
