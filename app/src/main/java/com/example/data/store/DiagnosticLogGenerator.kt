package com.example.data.store

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.model.AppSettingsState
import com.example.data.model.YtAudioQuality
import com.example.data.network.StreamResolutionTrace
import com.example.media.AudioPlaybackState
import io.github.augztn.pocketlink.ssdbridge.LocalHostStatus
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates structured, human-readable diagnostic log reports covering device/network info,
 * Discover Opus extraction traces, live ExoPlayer decoder format, Local Lossless status,
 * PocketLink Host/Cloudflare Tunnel status, and timestamped application events.
 */
object DiagnosticLogGenerator {

    fun generateReport(
        context: Context,
        settings: AppSettingsState,
        effectiveQuality: YtAudioQuality,
        playbackState: AudioPlaybackState,
        lastTrace: StreamResolutionTrace?,
        localLosslessCount: Int,
        localLosslessTaggedCount: Int = 0,
        localLosslessWithLyricsCount: Int = 0,
        activeLyricsSource: String? = null,
        hostStatus: LocalHostStatus,
        tunnelStatus: TunnelStatusSnapshot,
        probeOutput: String? = null,
    ): String {
        val now = Date()
        val isoFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val networkSummary = inspectNetworkSummary(context)

        return buildString {
            appendLine("============================================================")
            appendLine("POCKETLINK DIAGNOSTIC LOG REPORT")
            appendLine("Generated : ${isoFmt.format(now)}")
            appendLine("App       : ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device    : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Network   : $networkSummary")
            appendLine("============================================================")
            appendLine()

            appendLine("[1] DISCOVER AUDIO ENGINE & QUALITY")
            appendLine("  Discover Enabled      : ${settings.ytStreamingEnabled}")
            appendLine("  Requested Quality     : ${settings.ytAudioQuality.label} (${settings.ytAudioQuality.prefKey})")
            appendLine("  Effective Quality     : ${effectiveQuality.label} (${effectiveQuality.prefKey})")
            appendLine("  Extractor Profile     : ${settings.ytResolverProfile}")
            appendLine("  Stream Transport      : ChunkedYoutubeDataSource (512 KB bounded Range chunks)")
            appendLine("  Region / Country      : ${settings.ytCountryCode}")
            appendLine("  Local Lossless Toggle : ${settings.ytLocalLosslessEnabled} ($localLosslessCount lossless files indexed, $localLosslessTaggedCount with embedded tags, $localLosslessWithLyricsCount with embedded lyrics)")
            appendLine("  Skip Silence          : ${settings.ytSkipSilence}")
            appendLine()

            appendLine("[2] ACTIVE PLAYBACK & EXOPLAYER DECODER")
            val track = playbackState.currentTrack
            if (track == null) {
                appendLine("  Track                 : (none)")
            } else {
                appendLine("  Track                 : ${track.displayTitle} — ${track.artist.orEmpty()}")
                if (!track.album.isNullOrBlank()) {
                    appendLine("  Album                 : ${track.album}")
                }
                appendLine("  Track ID / Path       : ${track.path}")
                appendLine("  Source Type           : ${when {
                    track.isLocalLosslessMatch -> "Discover Local Lossless Match (${track.localLosslessBadge.orEmpty()})"
                    track.isYouTubeStream -> "Discover Stream (NewPipe/InnerTube)"
                    else -> "PocketLink Host File"
                }}")
                appendLine("  Route Label           : ${playbackState.routeLabel}")
                appendLine("  Stream Badge          : ${playbackState.streamBadge.ifBlank { "(pending)" }}")
                appendLine("  ExoPlayer Format      : ${playbackState.actualAudioFormat.ifBlank { "(not yet decoded)" }}")
                appendLine("  Lyrics Detection      : ${activeLyricsSource ?: "(none loaded)"}")
                appendLine("  State                 : playing=${playbackState.isPlaying}, buffering=${playbackState.isBuffering}, pos=${playbackState.positionMs}ms / dur=${playbackState.durationMs}ms")
                if (!playbackState.errorMessage.isNullOrBlank()) {
                    appendLine("  Playback Error        : ${playbackState.errorMessage}")
                }
            }
            appendLine()

            appendLine("[3] LAST DISCOVER STREAM RESOLUTION TRACE")
            if (lastTrace == null) {
                appendLine("  (No Discover stream has been resolved yet in this session.)")
            } else {
                appendLine("  Timestamp             : ${isoFmt.format(Date(lastTrace.timestampMs))}")
                appendLine("  Video ID              : ${lastTrace.videoId}")
                appendLine("  Quality Resolution    : requested=${lastTrace.requestedQuality.shortLabel} -> effective=${lastTrace.effectiveQuality.shortLabel} (unmetered=${lastTrace.isUnmeteredNetwork})")
                appendLine("  Resolver Profile      : ${lastTrace.resolverProfile}")
                val win = lastTrace.selected
                if (win != null) {
                    appendLine("  SELECTED STREAM       : ${win.badgeLabel} | itag=${win.itag ?: "?"} | opus=${win.isOpus} | mime=${win.mimeType} | client=${win.clientName}")
                } else {
                    appendLine("  SELECTED STREAM       : NONE (resolution failed)")
                }
                if (lastTrace.probes.isNotEmpty()) {
                    appendLine("  Candidate Probes (${lastTrace.probes.size}):")
                    lastTrace.probes.forEachIndexed { idx, p ->
                        val mark = if (p.accepted) "PASS" else "FAIL"
                        appendLine(
                            "    #${idx + 1} [$mark] src=${p.source} itag=${p.itag ?: "?"} codec=${p.codec} ${p.bitrateKbps}k mime=${p.mimeType} -> HTTP ${p.httpStatus} (${p.responseContentType.ifBlank { p.note }})"
                        )
                    }
                }
                if (lastTrace.errors.isNotEmpty()) {
                    appendLine("  Extractor Notes:")
                    lastTrace.errors.forEach { appendLine("    - $it") }
                }
            }
            appendLine()

            if (!probeOutput.isNullOrBlank()) {
                appendLine("[4] LIVE MULTI-QUALITY PROBE RESULTS (HIGH / MEDIUM / LOW)")
                probeOutput.lines().forEach { appendLine("  $it") }
                appendLine()
            }

            appendLine("[5] HOST SERVER & CLOUDFLARE TUNNEL")
            appendLine("  Host Server Running   : ${hostStatus.running} (api=${hostStatus.apiRunning}, bridge=${hostStatus.bridgeRunning})")
            appendLine("  Tailscale IPs         : ${hostStatus.tailscaleIps.joinToString().ifBlank { "(none)" }}")
            appendLine("  Media Index State     : ${hostStatus.mediaIndexState} (${hostStatus.mediaIndexFileCount} files, ${hostStatus.mediaIndexMediaCount} media)")
            appendLine("  Cloudflare Tunnel     : ${tunnelStatus.state.label} (mode=${tunnelStatus.mode.shortLabel})")
            appendLine("  Public URL            : ${tunnelStatus.publicUrl ?: "(not configured)"}")
            appendLine("  CF-Ray / Latency      : ${tunnelStatus.cloudflareRay ?: "-"} / ${tunnelStatus.latencyMs?.let { "${it}ms" } ?: "-"}")
            appendLine("  Proxied Traffic       : ${tunnelStatus.requestsProxied} req / ${tunnelStatus.bytesProxied} bytes")
            if (tunnelStatus.logs.isNotEmpty()) {
                appendLine("  Recent Tunnel Events (${tunnelStatus.logs.size}):")
                tunnelStatus.logs.takeLast(15).forEach { line ->
                    appendLine("    [${timeFmt.format(Date(line.timestampMs))}] ${line.level} ${line.message}")
                }
            }
            appendLine()

            val logs = AppLogger.exportText()
            appendLine("[6] APPLICATION EVENT LOG (${AppLogger.entries.value.size} entries)")
            if (logs.isBlank()) {
                appendLine("  (empty)")
            } else {
                appendLine(logs)
            }
        }
    }

    fun buildDefaultFilename(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "pocketlink-diagnostic-$stamp.log"
    }

    fun writeShareableLogFile(context: Context, reportText: String): Uri {
        val logDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(logDir, buildDefaultFilename())
        file.writeText(reportText, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }

    private fun inspectNetworkSummary(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "Unknown"
        val active = cm.activeNetwork ?: return@runCatching "Offline"
        val caps = cm.getNetworkCapabilities(active) ?: return@runCatching "Unknown"
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }.joinToString("+").ifBlank { "Other" }
        val metered = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "Unmetered" else "Metered"
        "$transports ($metered)"
    }.getOrDefault("Unknown")
}
