package io.github.augztn.pocketlink.ssdbridge.tunnel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Coordinates extensible tunnel providers (Cloudflare only for now) with encrypted
 * AndroidKeyStore secret persistence and live state flows observed by BridgeService & UI.
 */
class TunnelManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<CloudflareTunnelConfig> = _config.asStateFlow()

    @Volatile
    private var stateListener: ((TunnelStatusSnapshot) -> Unit)? = null

    private val cloudflareAdapter = CloudflareTunnelAdapter(
        configProvider = { _config.value },
        workerSecretProvider = { readEncryptedSecret(KEY_WORKER_SECRET) },
        onStateChanged = { snapshot -> stateListener?.invoke(snapshot) },
    )

    private val adapters: Map<TunnelProviderId, TunnelProviderAdapter> = mapOf(
        TunnelProviderId.CLOUDFLARE to cloudflareAdapter,
    )

    val status: StateFlow<TunnelStatusSnapshot> = cloudflareAdapter.status

    init {
        cloudflareAdapter.syncConfigPreview(_config.value)
    }

    fun setStateListener(listener: ((TunnelStatusSnapshot) -> Unit)?) {
        stateListener = listener
    }

    fun availableProviders(): List<TunnelProviderId> = TunnelProviderRegistry.availableProviders()

    fun activeAdapter(): TunnelProviderAdapter =
        adapters[_config.value.provider] ?: cloudflareAdapter

    fun isAutoStartEnabled(): Boolean = _config.value.autoStartWithServer && _config.value.isReadyToStart

    fun startActiveTunnel(localPort: Int = 8000): Result<Unit> =
        activeAdapter().start(localPort)

    fun stopActiveTunnel() {
        activeAdapter().stop()
    }

    suspend fun verifyPublicEndpoint(): Result<TunnelProbeResult> =
        activeAdapter().verifyPublicEndpoint()

    fun clearLogs() {
        activeAdapter().clearLogs()
    }

    fun saveCloudflareConfig(
        mode: CloudflareTunnelMode,
        workerPublicUrl: String? = null,
        workerSecret: String? = null,
        zeroTrustPublicUrl: String? = null,
        zeroTrustToken: String? = null,
        autoStartWithServer: Boolean? = null,
    ): Result<CloudflareTunnelConfig> = runCatching {
        val current = _config.value
        val nextWorkerUrl = workerPublicUrl?.let { raw ->
            if (raw.isBlank()) "" else normalizeCloudflarePublicUrl(raw).getOrThrow()
        } ?: current.workerPublicUrl

        val nextZtUrl = zeroTrustPublicUrl?.let { raw ->
            if (raw.isBlank()) "" else normalizeCloudflarePublicUrl(raw).getOrThrow()
        } ?: current.zeroTrustPublicUrl

        if (workerSecret != null && workerSecret.isNotBlank()) {
            require(workerSecret.trim().length >= 8) {
                "Tunnel secret must be at least 8 characters."
            }
            writeEncryptedSecret(KEY_WORKER_SECRET, workerSecret.trim())
        }

        if (zeroTrustToken != null && zeroTrustToken.isNotBlank()) {
            val parsed = parseCloudflareTunnelToken(zeroTrustToken)
                ?: throw IllegalArgumentException(
                    "Invalid Cloudflare Tunnel token. Paste the full Base64 token (starts with eyJ…) from Cloudflare Zero Trust."
                )
            writeEncryptedSecret(KEY_ZT_TOKEN, zeroTrustToken.trim())
            prefs.edit()
                .putString(KEY_ZT_ACCOUNT_ID, parsed.accountId)
                .putString(KEY_ZT_TUNNEL_ID, parsed.tunnelId)
                .apply()
        }

        val nextAutoStart = autoStartWithServer ?: current.autoStartWithServer
        prefs.edit()
            .putString(KEY_PROVIDER, TunnelProviderId.CLOUDFLARE.id)
            .putString(KEY_CF_MODE, mode.id)
            .putString(KEY_CF_WORKER_URL, nextWorkerUrl)
            .putString(KEY_CF_ZT_URL, nextZtUrl)
            .putBoolean(KEY_AUTO_START, nextAutoStart)
            .apply()

        val updated = loadConfig()
        _config.value = updated
        cloudflareAdapter.syncConfigPreview(updated)
        updated
    }

    fun clearSavedSecret(mode: CloudflareTunnelMode) {
        when (mode) {
            CloudflareTunnelMode.EDGE_BRIDGE -> prefs.edit().remove(KEY_WORKER_SECRET).apply()
            CloudflareTunnelMode.ZERO_TRUST -> prefs.edit()
                .remove(KEY_ZT_TOKEN)
                .remove(KEY_ZT_ACCOUNT_ID)
                .remove(KEY_ZT_TUNNEL_ID)
                .apply()
        }
        val updated = loadConfig()
        _config.value = updated
        cloudflareAdapter.syncConfigPreview(updated)
    }

    fun generateWorkerSecret(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val secret = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        writeEncryptedSecret(KEY_WORKER_SECRET, secret)
        val updated = loadConfig()
        _config.value = updated
        cloudflareAdapter.syncConfigPreview(updated)
        return secret
    }

    fun readWorkerSecret(): String = readEncryptedSecret(KEY_WORKER_SECRET)

    private fun loadConfig(): CloudflareTunnelConfig {
        val provider = TunnelProviderId.fromId(prefs.getString(KEY_PROVIDER, null))
        val mode = CloudflareTunnelMode.fromId(prefs.getString(KEY_CF_MODE, null))
        val workerUrl = prefs.getString(KEY_CF_WORKER_URL, null).orEmpty()
        val ztUrl = prefs.getString(KEY_CF_ZT_URL, null).orEmpty()
        val autoStart = prefs.getBoolean(KEY_AUTO_START, false)
        val hasWorkerSecret = !prefs.getString(KEY_WORKER_SECRET, null).isNullOrBlank()
        val hasZtToken = !prefs.getString(KEY_ZT_TOKEN, null).isNullOrBlank()
        val acct = prefs.getString(KEY_ZT_ACCOUNT_ID, null).orEmpty()
        val tid = prefs.getString(KEY_ZT_TUNNEL_ID, null).orEmpty()
        val tokenInfo = if (hasZtToken && acct.isNotBlank() && tid.isNotBlank()) {
            CloudflareTokenInfo(accountId = acct, tunnelId = tid, valid = true)
        } else {
            null
        }
        return CloudflareTunnelConfig(
            provider = provider,
            mode = mode,
            workerPublicUrl = workerUrl,
            zeroTrustPublicUrl = ztUrl,
            hasWorkerSecret = hasWorkerSecret,
            hasZeroTrustToken = hasZtToken,
            tokenInfo = tokenInfo,
            autoStartWithServer = autoStart,
        )
    }

    private fun writeEncryptedSecret(prefKey: String, value: String) {
        val encrypted = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            "gcm:" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }.getOrElse {
            // Fallback only for JVM Robolectric test environments lacking AndroidKeyStore provider
            "b64:" + Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        prefs.edit().putString(prefKey, encrypted).apply()
    }

    private fun readEncryptedSecret(prefKey: String): String {
        val payload = prefs.getString(prefKey, null) ?: return ""
        if (payload.startsWith("b64:")) {
            return runCatching {
                String(Base64.decode(payload.removePrefix("b64:"), Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")
        }
        val clean = payload.removePrefix("gcm:")
        val parts = clean.split(':')
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
        private const val PREFS_NAME = "pocketlink_tunnel_prefs"
        private const val KEY_ALIAS = "pocketlink_tunnel_aes_key"
        private const val KEY_PROVIDER = "tunnel_provider"
        private const val KEY_CF_MODE = "cf_mode"
        private const val KEY_CF_WORKER_URL = "cf_worker_url"
        private const val KEY_CF_ZT_URL = "cf_zt_url"
        private const val KEY_WORKER_SECRET = "cf_worker_secret_enc"
        private const val KEY_ZT_TOKEN = "cf_zt_token_enc"
        private const val KEY_ZT_ACCOUNT_ID = "cf_zt_account_id"
        private const val KEY_ZT_TUNNEL_ID = "cf_zt_tunnel_id"
        private const val KEY_AUTO_START = "tunnel_auto_start"

        @Volatile
        private var instance: TunnelManager? = null

        fun getInstance(context: Context): TunnelManager =
            instance ?: synchronized(this) {
                instance ?: TunnelManager(context).also { instance = it }
            }
    }
}
