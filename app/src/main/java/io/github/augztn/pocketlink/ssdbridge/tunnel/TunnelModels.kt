package io.github.augztn.pocketlink.ssdbridge.tunnel

import android.util.Base64
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Extensible catalog of remote server tunnel providers adapted from mobile-server.
 * Currently only Cloudflare is enabled; future providers (e.g., Ngrok, Custom Relay)
 * can be registered here and paired with a [TunnelProviderAdapter] without refactoring
 * BridgeService, ViewModel, or Host Setup UI.
 */
enum class TunnelProviderId(
    val id: String,
    val displayName: String,
    val tagline: String,
    val available: Boolean,
) {
    CLOUDFLARE(
        id = "cloudflare",
        displayName = "Cloudflare Tunnel",
        tagline = "Expose port 8000 over Cloudflare HTTPS edge with zero port forwarding",
        available = true,
    ),
    ;

    companion object {
        fun fromId(raw: String?): TunnelProviderId =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: CLOUDFLARE
    }
}

object TunnelProviderRegistry {
    fun availableProviders(): List<TunnelProviderId> =
        TunnelProviderId.entries.filter { it.available }
}

enum class CloudflareTunnelMode(
    val id: String,
    val label: String,
    val shortLabel: String,
    val summary: String,
) {
    AUTO_EDGE(
        id = "auto_edge",
        label = "Instant Auto Tunnel",
        shortLabel = "Auto (1-Click)",
        summary = "Zero manual entry — automatically generates device tunnel credentials, registers an encrypted Cloudflare HTTPS session, and links to local port 8000 in one tap.",
    ),
    EDGE_BRIDGE(
        id = "edge_bridge",
        label = "Custom Worker Edge",
        shortLabel = "Edge Worker",
        summary = "Pure-Kotlin WSS reverse tunnel via your custom Cloudflare Worker — runs natively inside PocketLink with no external binary.",
    ),
    ZERO_TRUST(
        id = "zero_trust",
        label = "Cloudflare Zero Trust",
        shortLabel = "Zero Trust",
        summary = "Verify & monitor a Cloudflare Named Tunnel (eyJ… token) or Quick Tunnel (*.trycloudflare.com) pointing to 127.0.0.1:8000.",
    ),
    ;

    companion object {
        fun fromId(raw: String?): CloudflareTunnelMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: AUTO_EDGE
    }
}

enum class TunnelState(val label: String) {
    STOPPED("Stopped"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    ERROR("Error"),
}

data class CloudflareTokenInfo(
    val accountId: String,
    val tunnelId: String,
    val valid: Boolean,
) {
    val shortAccountId: String
        get() = if (accountId.length > 10) "${accountId.take(6)}…${accountId.takeLast(4)}" else accountId

    val shortTunnelId: String
        get() = if (tunnelId.length > 12) "${tunnelId.take(8)}…${tunnelId.takeLast(4)}" else tunnelId
}

data class CloudflareTunnelConfig(
    val provider: TunnelProviderId = TunnelProviderId.CLOUDFLARE,
    val mode: CloudflareTunnelMode = CloudflareTunnelMode.AUTO_EDGE,
    val autoPublicUrl: String = "",
    val autoSubdomain: String = "",
    val workerPublicUrl: String = "",
    val zeroTrustPublicUrl: String = "",
    val hasWorkerSecret: Boolean = false,
    val hasZeroTrustToken: Boolean = false,
    val tokenInfo: CloudflareTokenInfo? = null,
    val autoStartWithServer: Boolean = false,
) {
    val activePublicUrl: String
        get() = when (mode) {
            CloudflareTunnelMode.AUTO_EDGE -> autoPublicUrl.ifBlank { workerPublicUrl.ifBlank { zeroTrustPublicUrl } }
            CloudflareTunnelMode.EDGE_BRIDGE -> workerPublicUrl.ifBlank { autoPublicUrl }
            CloudflareTunnelMode.ZERO_TRUST -> zeroTrustPublicUrl.ifBlank { autoPublicUrl }
        }

    val isReadyToStart: Boolean
        get() = when (mode) {
            CloudflareTunnelMode.AUTO_EDGE -> true
            CloudflareTunnelMode.EDGE_BRIDGE ->
                (workerPublicUrl.isNotBlank() && hasWorkerSecret) || autoPublicUrl.isNotBlank()
            CloudflareTunnelMode.ZERO_TRUST ->
                zeroTrustPublicUrl.isNotBlank() || autoPublicUrl.isNotBlank()
        }
}

data class TunnelLogLine(
    val timestampMs: Long,
    val level: String,
    val message: String,
)

data class TunnelProbeResult(
    val reachable: Boolean,
    val publicUrl: String,
    val latencyMs: Long = 0L,
    val statusCode: Int = 0,
    val serviceName: String? = null,
    val cloudflareRay: String? = null,
    val cloudflareProxied: Boolean = false,
    val errorMessage: String? = null,
)

data class TunnelStatusSnapshot(
    val provider: TunnelProviderId = TunnelProviderId.CLOUDFLARE,
    val mode: CloudflareTunnelMode = CloudflareTunnelMode.EDGE_BRIDGE,
    val state: TunnelState = TunnelState.STOPPED,
    val publicUrl: String? = null,
    val localTargetUrl: String = "http://127.0.0.1:8000",
    val autoStartWithServer: Boolean = false,
    val cloudflareRay: String? = null,
    val cloudflareProxied: Boolean = false,
    val latencyMs: Long? = null,
    val requestsProxied: Long = 0L,
    val bytesProxied: Long = 0L,
    val tokenInfo: CloudflareTokenInfo? = null,
    val lastError: String? = null,
    val logs: List<TunnelLogLine> = emptyList(),
) {
    val isActive: Boolean get() = state == TunnelState.CONNECTED || state == TunnelState.CONNECTING
}

interface TunnelProviderAdapter {
    val providerId: TunnelProviderId
    val status: StateFlow<TunnelStatusSnapshot>
    fun start(localPort: Int = 8000): Result<Unit>
    fun stop()
    suspend fun verifyPublicEndpoint(): Result<TunnelProbeResult>
    fun clearLogs()
}

/**
 * Decodes a Cloudflare Zero Trust Named Tunnel token (`cloudflared tunnel run --token eyJ...`).
 * Cloudflare encodes tunnel credentials as standard/URL-safe Base64 JSON:
 * `{"a":"<account_tag>","t":"<tunnel_uuid>","s":"<tunnel_secret>"}`.
 * The secret (`s`) is validated for presence only and never stored in the returned info object.
 */
fun parseCloudflareTunnelToken(rawToken: String): CloudflareTokenInfo? {
    val cleaned = rawToken
        .trim()
        .removePrefix("cloudflared tunnel run --token")
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .replace(Regex("\\s+"), "")
    if (cleaned.length < 24) return null

    val decodedBytes = runCatching {
        Base64.decode(cleaned, Base64.DEFAULT)
    }.recoverCatching {
        Base64.decode(cleaned, Base64.URL_SAFE or Base64.NO_PADDING)
    }.getOrNull() ?: return null

    val json = runCatching { JSONObject(String(decodedBytes, Charsets.UTF_8)) }.getOrNull()
        ?: return null

    val accountId = json.optString("a").trim()
    val tunnelId = json.optString("t").trim()
    val secret = json.optString("s").trim()
    if (accountId.isBlank() || tunnelId.isBlank() || secret.isBlank()) {
        return null
    }
    return CloudflareTokenInfo(
        accountId = accountId,
        tunnelId = tunnelId,
        valid = true,
    )
}

/**
 * Normalizes a user-provided Cloudflare public URL (e.g. `my-relay.workers.dev`,
 * `https://abc.trycloudflare.com/`, or `https://pocketlink.example.com`) into a clean
 * HTTPS origin without trailing slash.
 */
fun normalizeCloudflarePublicUrl(raw: String): Result<String> = runCatching {
    val cleaned = raw
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .trim()
        .removeSuffix("/health")
        .trimEnd('/')
    require(cleaned.isNotBlank()) { "Enter your Cloudflare public HTTPS URL." }
    val withScheme = when {
        cleaned.startsWith("https://", ignoreCase = true) -> cleaned
        cleaned.startsWith("http://", ignoreCase = true) ->
            throw IllegalArgumentException("Cloudflare tunnels must use https:// (not plain http://).")
        else -> "https://$cleaned"
    }
    val uri = URI(withScheme)
    val host = uri.host?.trim()?.lowercase(Locale.US)
    require(!host.isNullOrBlank() && '.' in host) {
        "Enter a valid HTTPS hostname (for example https://pocketlink.yourname.workers.dev or https://xyz.trycloudflare.com)."
    }
    require(host != "localhost" && host != "127.0.0.1") {
        "Enter your public Cloudflare hostname, not localhost."
    }
    val portPart = if (uri.port in 1..65535 && uri.port != 443) ":${uri.port}" else ""
    "https://$host$portPart"
}

data class ParsedConnectLink(
    val host: String,
    val port: String,
    val username: String? = null,
    val serverName: String? = null,
    val isHttps: Boolean = false,
    val password: String? = null,
)

/**
 * Builds a shareable one-tap PocketLink connect link (`pocketlink://connect?url=...&user=...&name=...&pwd=...`)
 * so clients can paste it directly into the Connection screen and connect in 1 tap without typing host/port/password manually.
 */
fun buildPocketLinkConnectUri(
    publicUrl: String,
    username: String = "admin",
    serverName: String = "",
    password: String = "",
): String {
    val cleanUrl = publicUrl.trim().trimEnd('/')
    val encodedUrl = java.net.URLEncoder.encode(cleanUrl, "UTF-8")
    val encodedUser = java.net.URLEncoder.encode(username.trim().ifBlank { "admin" }, "UTF-8")
    val nameParam = serverName.trim().takeIf { it.isNotBlank() }?.let {
        "&name=${java.net.URLEncoder.encode(it, "UTF-8")}"
    }.orEmpty()
    val pwdParam = password.takeIf { it.isNotEmpty() }?.let {
        "&pwd=${java.net.URLEncoder.encode(it, "UTF-8")}"
    }.orEmpty()
    return "pocketlink://connect?url=$encodedUrl&user=$encodedUser$nameParam$pwdParam"
}

/**
 * Smart parser for clipboard/pasted server addresses:
 * - `pocketlink://connect?url=https%3A%2F%2F...&user=admin&name=Home&pwd=...`
 * - `https://my-host.workers.dev` or bare `my-host.workers.dev` / `abc.trycloudflare.com`
 * - `http://100.68.88.73:8000` or bare `100.68.88.73:8000` / `100.68.88.73`
 */
fun parsePocketLinkConnectInput(raw: String): ParsedConnectLink? {
    val trimmed = raw.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "").trim()
    if (trimmed.isBlank()) return null

    if (trimmed.startsWith("pocketlink://", ignoreCase = true)) {
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val params = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair to ""
                else {
                    val k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                    val v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    k to v
                }
            }
        val targetUrl = params["url"]?.trim().orEmpty()
        if (targetUrl.isBlank()) return null
        val parsed = parsePocketLinkConnectInput(targetUrl) ?: return null
        return parsed.copy(
            username = params["user"]?.trim()?.takeIf { it.isNotBlank() } ?: parsed.username,
            serverName = params["name"]?.trim()?.takeIf { it.isNotBlank() } ?: parsed.serverName,
            password = params["pwd"]?.takeIf { it.isNotEmpty() } ?: parsed.password,
        )
    }

    val lower = trimmed.lowercase(Locale.US)
    val looksCloudflareDomain = lower.contains(".workers.dev") ||
        lower.contains(".trycloudflare.com") ||
        lower.contains(".cfargotunnel.com") ||
        lower.contains(".pages.dev")

    if (lower.startsWith("https://") || (looksCloudflareDomain && !lower.startsWith("http://"))) {
        val normalized = normalizeCloudflarePublicUrl(trimmed).getOrNull() ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val portNum = if (uri.port in 1..65535) uri.port else 443
        return ParsedConnectLink(
            host = normalized,
            port = portNum.toString(),
            isHttps = true,
        )
    }

    val withScheme = if (lower.startsWith("http://")) trimmed else "http://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    val host = uri.host?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val portNum = if (uri.port in 1..65535) uri.port else 8000
    return ParsedConnectLink(
        host = host,
        port = portNum.toString(),
        isHttps = false,
    )
}

