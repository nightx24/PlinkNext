package com.example.data.network

import com.example.data.model.IndexedFile
import com.example.data.model.YtAudioQuality
import com.example.data.store.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationText: String = "",
    val thumbnailUrl: String = "",
) {
    fun toIndexedFile(): IndexedFile = IndexedFile(
        volume = "youtube",
        path = videoId,
        name = title,
        type = "file",
        size = 0L,
        modified = "",
        mime = "audio/webm",
        category = "music",
        artist = artist.ifBlank { "Discover" },
        artworkUrl = thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
        streamUri = "pocketlink-yt://track?v=$videoId",
        durationText = durationText,
        album = album.takeIf { it.isNotBlank() },
        durationMs = YtMusicClient.parseDurationTextToMs(durationText),
    )
}

data class YtStreamInfo(
    val url: String,
    val mimeType: String,
    val bitrateBps: Long,
    val clientName: String,
    val expiresAtMs: Long,
    val userAgent: String = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
    val extraHeaders: Map<String, String> = emptyMap(),
    val codecLabel: String = "",
    val itag: Int? = null,
    val useQueryRange: Boolean = false,
) {
    val isOpus: Boolean
        get() = codecLabel.equals("OPUS", ignoreCase = true) ||
            itag in setOf(774, 251, 250, 249) ||
            mimeType.contains("opus", ignoreCase = true) ||
            mimeType.contains("webm", ignoreCase = true)

    val isHls: Boolean
        get() = codecLabel.equals("HLS", ignoreCase = true) ||
            mimeType.contains("mpegurl", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true) ||
            url.contains("manifest.googlevideo.com", ignoreCase = true)

    val badgeLabel: String
        get() {
            val codec = codecLabel.ifBlank {
                when {
                    isHls -> "HLS"
                    isOpus -> "OPUS"
                    mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("m4a", ignoreCase = true) -> "M4A"
                    else -> "AUDIO"
                }
            }
            val kbps = (bitrateBps / 1_000L).toInt()
            return if (kbps > 0) "$codec · ${kbps}K" else codec
        }
}

data class StreamProbeEvent(
    val source: String,
    val itag: Int?,
    val codec: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val httpStatus: Int,
    val responseContentType: String,
    val accepted: Boolean,
    val note: String = "",
)

data class StreamResolutionTrace(
    val timestampMs: Long,
    val videoId: String,
    val requestedQuality: YtAudioQuality,
    val effectiveQuality: YtAudioQuality,
    val isUnmeteredNetwork: Boolean,
    val resolverProfile: String,
    val probes: List<StreamProbeEvent>,
    val selected: YtStreamInfo?,
    val errors: List<String>,
)

data class YtHomeFeed(
    val trending: List<YtTrack> = emptyList(),
    val quickPicks: List<YtTrack> = emptyList(),
    val sectionTitle: String = "Charts & Trending",
)

data class SyncedLyricLine(
    val timeMs: Long,
    val text: String,
)

data class TrackLyrics(
    val lines: List<SyncedLyricLine> = emptyList(),
    val isSynced: Boolean = false,
    val source: String = "LRCLIB",
)

private data class InnertubePlayerClient(
    val id: String,
    val clientName: String,
    val clientVersion: String,
    val clientHeaderId: String,
    val userAgent: String,
    val endpointHost: String = "https://www.youtube.com",
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: Int? = null,
    val embedUrl: String? = null,
    val isWebClient: Boolean = false,
    val useVisitorData: Boolean = false,
)

class YtMusicClient {
    @Volatile var audioQuality: YtAudioQuality = YtAudioQuality.AUTO
    @Volatile var resolverProfile: String = "AUTO"
    @Volatile var countryCode: String = "US"
    @Volatile var isUnmeteredNetworkProvider: () -> Boolean = { true }
    @Volatile var lastResolutionTrace: StreamResolutionTrace? = null
        private set
    @Volatile private var visitorData: String = ""
    @Volatile private var lastWorkingPipedHost: String? = null
    @Volatile private var lastWorkingInvidiousHost: String? = null
    @Volatile private var discoveredPipedHosts: List<String> = emptyList()
    @Volatile private var discoveredInvidiousHosts: List<String> = emptyList()
    @Volatile private var lastProxyDiscoveryAtMs: Long = 0L

    fun effectiveAudioQuality(): YtAudioQuality =
        resolveEffectiveQuality(audioQuality, runCatching { isUnmeteredNetworkProvider() }.getOrDefault(true))

    private val streamCache = ConcurrentHashMap<String, YtStreamInfo>()
    private val failedClientsByVideo = ConcurrentHashMap<String, MutableSet<String>>()
    private val lyricsCache = ConcurrentHashMap<String, TrackLyrics>()

    private val sharedConnectionPool = ConnectionPool(10, 2, TimeUnit.MINUTES)

    val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .dispatcher(Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 8
        })
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val mediaClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 8
        })
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // Fast-fail OkHttpClient dedicated to public Piped/Invidious failover & instance discovery
    private val proxyClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val newPipeExtractor: NewPipeAudioExtractor by lazy {
        NewPipeAudioExtractor(apiClient)
    }

    fun clearCaches() {
        streamCache.clear()
        failedClientsByVideo.clear()
        lyricsCache.clear()
    }

    fun invalidateStream(videoId: String, markLastClientFailed: Boolean = false) {
        val cleanId = videoId.trim()
        if (cleanId.isEmpty()) return
        if (markLastClientFailed) {
            val prevClient = streamCache.entries
                .firstOrNull { it.key.startsWith("$cleanId:") }
                ?.value?.clientName
                ?: lastResolutionTrace?.takeIf { it.videoId == cleanId }?.selected?.clientName
            if (!prevClient.isNullOrBlank()) {
                failedClientsByVideo.computeIfAbsent(cleanId) {
                    java.util.Collections.synchronizedSet(mutableSetOf())
                }.add(prevClient)
            }
        }
        streamCache.keys.filter { it.startsWith("$cleanId:") }.forEach { streamCache.remove(it) }
    }

    suspend fun fetchHomeFeed(forceRefresh: Boolean = false): YtHomeFeed = withContext(Dispatchers.IO) {
        val chartBrowse = runCatching {
            postMusicBrowse("FEmusic_charts")
        }.getOrNull()
        val chartTracks = chartBrowse?.let { parseTracksFromJsonTree(it, limit = 30) }.orEmpty()

        val homeBrowse = runCatching {
            postMusicBrowse("FEmusic_home")
        }.getOrNull()
        val homeTracks = homeBrowse?.let { parseTracksFromJsonTree(it, limit = 24) }.orEmpty()

        val combinedTrending = (chartTracks.ifEmpty {
            searchSongsInternal("Top Hits 2025", limit = 30)
        }).distinctBy { it.videoId }

        val quickPicks = homeTracks
            .filter { candidate -> combinedTrending.none { it.videoId == candidate.videoId } }
            .ifEmpty { combinedTrending.drop(8).ifEmpty { combinedTrending } }
            .take(20)

        YtHomeFeed(
            trending = combinedTrending,
            quickPicks = quickPicks,
            sectionTitle = "Top Charts · ${countryCode.uppercase(Locale.US)}",
        )
    }

    suspend fun searchSongs(query: String, limit: Int = 30): List<YtTrack> = withContext(Dispatchers.IO) {
        searchSongsInternal(query, limit)
    }

    private fun searchSongsInternal(query: String, limit: Int = 30): List<YtTrack> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Attempt 1: songs-filtered search via InnerTube WEB_REMIX
        val filteredJson = runCatching {
            postMusicSearch(trimmed, params = SONGS_SEARCH_PARAMS)
        }.getOrNull()
        val filteredTracks = filteredJson?.let { parseTracksFromJsonTree(it, limit) }.orEmpty()
        if (filteredTracks.isNotEmpty()) return filteredTracks

        // Attempt 2: unfiltered InnerTube search
        val generalJson = runCatching { postMusicSearch(trimmed, params = null) }.getOrNull()
        val generalTracks = generalJson?.let { parseTracksFromJsonTree(it, limit) }.orEmpty()
        if (generalTracks.isNotEmpty()) return generalTracks

        // Attempt 3: NewPipeExtractor YouTube search fallback
        return runCatching {
            newPipeExtractor.searchTracks(trimmed, countryCode, limit)
        }.getOrDefault(emptyList())
    }

    suspend fun fetchSearchSuggestions(input: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        val body = JSONObject()
            .put("context", webRemixContext(countryCode, visitorData))
            .put("input", trimmed)
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/music/get_search_suggestions?key=$INNERTUBE_PUBLIC_KEY&prettyPrint=false")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .apply { if (visitorData.isNotBlank()) header("X-Goog-Visitor-Id", visitorData) }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val text = apiClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext emptyList()
            res.body?.string().orEmpty()
        }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext emptyList()
        captureVisitorData(json)
        parseSuggestionsFromJsonTree(json)
    }

    suspend fun fetchRadioNext(
        videoId: String,
        limit: Int = 25,
        excludeVideoIds: Set<String> = emptySet(),
    ): List<YtTrack> = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext emptyList()
        val seen = HashSet<String>(excludeVideoIds.size + 4).apply {
            addAll(excludeVideoIds)
            add(videoId)
        }
        val collected = ArrayList<YtTrack>(limit)

        fun collectUnique(items: List<YtTrack>) {
            for (t in items) {
                if (t.videoId.isNotBlank() && seen.add(t.videoId)) {
                    collected.add(t)
                    if (collected.size >= limit) break
                }
            }
        }

        // Page 1: InnerTube YouTube Music Radio (RDAMVM<videoId>)
        var continuationToken: String? = null
        runCatching {
            val body = JSONObject()
                .put("context", webRemixContext(countryCode, visitorData))
                .put("videoId", videoId)
                .put("playlistId", "RDAMVM$videoId")
                .put("isAudioOnly", true)
            val json = postMusicNext(body)
            if (json != null) {
                collectUnique(parseTracksFromJsonTree(json, limit * 2))
                continuationToken = extractNextContinuationToken(json)
            }
        }

        // Page 2 (Continuation): Pull deeper radio recommendations if Page 1 overlapped with already-seen songs
        if (collected.size < limit && !continuationToken.isNullOrBlank()) {
            runCatching {
                val contBody = JSONObject()
                    .put("context", webRemixContext(countryCode, visitorData))
                    .put("continuation", continuationToken)
                    .put("isAudioOnly", true)
                val contJson = postMusicNext(contBody)
                if (contJson != null) {
                    collectUnique(parseTracksFromJsonTree(contJson, limit * 2))
                }
            }
        }

        // Fallback: NewPipeExtractor related streams if InnerTube returned too few unseen tracks
        if (collected.size < 6) {
            val npRelated = runCatching {
                newPipeExtractor.fetchRelatedTracks(videoId, countryCode, limit)
            }.getOrDefault(emptyList())
            collectUnique(npRelated)
        }

        collected
    }

    /**
     * Resolves a canonical YouTube Music videoId for a local/host audio file using its clean
     * metadata (embedded title + primary artist) so Spotify-style Autoplay can seed real radio
     * recommendations when a local/host playlist finishes.
     */
    suspend fun findSeedVideoIdForTrack(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSearchTerm(title)
        val primaryArtist = extractPrimaryArtist(artist)
        val query = listOf(cleanTitle, primaryArtist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) return@withContext null
        val matches = searchSongsInternal(query, limit = 6)
        matches.firstOrNull()?.videoId
    }

    /**
     * Fetches the next page of Discover tracks so browsing/scrolling charts or search results
     * never stops at a hard 24/30 track cap.
     */
    suspend fun fetchMoreDiscoverTracks(
        seedTracks: List<YtTrack>,
        query: String = "",
        excludeVideoIds: Set<String> = emptySet(),
        limit: Int = 24,
    ): List<YtTrack> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>(excludeVideoIds)
        val out = ArrayList<YtTrack>(limit)

        fun appendFresh(items: List<YtTrack>) {
            for (t in items) {
                if (t.videoId.isNotBlank() && seen.add(t.videoId)) {
                    out.add(t)
                    if (out.size >= limit) break
                }
            }
        }

        // Try radio expansion from the last few seed tracks (tail -> middle -> first)
        val candidateSeeds = buildList {
            seedTracks.lastOrNull()?.let { add(it) }
            if (seedTracks.size >= 3) add(seedTracks[seedTracks.size / 2])
            seedTracks.firstOrNull()?.let { if (it !in this) add(it) }
        }
        for (seed in candidateSeeds) {
            if (out.size >= limit) break
            val related = fetchRadioNext(seed.videoId, limit = limit - out.size + 6, excludeVideoIds = seen)
            appendFresh(related)
        }

        // If still short and we have a query or artist, run a broader mix search
        if (out.size < 8) {
            val mixQuery = when {
                query.isNotBlank() -> "$query mix"
                seedTracks.isNotEmpty() -> "${extractPrimaryArtist(seedTracks.last().artist)} similar songs"
                else -> "Top Hits ${countryCode.uppercase(Locale.US)} mix"
            }.trim()
            if (mixQuery.isNotBlank()) {
                appendFresh(searchSongsInternal(mixQuery, limit = limit))
            }
        }

        out
    }

    private fun postMusicNext(body: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next?key=$INNERTUBE_PUBLIC_KEY&prettyPrint=false")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .apply { if (visitorData.isNotBlank()) header("X-Goog-Visitor-Id", visitorData) }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val text = apiClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            res.body?.string().orEmpty()
        }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        captureVisitorData(json)
        return json
    }

    private fun extractNextContinuationToken(root: JSONObject): String? {
        var found: String? = null
        fun walk(node: Any?) {
            if (found != null || node == null) return
            when (node) {
                is JSONObject -> {
                    val nextData = node.optJSONObject("nextRadioContinuationData")
                        ?: node.optJSONObject("nextContinuationData")
                    val token = nextData?.optString("continuation")?.trim()
                    if (!token.isNullOrBlank()) {
                        found = token
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext() && found == null) {
                        walk(node.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        if (found != null) break
                        walk(node.opt(i))
                    }
                }
            }
        }
        walk(root)
        return found
    }

    /**
     * Resolves a verified, playable audio stream (URL + matching User-Agent + codec metadata)
     * using a resilient multi-engine waterfall:
     * 1. NewPipeExtractor (with full JS signatureCipher & n-parameter deobfuscation)
     * 2. Direct InnerTube multi-client waterfall (ANDROID_VR, IOS, TVHTML5 Embedded, ANDROID_TESTSUITE, ANDROID_MUSIC)
     * 3. Public Piped / Invidious audio proxy failover (handles datacenter / bot-walled networks)
     *
     * Every candidate stream URL is verified via a lightweight HTTP Range probe before caching/returning
     * so HTTP 403 / dead URLs are rejected immediately rather than handed to ExoPlayer.
     */
    fun resolveAudioStreamBlocking(videoId: String): YtStreamInfo {
        val cleanId = videoId.trim()
        require(cleanId.isNotEmpty()) { "Missing Discover track ID" }

        val now = System.currentTimeMillis()
        val isUnmetered = runCatching { isUnmeteredNetworkProvider() }.getOrDefault(true)
        val requestedQuality = audioQuality
        val effectiveQuality = resolveEffectiveQuality(requestedQuality, isUnmetered)
        val cacheKey = "$cleanId:${effectiveQuality.prefKey}:$resolverProfile"
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs - 120_000L > now) {
                return cached
            }
        }

        val profile = resolverProfile.uppercase(Locale.US)
        val errors = mutableListOf<String>()
        val probeLog = mutableListOf<StreamProbeEvent>()
        val deferredNonOpusCandidates = mutableListOf<YtStreamInfo>()

        fun recordAndReturn(winner: YtStreamInfo): YtStreamInfo {
            streamCache[cacheKey] = winner
            lastResolutionTrace = StreamResolutionTrace(
                timestampMs = System.currentTimeMillis(),
                videoId = cleanId,
                requestedQuality = requestedQuality,
                effectiveQuality = effectiveQuality,
                isUnmeteredNetwork = isUnmetered,
                resolverProfile = profile,
                probes = probeLog.toList(),
                selected = winner,
                errors = errors.toList(),
            )
            AppLogger.info(
                "Resolved Discover stream",
                "$cleanId via ${winner.clientName} (${winner.badgeLabel}, itag=${winner.itag ?: "?"}, req=${requestedQuality.shortLabel}->${effectiveQuality.shortLabel}, mime=${winner.mimeType})"
            )
            return winner
        }

        fun probeCandidate(candidate: YtStreamInfo): YtStreamInfo? {
            val res = probeStreamUrl(candidate)
            probeLog.add(
                StreamProbeEvent(
                    source = candidate.clientName,
                    itag = candidate.itag,
                    codec = if (candidate.isOpus) "OPUS" else (candidate.codecLabel.ifBlank { "M4A" }),
                    mimeType = candidate.mimeType,
                    bitrateKbps = (candidate.bitrateBps / 1_000L).toInt(),
                    httpStatus = res.httpStatus,
                    responseContentType = res.contentType,
                    accepted = res.ok,
                    note = res.note,
                )
            )
            return if (res.ok) candidate.copy(useQueryRange = res.useQueryRange) else null
        }

        // === PASS 1: STRICT OPUS-FIRST ACROSS NEWPIPE + OPUS-CAPABLE INNERTUBE CLIENTS ===
        val deprioritizedClients = failedClientsByVideo[cleanId]?.toSet().orEmpty()
        if (profile == "AUTO" || profile == "NEWPIPE") {
            if ("NewPipe" !in deprioritizedClients) {
                try {
                    val npCandidates = newPipeExtractor.extractCandidateStreams(cleanId, effectiveQuality, countryCode)
                    val (npOpus, npFallback) = npCandidates.partition { it.isOpus }
                    deferredNonOpusCandidates.addAll(npFallback)

                    for (candidate in npOpus.take(4)) {
                        probeCandidate(candidate)?.let { return recordAndReturn(it) }
                    }
                    when {
                        npOpus.isNotEmpty() -> errors.add("NewPipe Opus (${npOpus.size}) rejected by CDN")
                        npCandidates.isEmpty() -> errors.add("NewPipe found no playable audio streams")
                        else -> errors.add("NewPipe returned ${npFallback.size} non-Opus stream(s); checking InnerTube for Opus")
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    errors.add("NewPipe: $msg")
                    AppLogger.warning("NewPipe extractor fallback", msg)
                }
            } else {
                errors.add("NewPipe skipped (failed on previous attempt)")
            }
        }

        // Direct InnerTube multi-client waterfall (ANDROID_VR / WEB_EMBEDDED / VISIONOS / TVHTML5 / IOS)
        if (profile != "NEWPIPE" || errors.isNotEmpty() || deferredNonOpusCandidates.isNotEmpty()) {
            val baseCandidates = orderedClients(profile)
            // Move any client that previously returned a playback 403 for this videoId to the back
            val candidates = baseCandidates.filterNot { it.id in deprioritizedClients } +
                baseCandidates.filter { it.id in deprioritizedClients }
            val loginBlockedClientFamilies = HashSet<String>()
            for (client in candidates) {
                if (client.clientName in loginBlockedClientFamilies) continue
                try {
                    val activeVisitor = if (client.useVisitorData) {
                        ensureVisitorData()
                        visitorData
                    } else ""
                    val playerJson = postYoutubePlayer(cleanId, client, countryCode, activeVisitor)
                    if (client.useVisitorData) captureVisitorData(playerJson)
                    val ordered = selectOrderedAudioStreams(
                        playerJson = playerJson,
                        quality = effectiveQuality,
                        clientName = client.id,
                        userAgent = client.userAgent,
                        isUnmeteredNetwork = isUnmetered,
                    )
                    if (ordered.isEmpty()) {
                        val reason = playerJson.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
                        val status = playerJson.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                        if (status.isNotBlank()) {
                            errors.add("${client.id}: $status ${reason.take(60)}".trim())
                            if (status.equals("LOGIN_REQUIRED", ignoreCase = true)) {
                                // Skip redundant version variants of the same client family (e.g., 4x ANDROID_VR)
                                loginBlockedClientFamilies.add(client.clientName)
                                if (client.useVisitorData) visitorData = ""
                            }
                        }
                        continue
                    }

                    val (clientOpus, clientNonOpus) = ordered.partition { it.isOpus }
                    deferredNonOpusCandidates.addAll(clientNonOpus.take(4))

                    for (opusStream in clientOpus.take(3)) {
                        probeCandidate(opusStream)?.let { return recordAndReturn(it) }
                    }
                    if (clientOpus.isNotEmpty()) {
                        errors.add("${client.id}: Opus probe rejected")
                    }
                } catch (t: Throwable) {
                    val msg = t.message ?: t.javaClass.simpleName
                    errors.add("${client.id}: $msg")
                    AppLogger.warning("Discover client fallback (${client.id})", msg)
                }
            }
        }

        // === PASS 2: M4A / PROGRESSIVE / HLS FALLBACK IF NO OPUS STREAM PASSED >1 MiB PROBE ===
        if (deferredNonOpusCandidates.isNotEmpty()) {
            AppLogger.warning(
                "No unrestricted Opus stream reachable; falling back to M4A/MP4/HLS",
                "$cleanId (${deferredNonOpusCandidates.size} fallback candidate(s))"
            )
            // Prefer direct M4A/progressive streams first, then unrestricted HLS (.m3u8) manifests
            val prioritizedFallbacks = deferredNonOpusCandidates
                .distinctBy { "${it.clientName}:${it.itag ?: it.codecLabel}:${it.bitrateBps}" }
                .sortedBy { if (it.isHls) 1 else 0 }
            for (fallback in prioritizedFallbacks.take(8)) {
                probeCandidate(fallback)?.let { return recordAndReturn(it) }
            }
        }

        // === PASS 3: PUBLIC PIPED / INVIDIOUS PROXY FAILOVER (DYNAMIC DISCOVERY + MULTI-CANDIDATE) ===
        val proxyStream = resolveViaPipedOrInvidious(cleanId, effectiveQuality, ::probeCandidate, errors)
        if (proxyStream != null) {
            return recordAndReturn(proxyStream)
        }

        lastResolutionTrace = StreamResolutionTrace(
            timestampMs = System.currentTimeMillis(),
            videoId = cleanId,
            requestedQuality = requestedQuality,
            effectiveQuality = effectiveQuality,
            isUnmeteredNetwork = isUnmetered,
            resolverProfile = profile,
            probes = probeLog.toList(),
            selected = null,
            errors = errors.toList(),
        )
        val hasBotWall = errors.any {
            it.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                it.contains("not a bot", ignoreCase = true)
        }
        val summary = if (hasBotWall) {
            "YouTube blocked anonymous playback on this network/IP (bot check). Switch Wi-Fi/mobile data or disable VPN, then tap Retry."
        } else {
            errors.take(2).joinToString(" | ").ifBlank { "No playable audio stream available" }
        }
        throw IOException("Unable to play track ($summary)")
    }

    /**
     * Live diagnostic self-test used by the Log Generator: probes HIGH, MEDIUM, and LOW
     * quality tiers on [videoId] and returns a human-readable comparison table.
     */
    suspend fun probeQualityTiers(videoId: String): String = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim().ifBlank { "dQw4w9WgXcQ" }
        val savedQuality = audioQuality
        val lines = mutableListOf<String>()
        lines.add("Discover Multi-Tier Probe for videoId=$cleanId (country=$countryCode, profile=$resolverProfile)")
        try {
            for (tier in listOf(YtAudioQuality.HIGH, YtAudioQuality.MEDIUM, YtAudioQuality.LOW)) {
                audioQuality = tier
                invalidateStream(cleanId)
                val outcome = runCatching { resolveAudioStreamBlocking(cleanId) }
                outcome.onSuccess { info ->
                    lines.add(
                        "  [${tier.shortLabel.padEnd(6)}] OK -> ${info.badgeLabel} | itag=${info.itag ?: "?"} | mime=${info.mimeType} | client=${info.clientName}"
                    )
                }.onFailure { err ->
                    lines.add("  [${tier.shortLabel.padEnd(6)}] FAIL -> ${err.message}")
                }
            }
        } finally {
            audioQuality = savedQuality
            invalidateStream(cleanId)
        }
        val report = lines.joinToString("\n")
        AppLogger.info("Discover quality probe finished ($cleanId)", report)
        report
    }

    fun resolveAudioStreamUrlBlocking(videoId: String): String =
        resolveAudioStreamBlocking(videoId).url

    private data class ProbeOutcome(
        val ok: Boolean,
        val httpStatus: Int,
        val contentType: String,
        val note: String = "",
        val useQueryRange: Boolean = false,
    )

    private fun probeStreamUrl(info: YtStreamInfo): ProbeOutcome {
        val isGoogleVideo = info.url.contains("googlevideo.com", ignoreCase = true)
        val probeClient = if (isGoogleVideo) apiClient else proxyClient

        // HLS (.m3u8) manifests do not support arbitrary byte-range probes; verify HTTP 200 + #EXTM3U header
        if (info.isHls) {
            return runCatching {
                val reqBuilder = Request.Builder()
                    .url(info.url)
                    .get()
                    .header("User-Agent", info.userAgent)
                    .header("Accept", "*/*")
                info.extraHeaders.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true) && !k.equals("Range", ignoreCase = true)) {
                        reqBuilder.header(k, v)
                    }
                }
                probeClient.newCall(reqBuilder.build()).execute().use { res ->
                    val ct = res.header("Content-Type").orEmpty()
                    val bodyPreview = res.body?.string().orEmpty().take(512)
                    val validM3u = res.code in 200..299 && bodyPreview.contains("#EXTM3U", ignoreCase = true)
                    ProbeOutcome(
                        ok = validM3u,
                        httpStatus = res.code,
                        contentType = ct,
                        note = if (validM3u) "OK HLS (#EXTM3U)" else "HLS HTTP ${res.code}",
                        useQueryRange = false,
                    )
                }
            }.getOrElse { err ->
                ProbeOutcome(
                    ok = false,
                    httpStatus = -1,
                    contentType = "",
                    note = err.message ?: err.javaClass.simpleName,
                )
            }
        }

        fun executeSlice(
            start: Long,
            end: Long,
            seq: Int,
            useQueryMode: Boolean,
        ): Triple<Boolean, Int, Pair<String, Long>> {
            val targetUrl = if (useQueryMode) {
                io.github.augztn.pocketlink.ssdbridge.ChunkedYoutubeDataSource.buildQueryRangeUrl(
                    info.url, start, end, seq
                )
            } else {
                info.url
            }
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", info.userAgent)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            if (!useQueryMode) {
                reqBuilder.header("Range", "bytes=$start-$end")
            }
            info.extraHeaders.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true) && !k.equals("User-Agent", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            return probeClient.newCall(reqBuilder.build()).execute().use { res ->
                val okCode = res.code == 200 || res.code == 206
                val contentType = res.header("Content-Type").orEmpty()
                val contentRange = res.header("Content-Range").orEmpty()
                val totalBytes = contentRange.substringAfterLast('/', "").trim().toLongOrNull()
                    ?: io.github.augztn.pocketlink.ssdbridge.ChunkedYoutubeDataSource.extractClenFromUrl(info.url)
                    ?: res.header("Content-Length")?.toLongOrNull()
                    ?: -1L
                val isHtmlOrJsonError = contentType.contains("text/html", ignoreCase = true) ||
                    contentType.contains("application/json", ignoreCase = true)
                Triple(okCode && !isHtmlOrJsonError, res.code, contentType to totalBytes)
            }
        }

        fun probeTwoChunks(useQueryMode: Boolean, requireSecondChunkPast1MiB: Boolean): ProbeOutcome {
            val modeTag = if (useQueryMode) "query(&range=)" else "header(Range:)"
            val (ok1, code1, meta1) = executeSlice(0L, 16_383L, seq = 1, useQueryMode = useQueryMode)
            val (contentType1, totalBytes) = meta1
            if (!ok1) {
                return ProbeOutcome(
                    ok = false,
                    httpStatus = code1,
                    contentType = contentType1,
                    note = if (contentType1.contains("text/html", true)) "HTML response" else "Chunk 1 HTTP $code1 ($modeTag)",
                    useQueryRange = useQueryMode,
                )
            }
            // Only enforce the >1 MiB second-chunk check on googlevideo.com CDNs (Piped/Invidious proxies may stream HTTP 200)
            if (requireSecondChunkPast1MiB) {
                val secondStart = computeSecondProbeOffset(totalBytes)
                if (secondStart > 0L) {
                    val secondEnd = minOf(secondStart + 2047L, totalBytes - 1L)
                    val (ok2, code2, meta2) = executeSlice(secondStart, secondEnd, seq = 2, useQueryMode = useQueryMode)
                    if (!ok2) {
                        return ProbeOutcome(
                            ok = false,
                            httpStatus = code2,
                            contentType = meta2.first,
                            note = "Chunk 2 @${secondStart / 1024}KB HTTP $code2 ($modeTag)",
                            useQueryRange = useQueryMode,
                        )
                    }
                }
            }
            return ProbeOutcome(
                ok = true,
                httpStatus = code1,
                contentType = contentType1,
                note = if (totalBytes > 0L) "OK $modeTag (${totalBytes / 1024}KB)" else "OK $modeTag",
                useQueryRange = useQueryMode,
            )
        }

        return runCatching {
            if (isGoogleVideo) {
                // InnerTune / NewPipe / YouTube VR native mode: try &range= query param first, then HTTP Range header
                val queryRes = probeTwoChunks(useQueryMode = true, requireSecondChunkPast1MiB = true)
                if (queryRes.ok) return@runCatching queryRes
                val headerRes = probeTwoChunks(useQueryMode = false, requireSecondChunkPast1MiB = true)
                if (headerRes.ok) return@runCatching headerRes
                headerRes
            } else {
                probeTwoChunks(useQueryMode = false, requireSecondChunkPast1MiB = false)
            }
        }.getOrElse { err ->
            ProbeOutcome(
                ok = false,
                httpStatus = -1,
                contentType = "",
                note = err.message ?: err.javaClass.simpleName,
            )
        }
    }

    /**
     * Probes the stream URL across initial + mid-stream chunks with the exact User-Agent & transport
     * that ExoPlayer will use, rejecting HTTP 403 / HTML / broken links before playback starts.
     */
    private fun validateStreamUrl(info: YtStreamInfo): Boolean = probeStreamUrl(info).ok

    private fun refreshPublicProxyInstancesIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastProxyDiscoveryAtMs < TimeUnit.MINUTES.toMillis(30)) return
        lastProxyDiscoveryAtMs = now

        // 1. Official live healthy Invidious API directory
        runCatching {
            val req = Request.Builder()
                .url("https://api.invidious.io/instances.json?sort_by=type,health")
                .header("User-Agent", NewPipeOkHttpDownloader.BROWSER_USER_AGENT)
                .get()
                .build()
            val raw = proxyClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) "" else res.body?.string().orEmpty()
            }
            if (raw.startsWith("[")) {
                val parsed = parseInvidiousInstancesJson(raw)
                if (parsed.isNotEmpty()) discoveredInvidiousHosts = parsed
            }
        }

        // 2. Official live Piped API directory
        runCatching {
            val req = Request.Builder()
                .url("https://piped-instances.kavin.rocks/")
                .header("User-Agent", NewPipeOkHttpDownloader.BROWSER_USER_AGENT)
                .get()
                .build()
            val raw = proxyClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) "" else res.body?.string().orEmpty()
            }
            if (raw.startsWith("[")) {
                val parsed = parsePipedInstancesJson(raw)
                if (parsed.isNotEmpty()) discoveredPipedHosts = parsed
            }
        }
    }

    private fun resolveViaPipedOrInvidious(
        videoId: String,
        quality: YtAudioQuality,
        probe: (YtStreamInfo) -> YtStreamInfo?,
        errors: MutableList<String>,
    ): YtStreamInfo? {
        refreshPublicProxyInstancesIfStale()

        val invidiousPool = buildList {
            lastWorkingInvidiousHost?.let(::add)
            addAll(discoveredInvidiousHosts)
            addAll(INVIDIOUS_FALLBACK_INSTANCES)
        }.map { it.trimEnd('/') }.distinct().take(6)

        for (base in invidiousPool) {
            val candidates = runCatching {
                val req = Request.Builder()
                    .url("$base/api/v1/videos/$videoId?fields=adaptiveFormats,formatStreams,hlsUrl")
                    .header("User-Agent", NewPipeOkHttpDownloader.BROWSER_USER_AGENT)
                    .get()
                    .build()
                val raw = proxyClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@runCatching emptyList()
                    res.body?.string().orEmpty()
                }
                if (raw.isBlank() || !raw.trimStart().startsWith("{")) return@runCatching emptyList()
                selectOrderedInvidiousAudioStreams(JSONObject(raw), base, videoId, quality)
            }.getOrDefault(emptyList())

            for (cand in candidates.take(4)) {
                probe(cand)?.let { winner ->
                    lastWorkingInvidiousHost = base
                    return winner
                }
            }
        }

        val pipedPool = buildList {
            lastWorkingPipedHost?.let(::add)
            addAll(discoveredPipedHosts)
            addAll(PIPED_FALLBACK_INSTANCES)
        }.map { it.trimEnd('/') }.distinct().take(6)

        for (base in pipedPool) {
            val candidates = runCatching {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", NewPipeOkHttpDownloader.BROWSER_USER_AGENT)
                    .get()
                    .build()
                val raw = proxyClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@runCatching emptyList()
                    res.body?.string().orEmpty()
                }
                if (raw.isBlank() || !raw.trimStart().startsWith("{")) return@runCatching emptyList()
                selectOrderedPipedAudioStreams(JSONObject(raw), quality, "Piped")
            }.getOrDefault(emptyList())

            for (cand in candidates.take(4)) {
                probe(cand)?.let { winner ->
                    lastWorkingPipedHost = base
                    return winner
                }
            }
        }

        errors.add("Public proxy failover (Invidious ${invidiousPool.size} / Piped ${pipedPool.size}): no playable stream")
        return null
    }

    private fun ensureVisitorData() {
        if (visitorData.isNotBlank()) return
        runCatching {
            val body = JSONObject().put("context", webRemixContext(countryCode, ""))
            val req = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/visitor_id?key=$INNERTUBE_PUBLIC_KEY&prettyPrint=false")
                .header("User-Agent", WEB_USER_AGENT)
                .header("Origin", "https://music.youtube.com")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            apiClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use
                val raw = res.body?.string().orEmpty()
                if (raw.isNotBlank()) {
                    captureVisitorData(JSONObject(raw))
                }
            }
        }
    }

    private fun captureVisitorData(json: JSONObject) {
        val found = json.optJSONObject("responseContext")?.optString("visitorData").orEmpty().trim()
        if (found.isNotEmpty()) {
            visitorData = found
        }
    }

    /**
     * BitChord-style metadata-aware lyrics lookup:
     * 1. Cleans YouTube/filename noise (Official Video, Remastered, feat., track numbers).
     * 2. Tries LRCLIB exact `/api/get` with track_name + primary artist_name + album_name + duration(s).
     * 3. Falls back to structured `/api/search` and ranks every candidate by synced LRC availability,
     *    title/artist/album similarity, and duration proximity (±3s / ±8s / >30s penalty).
     */
    suspend fun fetchLyrics(
        trackName: String,
        artistName: String,
        albumName: String = "",
        durationMs: Long = 0L,
    ): TrackLyrics? = withContext(Dispatchers.IO) {
        val cleanTrack = cleanSearchTerm(trackName)
        val cleanArtistFull = cleanSearchTerm(artistName)
        val primaryArtist = extractPrimaryArtist(cleanArtistFull)
        val cleanAlbum = cleanSearchTerm(albumName)
        val durationSec = (durationMs / 1000L).toInt().takeIf { it in 20..3600 }
        if (cleanTrack.isBlank()) return@withContext null

        val key = "${cleanTrack.lowercase(Locale.US)}::${primaryArtist.lowercase(Locale.US)}::${durationSec ?: 0}"
        lyricsCache[key]?.let { return@withContext it }

        val ignoreArtist = primaryArtist.isBlank() ||
            primaryArtist.equals("YouTube Music", ignoreCase = true) ||
            primaryArtist.equals("Discover", ignoreCase = true) ||
            primaryArtist.equals("Local Lossless", ignoreCase = true) ||
            primaryArtist.equals("Unknown Artist", ignoreCase = true)

        // Stage 1: Exact LRCLIB /api/get using full track + artist + album + duration metadata
        if (!ignoreArtist) {
            val getBuilder = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", cleanTrack)
                .addQueryParameter("artist_name", primaryArtist)
            if (cleanAlbum.isNotBlank()) getBuilder.addQueryParameter("album_name", cleanAlbum)
            if (durationSec != null) getBuilder.addQueryParameter("duration", durationSec.toString())

            val direct = runCatching { requestLrclibJsonObject(getBuilder.build().toString()) }.getOrNull()
            val parsedDirect = direct?.let { parseLrclibItem(it) }
            if (parsedDirect != null) {
                lyricsCache[key] = parsedDirect
                return@withContext parsedDirect
            }
        }

        // Stage 2: Structured /api/search?track_name=...&artist_name=...
        val searchCandidates = mutableListOf<JSONObject>()
        if (!ignoreArtist) {
            val structuredUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", cleanTrack)
                .addQueryParameter("artist_name", primaryArtist)
                .apply { if (cleanAlbum.isNotBlank()) addQueryParameter("album_name", cleanAlbum) }
                .build()
            searchCandidates.addAll(requestLrclibSearchArray(structuredUrl.toString()))
        }

        // Stage 3: Broad /api/search?q=... fallback if structured search returned nothing
        if (searchCandidates.isEmpty()) {
            val queryText = if (!ignoreArtist) "$cleanTrack $primaryArtist" else cleanTrack
            val broadUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", queryText)
                .build()
            searchCandidates.addAll(requestLrclibSearchArray(broadUrl.toString()))
        }

        // Stage 4: Title-only fallback if multi-artist query missed
        if (searchCandidates.isEmpty() && !ignoreArtist) {
            val titleOnlyUrl = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", cleanTrack)
                .build()
            searchCandidates.addAll(requestLrclibSearchArray(titleOnlyUrl.toString()))
        }

        val best = selectBestLrclibCandidate(
            candidates = searchCandidates,
            targetTitle = cleanTrack,
            targetArtist = if (ignoreArtist) "" else primaryArtist,
            targetAlbum = cleanAlbum,
            targetDurationSec = durationSec,
        )
        if (best != null) {
            lyricsCache[key] = best
        }
        best
    }

    private fun requestLrclibSearchArray(url: String): List<JSONObject> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", LRCLIB_USER_AGENT)
            .get()
            .build()
        val bodyText = apiClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@runCatching emptyList()
            res.body?.string().orEmpty()
        }
        val arr = JSONArray(bodyText)
        buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun requestLrclibJsonObject(url: String): JSONObject? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", LRCLIB_USER_AGENT)
            .get()
            .build()
        apiClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val raw = res.body?.string().orEmpty()
            return runCatching { JSONObject(raw) }.getOrNull()
        }
    }

    private fun postMusicBrowse(browseId: String): JSONObject {
        val body = JSONObject()
            .put("context", webRemixContext(countryCode, visitorData))
            .put("browseId", browseId)
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?key=$INNERTUBE_PUBLIC_KEY&prettyPrint=false")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .apply { if (visitorData.isNotBlank()) header("X-Goog-Visitor-Id", visitorData) }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        apiClient.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("Browse failed (${res.code})")
            return JSONObject(raw).also { captureVisitorData(it) }
        }
    }

    private fun postMusicSearch(query: String, params: String?): JSONObject {
        val body = JSONObject()
            .put("context", webRemixContext(countryCode, visitorData))
            .put("query", query)
            .apply { if (params != null) put("params", params) }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?key=$INNERTUBE_PUBLIC_KEY&prettyPrint=false")
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .apply { if (visitorData.isNotBlank()) header("X-Goog-Visitor-Id", visitorData) }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        apiClient.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("Search failed (${res.code})")
            return JSONObject(raw).also { captureVisitorData(it) }
        }
    }

    private fun postYoutubePlayer(
        videoId: String,
        client: InnertubePlayerClient,
        gl: String,
        currentVisitorData: String,
    ): JSONObject {
        val clientObj = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", client.clientVersion)
            .put("hl", "en")
            .put("gl", gl.ifBlank { "US" }.uppercase(Locale.US))
            .put("utcOffsetMinutes", 0)
            .put("timeZone", "UTC")
            .put("clientScreen", "WATCH")
            .apply {
                if (client.userAgent.isNotBlank() && !client.isWebClient) {
                    put("userAgent", client.userAgent)
                }
                if (currentVisitorData.isNotBlank()) put("visitorData", currentVisitorData)
                client.osName?.let { put("osName", it) }
                client.osVersion?.let { put("osVersion", it) }
                client.deviceMake?.let { put("deviceMake", it) }
                client.deviceModel?.let { put("deviceModel", it) }
                client.androidSdkVersion?.let { put("androidSdkVersion", it) }
            }
        val contextObj = JSONObject().put("client", clientObj).apply {
            client.embedUrl?.let { put("thirdParty", JSONObject().put("embedUrl", it)) }
        }
        val body = JSONObject()
            .put("context", contextObj)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val req = Request.Builder()
            .url("${client.endpointHost}/youtubei/v1/player?key=$INNERTUBE_PUBLIC_KEY&prettyPrint=false")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientHeaderId)
            .header("X-YouTube-Client-Version", client.clientVersion)
            .apply {
                // Only browser/TV web clients send Origin; native mobile clients must omit it
                if (client.isWebClient) {
                    header("Origin", client.endpointHost)
                    header("Referer", "${client.endpointHost}/")
                }
                if (currentVisitorData.isNotBlank()) header("X-Goog-Visitor-Id", currentVisitorData)
            }
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        apiClient.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("Player HTTP ${res.code} (${client.id})")
            return JSONObject(raw)
        }
    }

    companion object {
        private const val INNERTUBE_PUBLIC_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val SONGS_SEARCH_PARAMS = "EgWKAQIIAWoSEAMQBBAJEAoQBRAREAUYHRAF"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val LRCLIB_USER_AGENT = "PocketLink-Android/1.0 (https://github.com/pocketlink)"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val PIPED_FALLBACK_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi-libre.kavin.rocks",
            "https://pipedapi.leptons.xyz",
            "https://pipedapi.adminforge.de",
            "https://api.piped.yt",
            "https://api.piped.private.coffee",
            "https://piped-api.privacy.com.de",
        )

        private val INVIDIOUS_FALLBACK_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://inv.thepixora.com",
            "https://inv.perditum.com",
            "https://yewtu.be",
            "https://invidious.nerdvpn.de",
        )

        private val CLIENT_WEB_EMBEDDED = InnertubePlayerClient(
            id = "WEB_EMBEDDED",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20250317.01.00",
            clientHeaderId = "56",
            userAgent = WEB_USER_AGENT,
            embedUrl = "https://www.youtube.com/",
            isWebClient = true,
            useVisitorData = false,
        )

        private val CLIENT_VISIONOS = InnertubePlayerClient(
            id = "VISIONOS",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientHeaderId = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            osName = "visionOS",
            osVersion = "26.5.23O471",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            useVisitorData = false,
        )

        private val CLIENT_ANDROID_VR_171 = InnertubePlayerClient(
            id = "ANDROID_VR_171",
            clientName = "ANDROID_VR",
            clientVersion = "1.71.26",
            clientHeaderId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.71.26 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            useVisitorData = false,
        )

        private val CLIENT_ANDROID_VR_161 = InnertubePlayerClient(
            id = "ANDROID_VR",
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientHeaderId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            useVisitorData = false,
        )

        private val CLIENT_ANDROID_VR_165 = InnertubePlayerClient(
            id = "ANDROID_VR_165",
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientHeaderId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            useVisitorData = false,
        )

        private val CLIENT_ANDROID_VR_143 = InnertubePlayerClient(
            id = "ANDROID_VR_143",
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            clientHeaderId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            useVisitorData = false,
        )

        private val CLIENT_TVHTML5 = InnertubePlayerClient(
            id = "TVHTML5",
            clientName = "TVHTML5",
            clientVersion = "7.20260215.10.00",
            clientHeaderId = "7",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            isWebClient = true,
            useVisitorData = false,
        )

        private val CLIENT_IOS = InnertubePlayerClient(
            id = "IOS",
            clientName = "IOS",
            clientVersion = "20.49.4",
            clientHeaderId = "5",
            userAgent = "com.google.ios.youtube/20.49.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            useVisitorData = false,
        )

        private val CLIENT_ANDROID_TESTSUITE = InnertubePlayerClient(
            id = "ANDROID_TESTSUITE",
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9",
            clientHeaderId = "30",
            userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            useVisitorData = false,
        )

        private val CLIENT_ANDROID_MUSIC = InnertubePlayerClient(
            id = "ANDROID_MUSIC",
            clientName = "ANDROID_MUSIC",
            clientVersion = "7.27.52",
            clientHeaderId = "21",
            userAgent = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 14) gzip",
            endpointHost = "https://music.youtube.com",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            useVisitorData = true,
        )

        internal fun computeSecondProbeOffset(totalBytes: Long): Long = when {
            totalBytes > 1_200_000L -> maxOf(1_114_112L, (totalBytes * 3L) / 5L)
            totalBytes > 1_052_672L -> 1_050_000L
            totalBytes > 96_000L -> totalBytes / 2L
            else -> -1L
        }

        private fun orderedClients(profile: String): List<InnertubePlayerClient> = when (profile.uppercase(Locale.US)) {
            "IOS" -> listOf(
                CLIENT_VISIONOS,
                CLIENT_IOS,
                CLIENT_WEB_EMBEDDED,
                CLIENT_ANDROID_VR_171,
                CLIENT_ANDROID_VR_165,
                CLIENT_ANDROID_VR_143,
                CLIENT_ANDROID_VR_161,
                CLIENT_TVHTML5,
                CLIENT_ANDROID_MUSIC,
                CLIENT_ANDROID_TESTSUITE,
            )
            "ANDROID_VR" -> listOf(
                CLIENT_ANDROID_VR_171,
                CLIENT_ANDROID_VR_165,
                CLIENT_VISIONOS,
                CLIENT_WEB_EMBEDDED,
                CLIENT_ANDROID_VR_143,
                CLIENT_ANDROID_VR_161,
                CLIENT_TVHTML5,
                CLIENT_ANDROID_MUSIC,
                CLIENT_ANDROID_TESTSUITE,
                CLIENT_IOS,
            )
            else -> listOf(
                // Multi-family rotation: VR + VisionOS + Embedded Web + TV + iOS (no cross-client visitorData poisoning)
                CLIENT_ANDROID_VR_171,
                CLIENT_VISIONOS,
                CLIENT_WEB_EMBEDDED,
                CLIENT_ANDROID_VR_165,
                CLIENT_TVHTML5,
                CLIENT_IOS,
                CLIENT_ANDROID_VR_143,
                CLIENT_ANDROID_VR_161,
                CLIENT_ANDROID_MUSIC,
                CLIENT_ANDROID_TESTSUITE,
            )
        }

        private fun webRemixContext(countryCode: String, currentVisitorData: String): JSONObject = JSONObject().put(
            "client",
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250317.01.00")
                .put("hl", "en")
                .put("gl", countryCode.ifBlank { "US" }.uppercase(Locale.US))
                .apply { if (currentVisitorData.isNotBlank()) put("visitorData", currentVisitorData) }
        )

        internal fun resolveEffectiveQuality(
            requested: YtAudioQuality,
            isUnmeteredNetwork: Boolean = true,
        ): YtAudioQuality = when (requested) {
            YtAudioQuality.AUTO -> if (isUnmeteredNetwork) YtAudioQuality.HIGH else YtAudioQuality.MEDIUM
            else -> requested
        }

        private fun isOpusMimeOrItag(mime: String, itag: Int?): Boolean =
            itag in setOf(774, 251, 250, 249) ||
                mime.contains("opus", ignoreCase = true) ||
                mime.contains("webm", ignoreCase = true)

        private fun <T> orderCandidatesOpusFirst(
            pool: List<T>,
            resolvedQuality: YtAudioQuality,
            mimeOf: (T) -> String,
            bitrateOf: (T) -> Long,
            itagOf: (T) -> Int?,
        ): List<T> {
            fun sortPool(items: List<T>, isOpusPool: Boolean): List<T> {
                if (items.size <= 1) return items
                return when (resolvedQuality) {
                    YtAudioQuality.HIGH, YtAudioQuality.AUTO -> items.sortedWith(
                        compareByDescending<T> {
                            when (itagOf(it)) {
                                774 -> 2
                                251 -> 1
                                else -> 0
                            }
                        }.thenByDescending { bitrateOf(it) }
                    )
                    YtAudioQuality.MEDIUM -> {
                        val defaultTarget = if (isOpusPool) 70_000L else 128_000L
                        val distinctRates = items.map { bitrateOf(it) }.filter { it > 0L }.distinct().sorted()
                        val middleRate = if (distinctRates.size >= 3) distinctRates[distinctRates.size / 2] else defaultTarget
                        items.sortedWith(
                            compareByDescending<T> { if (isOpusPool && itagOf(it) == 250) 1 else 0 }
                                .thenBy { abs(bitrateOf(it) - middleRate) }
                                .thenByDescending { bitrateOf(it) }
                        )
                    }
                    YtAudioQuality.LOW -> items.sortedWith(
                        compareByDescending<T> { if (isOpusPool && itagOf(it) == 249) 1 else 0 }
                            .thenBy { val b = bitrateOf(it); if (b > 0L) b else Long.MAX_VALUE }
                    )
                }
            }

            val (opusPool, nonOpusPool) = pool.partition { isOpusMimeOrItag(mimeOf(it), itagOf(it)) }
            return sortPool(opusPool, isOpusPool = true) + sortPool(nonOpusPool, isOpusPool = false)
        }

        internal fun selectOrderedAudioStreams(
            playerJson: JSONObject,
            quality: YtAudioQuality,
            clientName: String,
            userAgent: String = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
            isUnmeteredNetwork: Boolean = true,
        ): List<YtStreamInfo> {
            val status = playerJson.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
            if (status.isNotEmpty() && !status.equals("OK", ignoreCase = true)) {
                return emptyList()
            }
            val streamingData = playerJson.optJSONObject("streamingData") ?: return emptyList()
            val adaptive = streamingData.optJSONArray("adaptiveFormats")
            val progressive = streamingData.optJSONArray("formats")

            data class Candidate(
                val url: String,
                val mime: String,
                val bitrate: Long,
                val isDefaultAudio: Boolean,
                val itag: Int?,
            )

            fun collectFormats(arr: JSONArray?): List<Candidate> {
                if (arr == null) return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optBoolean("isDrm", false)) continue
                        if (o.optJSONArray("drmFamilies") != null) continue
                        val url = o.optString("url").trim()
                        if (url.isEmpty()) continue
                        val mime = o.optString("mimeType").trim()
                        val bitrate = o.optLong("averageBitrate", 0L).takeIf { it > 0L }
                            ?: o.optLong("bitrate", 0L)
                        val audioTrack = o.optJSONObject("audioTrack")
                        val isDefault = audioTrack == null || audioTrack.optBoolean("audioIsDefault", true)
                        val itag = o.optInt("itag", 0).takeIf { it > 0 }
                        add(Candidate(url, mime, bitrate, isDefault, itag))
                    }
                }
            }

            val allAudioOnly = collectFormats(adaptive).filter { it.mime.startsWith("audio/", ignoreCase = true) }
            val defaultAudioOnly = allAudioOnly.filter { it.isDefaultAudio }.ifEmpty { allAudioOnly }
            val progressiveFormats = collectFormats(progressive)
            val hlsManifestUrl = streamingData.optString("hlsManifestUrl").trim()

            val adaptivePool = defaultAudioOnly.ifEmpty {
                progressiveFormats.ifEmpty { collectFormats(adaptive) }
            }

            val resolvedQuality = resolveEffectiveQuality(quality, isUnmeteredNetwork)
            val orderedAdaptive = if (adaptivePool.isNotEmpty()) {
                orderCandidatesOpusFirst(
                    pool = adaptivePool,
                    resolvedQuality = resolvedQuality,
                    mimeOf = { it.mime },
                    bitrateOf = { it.bitrate },
                    itagOf = { it.itag },
                )
            } else {
                emptyList()
            }

            // Append progressive muxed streams (e.g. itag=18) after adaptive audio so >1 MiB fallback is always available
            val seenUrls = orderedAdaptive.mapTo(HashSet()) { it.url }
            val extraProgressive = progressiveFormats.filter { seenUrls.add(it.url) }
            val combined = orderedAdaptive + extraProgressive

            val mapped = combined.map { chosen ->
                val expiresAtMs = extractExpireEpochMs(chosen.url)
                    ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4))
                val isOpus = isOpusMimeOrItag(chosen.mime, chosen.itag)
                val label = when {
                    isOpus -> "OPUS"
                    chosen.mime.startsWith("video/mp4", ignoreCase = true) -> "MP4"
                    else -> ""
                }
                YtStreamInfo(
                    url = chosen.url,
                    mimeType = chosen.mime,
                    bitrateBps = chosen.bitrate,
                    clientName = clientName,
                    expiresAtMs = expiresAtMs,
                    userAgent = userAgent,
                    codecLabel = label,
                    itag = chosen.itag,
                )
            }.toMutableList()

            if (hlsManifestUrl.startsWith("http://", ignoreCase = true) ||
                hlsManifestUrl.startsWith("https://", ignoreCase = true)
            ) {
                mapped.add(
                    YtStreamInfo(
                        url = hlsManifestUrl,
                        mimeType = "application/x-mpegURL",
                        bitrateBps = 128_000L,
                        clientName = clientName,
                        expiresAtMs = extractExpireEpochMs(hlsManifestUrl)
                            ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4)),
                        userAgent = userAgent,
                        codecLabel = "HLS",
                        itag = null,
                    )
                )
            }

            return mapped
        }

        internal fun selectBestAudioStream(
            playerJson: JSONObject,
            quality: YtAudioQuality,
            clientName: String,
            userAgent: String = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
            isUnmeteredNetwork: Boolean = true,
        ): YtStreamInfo? = selectOrderedAudioStreams(
            playerJson = playerJson,
            quality = quality,
            clientName = clientName,
            userAgent = userAgent,
            isUnmeteredNetwork = isUnmeteredNetwork,
        ).firstOrNull()

        internal fun parseInvidiousInstancesJson(rawJson: String): List<String> = runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONArray(i) ?: continue
                    val meta = entry.optJSONObject(1) ?: continue
                    if (!meta.optString("type").equals("https", ignoreCase = true)) continue
                    if (!meta.optBoolean("api", false)) continue
                    val uri = meta.optString("uri").trim().trimEnd('/')
                    if (uri.startsWith("https://", ignoreCase = true)) {
                        add(uri)
                    }
                }
            }.distinct()
        }.getOrDefault(emptyList())

        internal fun parsePipedInstancesJson(rawJson: String): List<String> = runCatching {
            val arr = JSONArray(rawJson)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val apiUrl = obj.optString("api_url").trim().trimEnd('/')
                    if (apiUrl.startsWith("https://", ignoreCase = true)) {
                        add(apiUrl)
                    }
                }
            }.distinct()
        }.getOrDefault(emptyList())

        internal fun selectOrderedPipedAudioStreams(
            pipedJson: JSONObject,
            quality: YtAudioQuality,
            clientName: String,
            isUnmeteredNetwork: Boolean = true,
        ): List<YtStreamInfo> {
            val arr = pipedJson.optJSONArray("audioStreams")
            data class Candidate(val url: String, val mime: String, val bitrate: Long, val itag: Int?)
            val pool = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val url = o.optString("url").trim()
                        if (url.isEmpty()) continue
                        val mime = o.optString("mimeType").trim().ifBlank { "audio/webm" }
                        val bitrate = o.optLong("bitrate", 128_000L)
                        val itag = o.optInt("itag", 0).takeIf { it > 0 }
                        add(Candidate(url, mime, bitrate, itag))
                    }
                }
            }
            val resolvedQuality = resolveEffectiveQuality(quality, isUnmeteredNetwork)
            val ordered = orderCandidatesOpusFirst(
                pool = pool,
                resolvedQuality = resolvedQuality,
                mimeOf = { it.mime },
                bitrateOf = { it.bitrate },
                itagOf = { it.itag },
            ).map { chosen ->
                YtStreamInfo(
                    url = chosen.url,
                    mimeType = chosen.mime,
                    bitrateBps = chosen.bitrate,
                    clientName = clientName,
                    expiresAtMs = extractExpireEpochMs(chosen.url)
                        ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)),
                    userAgent = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
                    codecLabel = if (isOpusMimeOrItag(chosen.mime, chosen.itag)) "OPUS" else "",
                    itag = chosen.itag,
                )
            }.toMutableList()

            val hlsUrl = pipedJson.optString("hls").trim()
            if (hlsUrl.startsWith("https://", ignoreCase = true) || hlsUrl.startsWith("http://", ignoreCase = true)) {
                ordered.add(
                    YtStreamInfo(
                        url = hlsUrl,
                        mimeType = "application/x-mpegURL",
                        bitrateBps = 128_000L,
                        clientName = clientName,
                        expiresAtMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2),
                        userAgent = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
                        codecLabel = "HLS",
                        itag = null,
                    )
                )
            }
            return ordered
        }

        internal fun selectBestPipedAudioStream(
            pipedJson: JSONObject,
            quality: YtAudioQuality,
            clientName: String,
            isUnmeteredNetwork: Boolean = true,
        ): YtStreamInfo? = selectOrderedPipedAudioStreams(
            pipedJson = pipedJson,
            quality = quality,
            clientName = clientName,
            isUnmeteredNetwork = isUnmeteredNetwork,
        ).firstOrNull()

        internal fun selectOrderedInvidiousAudioStreams(
            invidiousJson: JSONObject,
            instanceBaseUrl: String,
            videoId: String,
            quality: YtAudioQuality,
            isUnmeteredNetwork: Boolean = true,
        ): List<YtStreamInfo> {
            val cleanBase = instanceBaseUrl.trimEnd('/')
            data class Candidate(val url: String, val mime: String, val bitrate: Long, val itag: Int?, val proxied: Boolean)

            fun collect(arr: JSONArray?, audioOnly: Boolean): List<Candidate> {
                if (arr == null) return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val type = o.optString("type").trim()
                        if (audioOnly && !type.startsWith("audio/", ignoreCase = true)) continue
                        val itagStr = o.optString("itag").trim()
                        val itagInt = itagStr.toIntOrNull()
                        val rawUrl = o.optString("url").trim()
                        val bitrate = o.optString("bitrate").toLongOrNull()
                            ?: o.optLong("bitrate", 128_000L)
                        // Add instance-proxied local=true first (bypasses datacenter IP blocks on googlevideo.com)
                        if (itagStr.isNotEmpty()) {
                            add(
                                Candidate(
                                    url = "$cleanBase/latest_version?id=$videoId&itag=$itagStr&local=true",
                                    mime = type.ifBlank { "audio/webm" },
                                    bitrate = bitrate,
                                    itag = itagInt,
                                    proxied = true,
                                )
                            )
                        }
                        // Also add direct rawUrl fallback if instance disables local=true proxying
                        if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                            add(
                                Candidate(
                                    url = rawUrl,
                                    mime = type.ifBlank { "audio/webm" },
                                    bitrate = bitrate,
                                    itag = itagInt,
                                    proxied = false,
                                )
                            )
                        }
                    }
                }
            }

            val adaptive = collect(invidiousJson.optJSONArray("adaptiveFormats"), audioOnly = true)
            val progressive = collect(invidiousJson.optJSONArray("formatStreams"), audioOnly = false)
            val resolvedQuality = resolveEffectiveQuality(quality, isUnmeteredNetwork)

            val (proxiedAdaptive, directAdaptive) = adaptive.partition { it.proxied }
            val orderedProxied = orderCandidatesOpusFirst(
                pool = proxiedAdaptive,
                resolvedQuality = resolvedQuality,
                mimeOf = { it.mime },
                bitrateOf = { it.bitrate },
                itagOf = { it.itag },
            )
            val orderedDirect = orderCandidatesOpusFirst(
                pool = directAdaptive,
                resolvedQuality = resolvedQuality,
                mimeOf = { it.mime },
                bitrateOf = { it.bitrate },
                itagOf = { it.itag },
            )
            val combined = (orderedProxied + progressive.filter { it.proxied } + orderedDirect + progressive.filterNot { it.proxied })
                .distinctBy { it.url }

            val out = combined.map { chosen ->
                val isOpus = isOpusMimeOrItag(chosen.mime, chosen.itag)
                YtStreamInfo(
                    url = chosen.url,
                    mimeType = chosen.mime,
                    bitrateBps = chosen.bitrate,
                    clientName = "Invidious",
                    expiresAtMs = extractExpireEpochMs(chosen.url)
                        ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)),
                    userAgent = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
                    codecLabel = when {
                        isOpus -> "OPUS"
                        chosen.mime.startsWith("video/mp4", ignoreCase = true) -> "MP4"
                        else -> ""
                    },
                    itag = chosen.itag,
                )
            }.toMutableList()

            val hlsUrl = invidiousJson.optString("hlsUrl").trim()
            if (hlsUrl.startsWith("https://", ignoreCase = true) || hlsUrl.startsWith("http://", ignoreCase = true)) {
                out.add(
                    YtStreamInfo(
                        url = hlsUrl,
                        mimeType = "application/x-mpegURL",
                        bitrateBps = 128_000L,
                        clientName = "Invidious",
                        expiresAtMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2),
                        userAgent = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
                        codecLabel = "HLS",
                        itag = null,
                    )
                )
            }
            return out
        }

        internal fun selectBestInvidiousAudioStream(
            invidiousJson: JSONObject,
            instanceBaseUrl: String,
            videoId: String,
            quality: YtAudioQuality,
            isUnmeteredNetwork: Boolean = true,
        ): YtStreamInfo? = selectOrderedInvidiousAudioStreams(
            invidiousJson = invidiousJson,
            instanceBaseUrl = instanceBaseUrl,
            videoId = videoId,
            quality = quality,
            isUnmeteredNetwork = isUnmeteredNetwork,
        ).firstOrNull()

        internal fun extractExpireEpochMs(url: String): Long? {
            val match = Regex("[?&]expire=(\\d+)").find(url) ?: return null
            val seconds = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
            return seconds * 1000L
        }

        internal fun parseTracksFromJsonTree(root: JSONObject, limit: Int = 30): List<YtTrack> {
            val out = ArrayList<YtTrack>(limit)
            val seenIds = HashSet<String>()

            fun walk(node: Any?) {
                if (out.size >= limit || node == null) return
                when (node) {
                    is JSONObject -> {
                        node.optJSONObject("musicResponsiveListItemRenderer")?.let { r ->
                            parseResponsiveItem(r)?.let { track ->
                                if (seenIds.add(track.videoId)) out.add(track)
                            }
                        }
                        node.optJSONObject("musicTwoRowItemRenderer")?.let { r ->
                            parseTwoRowItem(r)?.let { track ->
                                if (seenIds.add(track.videoId)) out.add(track)
                            }
                        }
                        node.optJSONObject("playlistPanelVideoRenderer")?.let { r ->
                            parsePlaylistPanelItem(r)?.let { track ->
                                if (seenIds.add(track.videoId)) out.add(track)
                            }
                        }
                        val keys = node.keys()
                        while (keys.hasNext() && out.size < limit) {
                            val k = keys.next()
                            if (k == "musicResponsiveListItemRenderer" ||
                                k == "musicTwoRowItemRenderer" ||
                                k == "playlistPanelVideoRenderer"
                            ) continue
                            walk(node.opt(k))
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            if (out.size >= limit) break
                            walk(node.opt(i))
                        }
                    }
                }
            }

            walk(root)
            return out
        }

        private fun parseResponsiveItem(renderer: JSONObject): YtTrack? {
            val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
                ?: renderer.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
                ?: renderer.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
                ?: return null

            val columns = renderer.optJSONArray("flexColumns") ?: return null
            val col0Runs = extractFlexColumnRuns(columns.optJSONObject(0))
            val title = col0Runs.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null

            val col1Runs = extractFlexColumnRuns(columns.optJSONObject(1))
            val durationFromFixed = renderer.optJSONArray("fixedColumns")
                ?.optJSONObject(0)
                ?.let { extractFlexColumnRuns(it).firstOrNull() }
                .orEmpty()

            val cleanedMeta = col1Runs
                .map { it.trim() }
                .filter { it.isNotBlank() && it != "•" && it != "·" }
                .filterNot { it.equals("Song", true) || it.equals("Video", true) || it.equals("EP", true) || it.equals("Album", true) }

            val durationRegex = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
            val durationText = durationFromFixed.takeIf { durationRegex.matches(it) }
                ?: cleanedMeta.lastOrNull { durationRegex.matches(it) }.orEmpty()

            val nonDurationMeta = cleanedMeta.filterNot { durationRegex.matches(it) }
            val artist = nonDurationMeta.firstOrNull().orEmpty().ifBlank { "Discover" }
            val album = nonDurationMeta.getOrNull(1).orEmpty()

            val thumb = extractHighestResThumbnail(
                renderer.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail"),
                videoId,
            )

            return YtTrack(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationText = durationText,
                thumbnailUrl = thumb,
            )
        }

        private fun parseTwoRowItem(renderer: JSONObject): YtTrack? {
            val videoId = renderer.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val title = extractRunsText(renderer.optJSONObject("title")).ifBlank { return null }
            val subtitle = extractRunsText(renderer.optJSONObject("subtitle"))
                .split("•")
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("Song", true) && !it.equals("Video", true) }
            val artist = subtitle.firstOrNull().orEmpty().ifBlank { "Discover" }
            val thumb = extractHighestResThumbnail(
                renderer.optJSONObject("thumbnailRenderer")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail"),
                videoId,
            )
            return YtTrack(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumb,
            )
        }

        private fun parsePlaylistPanelItem(renderer: JSONObject): YtTrack? {
            val videoId = renderer.optString("videoId").takeIf { it.isNotBlank() } ?: return null
            val title = extractRunsText(renderer.optJSONObject("title")).ifBlank { return null }
            val artist = extractRunsText(renderer.optJSONObject("shortBylineText"))
                .ifBlank { extractRunsText(renderer.optJSONObject("longBylineText")) }
                .substringBefore("•")
                .trim()
                .ifBlank { "Discover" }
            val durationText = extractRunsText(renderer.optJSONObject("lengthText"))
            val thumb = extractHighestResThumbnail(renderer.optJSONObject("thumbnail"), videoId)
            return YtTrack(
                videoId = videoId,
                title = title,
                artist = artist,
                durationText = durationText,
                thumbnailUrl = thumb,
            )
        }

        private fun extractFlexColumnRuns(col: JSONObject?): List<String> {
            val runs = col?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?: return emptyList()
            return buildList {
                for (i in 0 until runs.length()) {
                    runs.optJSONObject(i)?.optString("text")?.let(::add)
                }
            }
        }

        private fun extractRunsText(obj: JSONObject?): String {
            if (obj == null) return ""
            val runs = obj.optJSONArray("runs") ?: return obj.optString("simpleText")
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
            return sb.toString().trim()
        }

        private fun extractHighestResThumbnail(thumbnailObj: JSONObject?, videoId: String): String {
            val arr = thumbnailObj?.optJSONArray("thumbnails")
            val raw = if (arr != null && arr.length() > 0) {
                arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty()
            } else ""
            if (raw.isBlank()) return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            return if (raw.contains("lh3.googleusercontent.com")) {
                raw.replace(Regex("=w\\d+-h\\d+[^\"&]*"), "=w544-h544-l90-rj")
            } else {
                raw
            }
        }

        internal fun parseSuggestionsFromJsonTree(root: JSONObject): List<String> {
            val suggestions = LinkedHashSet<String>()
            fun walk(node: Any?) {
                if (suggestions.size >= 8 || node == null) return
                when (node) {
                    is JSONObject -> {
                        node.optJSONObject("searchSuggestionRenderer")?.let { s ->
                            val text = extractRunsText(s.optJSONObject("suggestion"))
                            if (text.isNotBlank()) suggestions.add(text)
                        }
                        val keys = node.keys()
                        while (keys.hasNext() && suggestions.size < 8) {
                            walk(node.opt(keys.next()))
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            if (suggestions.size >= 8) break
                            walk(node.opt(i))
                        }
                    }
                }
            }
            walk(root)
            return suggestions.toList()
        }

        private fun parseLrclibItem(obj: JSONObject): TrackLyrics? {
            val syncedRaw = obj.optString("syncedLyrics").trim()
            if (syncedRaw.isNotEmpty() && syncedRaw != "null") {
                val parsed = parseLrcLines(syncedRaw)
                if (parsed.isNotEmpty()) {
                    return TrackLyrics(lines = parsed, isSynced = true, source = "LRCLIB Synced")
                }
            }
            val plainRaw = obj.optString("plainLyrics").trim()
            if (plainRaw.isNotEmpty() && plainRaw != "null") {
                val plainLines = plainRaw.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapIndexed { idx, line -> SyncedLyricLine(timeMs = idx.toLong(), text = line) }
                if (plainLines.isNotEmpty()) {
                    return TrackLyrics(lines = plainLines, isSynced = false, source = "LRCLIB")
                }
            }
            return null
        }

        /**
         * Parses raw embedded tag lyrics (FLAC VORBIS_COMMENT / ID3v2 USLT / MP4 ©lyr) or sidecar .lrc text.
         */
        fun parseRawLyricsText(rawText: String, sourceLabel: String): TrackLyrics? {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty()) return null
            val synced = parseLrcLines(trimmed)
            if (synced.isNotEmpty()) {
                return TrackLyrics(lines = synced, isSynced = true, source = "$sourceLabel Synced")
            }
            val plain = trimmed.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("[ti:") && !it.startsWith("[ar:") && !it.startsWith("[al:") && !it.startsWith("[by:") }
                .mapIndexed { idx, line -> SyncedLyricLine(timeMs = idx.toLong(), text = line) }
            if (plain.isEmpty()) return null
            return TrackLyrics(lines = plain, isSynced = false, source = sourceLabel)
        }

        /**
         * BitChord-style candidate ranker for LRCLIB search arrays: scores every candidate by
         * synced LRC availability, title similarity, primary artist overlap, album match,
         * and duration proximity rather than blindly taking index 0.
         */
        internal fun selectBestLrclibCandidate(
            candidates: List<JSONObject>,
            targetTitle: String,
            targetArtist: String,
            targetAlbum: String = "",
            targetDurationSec: Int? = null,
        ): TrackLyrics? {
            val normTitle = normalizeLyricsMatchText(targetTitle)
            val normArtist = normalizeLyricsMatchText(targetArtist)
            val normAlbum = normalizeLyricsMatchText(targetAlbum)
            val titleTokens = normTitle.split(' ').filter { it.length >= 2 }.toSet()
            val artistTokens = normArtist.split(' ').filter { it.length >= 2 }.toSet()

            var bestLyrics: TrackLyrics? = null
            var bestScore = Double.NEGATIVE_INFINITY

            for (obj in candidates) {
                val parsed = parseLrclibItem(obj) ?: continue
                val candTitle = normalizeLyricsMatchText(obj.optString("trackName").ifBlank { obj.optString("name") })
                val candArtist = normalizeLyricsMatchText(obj.optString("artistName"))
                val candAlbum = normalizeLyricsMatchText(obj.optString("albumName"))
                val candDurationSec = obj.optDouble("duration", -1.0).takeIf { it > 0.0 }?.toInt()

                var score = 0.0
                if (parsed.isSynced) score += 30.0

                // Title similarity
                if (normTitle.isNotEmpty() && candTitle.isNotEmpty()) {
                    when {
                        candTitle == normTitle -> score += 45.0
                        candTitle.contains(normTitle) || normTitle.contains(candTitle) -> score += 30.0
                        else -> {
                            val candTokens = candTitle.split(' ').filter { it.length >= 2 }.toSet()
                            val inter = titleTokens.intersect(candTokens).size
                            val union = titleTokens.union(candTokens).size
                            if (union > 0) score += (inter.toDouble() / union) * 35.0
                        }
                    }
                }

                // Artist similarity
                if (normArtist.isNotEmpty() && candArtist.isNotEmpty()) {
                    when {
                        candArtist == normArtist -> score += 32.0
                        candArtist.contains(normArtist) || normArtist.contains(candArtist) -> score += 24.0
                        else -> {
                            val candArtTokens = candArtist.split(' ').filter { it.length >= 2 }.toSet()
                            val hits = artistTokens.count { it in candArtTokens }
                            if (artistTokens.isNotEmpty() && hits > 0) {
                                score += (hits.toDouble() / artistTokens.size) * 26.0
                            } else {
                                score -= 18.0
                            }
                        }
                    }
                }

                // Album match bonus
                if (normAlbum.isNotEmpty() && candAlbum.isNotEmpty() &&
                    (candAlbum == normAlbum || candAlbum.contains(normAlbum) || normAlbum.contains(candAlbum))
                ) {
                    score += 10.0
                }

                // Duration proximity (separates Studio vs Radio Edit vs Live vs Acoustic)
                if (targetDurationSec != null && candDurationSec != null) {
                    val diff = abs(targetDurationSec - candDurationSec)
                    score += when {
                        diff <= 3 -> 25.0
                        diff <= 8 -> 14.0
                        diff <= 16 -> 5.0
                        diff > 35 -> -25.0
                        else -> -6.0
                    }
                }

                if (score > bestScore) {
                    bestScore = score
                    bestLyrics = parsed
                }
            }

            return if (bestScore >= 15.0) bestLyrics else null
        }

        internal fun parseDurationTextToMs(durationText: String?): Long {
            val raw = durationText?.trim().orEmpty()
            if (raw.isEmpty()) return 0L
            val parts = raw.split(':').mapNotNull { it.trim().toLongOrNull() }
            return when (parts.size) {
                2 -> (parts[0] * 60L + parts[1]) * 1000L
                3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
                else -> 0L
            }
        }

        internal fun parseLrcLines(lrc: String): List<SyncedLyricLine> {
            val regex = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)")
            return lrc.lines()
                .mapNotNull { line ->
                    val match = regex.find(line.trim()) ?: return@mapNotNull null
                    val mins = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val secs = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val fracStr = match.groupValues[3]
                    val millis = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    val text = match.groupValues[4].trim()
                    if (text.isBlank()) return@mapNotNull null
                    SyncedLyricLine(
                        timeMs = (mins * 60_000L) + (secs * 1_000L) + millis,
                        text = text,
                    )
                }
                .sortedBy { it.timeMs }
        }

        internal fun extractPrimaryArtist(artist: String): String {
            if (artist.isBlank()) return ""
            return artist
                .split(Regex("\\s*(?:,|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bx\\b|×|;|/)\\s*", RegexOption.IGNORE_CASE))
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
        }

        private fun normalizeLyricsMatchText(raw: String): String =
            cleanSearchTerm(raw)
                .lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        internal fun cleanSearchTerm(raw: String): String =
            raw.replace(Regex("\\.(flac|alac|wav|aiff|aif|mp3|m4a|opus|ogg|wv|ape|dsf|dff)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^\\s*(?:\\d{1,3}\\s*[-._)]\\s*|\\d{2}\\s+)"), "")
                .replace(Regex("\\([^)]*(?:official|audio|video|lyrics?|visualizer|remaster(?:ed)?|hd|hq|4k|mv|explicit|clean|deluxe|bonus|flac|lossless|24-?bit|16-?bit|feat\\.?|ft\\.?)[^)]*\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\[[^\\]]*(?:official|audio|video|lyrics?|visualizer|remaster(?:ed)?|hd|hq|4k|mv|explicit|clean|deluxe|bonus|flac|lossless|24-?bit|16-?bit|feat\\.?|ft\\.?)[^\\]]*\\]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE), "")
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
