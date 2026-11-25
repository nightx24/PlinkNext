package io.github.augztn.pocketlink.ssdbridge

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.data.store.AppLogger
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Shared ExoPlayer factory tuning audio sink, async MediaCodec queueing,
 * constant-bitrate seekability, and streaming load control for both
 * background audio playback and the video player.
 */
object PocketLinkMediaEngine {
    @OptIn(UnstableApi::class)
    fun buildExoPlayer(
        context: Context,
        dataSourceFactory: DataSource.Factory,
        contentType: Int,
        handleWakeMode: Boolean = false,
    ): ExoPlayer {
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
            .setEnableAudioFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(true)
            .forceEnableMediaCodecAsynchronousQueueing()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_200,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(contentType)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .apply {
                if (handleWakeMode) {
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
            }
            .build()
    }
}

/**
 * Media3 MediaSessionService hosting audio ExoPlayer in the foreground/background,
 * exposing system media notifications, lock screen transport controls, and headset
 * media button events cleanly with single-owner AudioFocus.
 */
@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            MediaPlaybackBridge.isPlaying = isPlaying
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                MediaPlaybackBridge.isPlaying = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val hlsSwitch = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HlsSwitchException>()
                .firstOrNull()
            if (hlsSwitch != null) {
                val player = exoPlayer
                if (player != null) {
                    val idx = player.currentMediaItemIndex
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    if (idx in 0 until player.mediaItemCount) {
                        val currentItem = player.getMediaItemAt(idx)
                        val hlsItem = currentItem.buildUpon()
                            .setUri(Uri.parse(hlsSwitch.stream.url))
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build()
                        AppLogger.info(
                            "Switching Discover track to HLS",
                            "${hlsSwitch.videoId} via ${hlsSwitch.stream.sourceName} @ ${pos}ms"
                        )
                        player.replaceMediaItem(idx, hlsItem)
                        player.seekTo(idx, pos)
                        player.prepare()
                        player.play()
                        return
                    }
                }
            }
            MediaPlaybackBridge.isPlaying = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        MediaPlaybackBridge.isServiceRunning = true

        val player = PocketLinkMediaEngine.buildExoPlayer(
            context = this,
            dataSourceFactory = MediaPlaybackBridge.dynamicDataSourceFactory(this),
            contentType = C.AUDIO_CONTENT_TYPE_MUSIC,
            handleWakeMode = true,
        ).also { created ->
            created.skipSilenceEnabled = MediaPlaybackBridge.skipSilenceEnabled
            created.addListener(playerListener)
        }
        exoPlayer = player
        MediaPlaybackBridge.skipSilenceListener = { enabled ->
            player.skipSilenceEnabled = enabled
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val sessionActivity = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = MediaSession.Builder(CarConnectionSafeContextWrapper(this), player)
        if (sessionActivity != null) {
            builder.setSessionActivity(sessionActivity)
        }
        mediaSession = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            pauseAllPlayersAndStopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MediaPlaybackBridge.isServiceRunning = false
        MediaPlaybackBridge.isPlaying = false
        MediaPlaybackBridge.skipSilenceListener = null
        mediaSession?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }
}

/**
 * Prevents Media3's AndroidAutoConnectionStateObserver (API 33+) from querying the
 * `content://androidx.car.app.connection` provider when Android Auto is not installed,
 * which otherwise triggers `E/ActivityThread: Failed to find provider info for androidx.car.app.connection`.
 * When the provider is present, or for any other ContentResolver caller, delegates directly to the system resolver.
 */
private class CarConnectionSafeContextWrapper(
    base: Context,
) : ContextWrapper(base.applicationContext) {

    private val emptyCarConnectionResolver: ContentResolver? by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@lazy null
        runCatching {
            val provider = EmptyCarConnectionProvider()
            val info = ProviderInfo().apply {
                authority = CAR_CONNECTION_AUTHORITY
                packageName = baseContext.packageName
                name = EmptyCarConnectionProvider::class.java.name
                applicationInfo = baseContext.applicationInfo
                exported = true
            }
            provider.attachInfo(baseContext, info)
            ContentResolver.wrap(provider)
        }.getOrNull()
    }

    override fun getApplicationContext(): Context = this

    override fun getContentResolver(): ContentResolver {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            isCarConnectionObserverCall() &&
            !hasCarConnectionProvider()
        ) {
            emptyCarConnectionResolver?.let { return it }
        }
        return super.getContentResolver()
    }

    private fun hasCarConnectionProvider(): Boolean = runCatching {
        val providerInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveContentProvider(
                CAR_CONNECTION_AUTHORITY,
                PackageManager.ComponentInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveContentProvider(CAR_CONNECTION_AUTHORITY, 0)
        }
        providerInfo != null
    }.getOrDefault(false)

    private fun isCarConnectionObserverCall(): Boolean {
        val frames = Thread.currentThread().stackTrace
        for (i in 2 until frames.size) {
            if (frames[i].className == CAR_CONNECTION_OBSERVER_CLASS) {
                return true
            }
        }
        return false
    }

    private class EmptyCarConnectionProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    companion object {
        private const val CAR_CONNECTION_AUTHORITY = "androidx.car.app.connection"
        private const val CAR_CONNECTION_OBSERVER_CLASS =
            "androidx.media3.session.AndroidAutoConnectionStateObserver"
    }
}

internal data class ResolvedExternalStream(
    val url: String,
    val userAgent: String,
    val headers: Map<String, String> = emptyMap(),
    val badgeLabel: String = "",
    val sourceName: String = "NewPipe",
    val useQueryRange: Boolean = false,
    val isHls: Boolean = false,
    val expiresAtMs: Long = 0L,
)

internal class HlsSwitchException(
    val videoId: String,
    val stream: ResolvedExternalStream,
) : IOException("HLS_HANDOFF:$videoId")

/**
 * BitChord / InnerTune / NewPipe-style bounded-range chunked HTTP DataSource for googlevideo.com.
 *
 * Executes direct OkHttp bounded `Range: bytes=start-end` requests (512 KB chunks) with a single
 * clean `User-Agent` header (identical to `YtMusicClient.probeStreamUrl`), plus automatic:
 * 1) Transport mode fallback between YouTube native `&range=` query mode and HTTP `Range:` header.
 * 2) Proactive token refresh when the `expire=` timestamp in the URL approaches expiration.
 * 3) Mid-stream client rotation & token refresh (up to [MAX_CLIENT_ROTATIONS_PER_CHUNK] alternate
 *    InnerTube / NewPipe clients) whenever `googlevideo.com` returns HTTP 403/401/410 on a chunk,
 *    preserving the exact `currentPos` byte offset so playback continues without interruption.
 */
@OptIn(UnstableApi::class)
internal class ChunkedYoutubeDataSource(
    private val client: OkHttpClient,
    private val userAgent: String?,
    private val initialUseQueryRange: Boolean = false,
    private val videoId: String? = null,
    private val initialExpiresAtMs: Long = 0L,
    private val streamRefresher: ((String) -> ResolvedExternalStream?)? = null,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var baseDataSpec: DataSpec? = null
    private var resolvedBaseUrl: String? = null
    private var activeResponse: Response? = null
    private var activeStream: InputStream? = null
    private var activeUri: Uri? = null
    private var opened: Boolean = false
    private var currentPos: Long = 0L
    private var totalLength: Long = C.LENGTH_UNSET.toLong()
    private var requestedEndExclusive: Long = C.LENGTH_UNSET.toLong()
    private var lastResponseHeaders: Map<String, List<String>> = emptyMap()
    private var preferQueryRange: Boolean = initialUseQueryRange
    private var activeUserAgent: String? = userAgent
    private var activeExtraHeaders: Map<String, String> = emptyMap()
    private var activeExpiresAtMs: Long = initialExpiresAtMs
    private var chunkRequestSeq: Int = 0

    override fun addTransferListener(transferListener: TransferListener) {
        if (transferListener !in listeners) {
            listeners.add(transferListener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        closeCurrentChunk()
        baseDataSpec = dataSpec
        val rawUrl = dataSpec.uri.toString()
        resolvedBaseUrl = stripRangeQueryParams(rawUrl)
        activeUri = dataSpec.uri
        currentPos = dataSpec.position.coerceAtLeast(0L)
        preferQueryRange = initialUseQueryRange
        activeUserAgent = userAgent
        activeExtraHeaders = dataSpec.httpRequestHeaders.filterKeys { key ->
            !key.equals("Range", ignoreCase = true) &&
                !key.equals("User-Agent", ignoreCase = true) &&
                !key.equals("Accept-Encoding", ignoreCase = true) &&
                !key.equals(HEADER_USE_QUERY_RANGE, ignoreCase = true) &&
                !key.equals(HEADER_VIDEO_ID, ignoreCase = true)
        }
        activeExpiresAtMs = initialExpiresAtMs.takeIf { it > 0L }
            ?: extractExpireEpochMsFromUrl(rawUrl)
            ?: 0L
        chunkRequestSeq = 0
        // Extract authoritative total byte length from YouTube's `clen=` query param when present
        totalLength = extractClenFromUrl(rawUrl) ?: C.LENGTH_UNSET.toLong()
        requestedEndExclusive = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            currentPos + dataSpec.length
        } else {
            C.LENGTH_UNSET.toLong()
        }

        listeners.forEach { it.onTransferInitializing(this, dataSpec, /* isNetwork = */ true) }
        openNextChunk(isInitialChunk = true)
        opened = true
        listeners.forEach { it.onTransferStart(this, dataSpec, /* isNetwork = */ true) }

        return when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            totalLength != C.LENGTH_UNSET.toLong() -> (totalLength - currentPos).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    private fun refreshTokenAndRotateClient(reason: String): Boolean {
        val vid = videoId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val refresher = streamRefresher ?: return false
        return runCatching {
            val fresh = refresher(vid) ?: return false
            if (fresh.isHls) {
                MediaPlaybackBridge.latestHlsUserAgent = fresh.userAgent
                throw HlsSwitchException(vid, fresh)
            }
            resolvedBaseUrl = stripRangeQueryParams(fresh.url)
            activeUserAgent = fresh.userAgent.takeIf { it.isNotBlank() } ?: activeUserAgent
            if (fresh.headers.isNotEmpty()) {
                activeExtraHeaders = fresh.headers
            }
            preferQueryRange = fresh.useQueryRange
            activeExpiresAtMs = fresh.expiresAtMs.takeIf { it > 0L }
                ?: extractExpireEpochMsFromUrl(fresh.url)
                ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4))
            chunkRequestSeq = 0
            // Re-sync totalLength if the rotated client selected a slightly different representation
            extractClenFromUrl(fresh.url)?.let { clen ->
                if (totalLength == C.LENGTH_UNSET.toLong() || currentPos < clen) {
                    totalLength = clen
                }
            }
            AppLogger.info(
                "Rotated Discover client mid-stream ($reason)",
                "$vid -> ${fresh.sourceName} (${fresh.badgeLabel}) @ pos=${currentPos}B"
            )
            true
        }.onFailure { err ->
            if (err is HlsSwitchException) throw err
            AppLogger.warning("Discover mid-stream client rotation failed ($vid)", err.message ?: err.javaClass.simpleName)
        }.getOrDefault(false)
    }

    private fun executeChunkRequest(
        spec: DataSpec,
        baseUrl: String,
        rangeStart: Long,
        rangeEnd: Long,
        useQueryParamRange: Boolean,
    ): Response {
        val seq = ++chunkRequestSeq
        val targetUrl = if (useQueryParamRange) {
            buildQueryRangeUrl(baseUrl, rangeStart, rangeEnd, seq)
        } else {
            baseUrl
        }
        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .get()
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")

        if (!useQueryParamRange) {
            reqBuilder.header("Range", "bytes=$rangeStart-$rangeEnd")
        }

        // Apply extra headers except internal/Range/User-Agent (single authoritative User-Agent below)
        activeExtraHeaders.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true) &&
                !key.equals("User-Agent", ignoreCase = true) &&
                !key.equals("Accept-Encoding", ignoreCase = true) &&
                !key.equals(HEADER_USE_QUERY_RANGE, ignoreCase = true) &&
                !key.equals(HEADER_VIDEO_ID, ignoreCase = true) &&
                value.isNotBlank()
            ) {
                reqBuilder.header(key, value)
            }
        }
        val resolvedUa = activeUserAgent?.takeIf { it.isNotBlank() }
            ?: spec.httpRequestHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }
        if (resolvedUa != null) {
            reqBuilder.header("User-Agent", resolvedUa)
        }

        return try {
            client.newCall(reqBuilder.build()).execute()
        } catch (io: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                io,
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
    }

    private fun openNextChunk(isInitialChunk: Boolean = false): Boolean {
        closeCurrentChunk()
        val spec = baseDataSpec ?: return false

        // Proactively refresh the stream token before expiry so long tracks or paused sessions never hit 403
        val now = System.currentTimeMillis()
        if (activeExpiresAtMs in 1L..(now + TOKEN_EXPIRY_SAFETY_WINDOW_MS)) {
            refreshTokenAndRotateClient("token expiring")
        }

        var baseUrl = resolvedBaseUrl ?: stripRangeQueryParams(spec.uri.toString())
        if (totalLength != C.LENGTH_UNSET.toLong() && currentPos >= totalLength) return false
        if (requestedEndExclusive != C.LENGTH_UNSET.toLong() && currentPos >= requestedEndExclusive) return false

        fun computeRangeEnd(): Long {
            var chunkLen = CHUNK_SIZE_BYTES
            if (totalLength != C.LENGTH_UNSET.toLong()) {
                chunkLen = minOf(chunkLen, totalLength - currentPos)
            }
            if (requestedEndExclusive != C.LENGTH_UNSET.toLong()) {
                chunkLen = minOf(chunkLen, requestedEndExclusive - currentPos)
            }
            return if (chunkLen <= 0L) -1L else (currentPos + chunkLen - 1L)
        }

        var rangeEnd = computeRangeEnd()
        if (rangeEnd < currentPos) return false

        var usedQueryMode = preferQueryRange
        var response = executeChunkRequest(spec, baseUrl, currentPos, rangeEnd, usedQueryMode)

        // Step 1: Automatic fallback between YouTube native `&range=` query mode and HTTP `Range:` header
        if (response.code == 403) {
            response.close()
            val alternateMode = !usedQueryMode
            val retryResponse = executeChunkRequest(spec, baseUrl, currentPos, rangeEnd, alternateMode)
            if (retryResponse.code in 200..299) {
                preferQueryRange = alternateMode
                usedQueryMode = alternateMode
                AppLogger.info(
                    "Discover switched range transport",
                    "mode=${if (alternateMode) "query(&range=)" else "header(Range:)"} pos=$currentPos-$rangeEnd"
                )
            }
            response = retryResponse
        }

        // Step 2: If still 403 / 401 / 410, invalidate the current client/token and rotate through
        // alternate InnerTube / NewPipe clients in place without failing ExoPlayer playback.
        var rotationAttempt = 0
        while ((response.code == 403 || response.code == 401 || response.code == 410) &&
            rotationAttempt < MAX_CLIENT_ROTATIONS_PER_CHUNK
        ) {
            val failedCode = response.code
            response.close()
            rotationAttempt++
            if (!refreshTokenAndRotateClient("HTTP $failedCode attempt #$rotationAttempt")) {
                break
            }
            baseUrl = resolvedBaseUrl ?: baseUrl
            rangeEnd = computeRangeEnd()
            if (rangeEnd < currentPos) return false
            usedQueryMode = preferQueryRange
            response = executeChunkRequest(spec, baseUrl, currentPos, rangeEnd, usedQueryMode)
            if (response.code == 403) {
                response.close()
                val alternateMode = !usedQueryMode
                val altResponse = executeChunkRequest(spec, baseUrl, currentPos, rangeEnd, alternateMode)
                if (altResponse.code in 200..299) {
                    preferQueryRange = alternateMode
                    usedQueryMode = alternateMode
                }
                response = altResponse
            }
        }

        val code = response.code
        val headersMap = response.headers.toMultimap()
        lastResponseHeaders = headersMap
        val finalUrlStr = response.request.url.toString()
        activeUri = Uri.parse(finalUrlStr)

        // HTTP 416 Range Not Satisfiable on a subsequent chunk means we reached exact EOF
        if (code == 416 && !isInitialChunk) {
            response.close()
            totalLength = currentPos
            return false
        }

        if (code !in 200..299) {
            val host = spec.uri.host.orEmpty()
            AppLogger.warning(
                "Chunked Discover HTTP $code",
                "host=$host mode=${if (usedQueryMode) "query" else "header"} pos=$currentPos-$rangeEnd rotations=$rotationAttempt contentType=${response.header("Content-Type").orEmpty()}"
            )
            response.close()
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                response.message,
                null,
                headersMap,
                spec,
                ByteArray(0),
            )
        }

        val body = response.body
        if (body == null) {
            response.close()
            throw HttpDataSource.HttpDataSourceException(
                "Empty response body from googlevideo",
                spec,
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        // Cache redirected googlevideo edge host (without range params) to avoid redirect latency on next chunks
        resolvedBaseUrl = stripRangeQueryParams(finalUrlStr)

        activeResponse = response
        activeStream = body.byteStream()

        // Learn total stream length from Content-Range, clen, or short final chunk
        val contentRange = response.header("Content-Range")
        val parsedTotal = parseContentRangeTotal(contentRange)
        val contentLength = body.contentLength()
        val expectedChunkLen = (rangeEnd - currentPos + 1L).coerceAtLeast(1L)
        when {
            parsedTotal != null && parsedTotal > 0L -> totalLength = parsedTotal
            !usedQueryMode && code == 200 && contentLength > 0L -> totalLength = currentPos + contentLength
            contentLength in 0L until expectedChunkLen -> totalLength = currentPos + contentLength
        }
        return true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val stream = activeStream ?: return C.RESULT_END_OF_INPUT
            val read = try {
                stream.read(buffer, offset, length)
            } catch (io: IOException) {
                // If an active chunk socket drops mid-read, try rotating/re-opening at currentPos once
                if (refreshTokenAndRotateClient("mid-chunk IO reset") && openNextChunk(isInitialChunk = false)) {
                    continue
                }
                val spec = baseDataSpec
                if (spec != null) {
                    throw HttpDataSource.HttpDataSourceException.createForIOException(
                        io,
                        spec,
                        HttpDataSource.HttpDataSourceException.TYPE_READ,
                    )
                }
                throw io
            }
            if (read != -1) {
                if (read > 0) {
                    currentPos += read.toLong()
                    baseDataSpec?.let { spec ->
                        listeners.forEach { it.onBytesTransferred(this, spec, /* isNetwork = */ true, read) }
                    }
                }
                return read
            }
            // Current chunk finished — advance to next bounded chunk if stream continues
            val hasMore = (totalLength == C.LENGTH_UNSET.toLong() || currentPos < totalLength) &&
                (requestedEndExclusive == C.LENGTH_UNSET.toLong() || currentPos < requestedEndExclusive)
            if (!hasMore || !openNextChunk(isInitialChunk = false)) {
                return C.RESULT_END_OF_INPUT
            }
        }
    }

    override fun getUri(): Uri? = activeUri ?: baseDataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = lastResponseHeaders

    override fun close() {
        val spec = baseDataSpec
        val wasOpened = opened
        try {
            closeCurrentChunk()
        } finally {
            opened = false
            baseDataSpec = null
            resolvedBaseUrl = null
            activeUri = null
            totalLength = C.LENGTH_UNSET.toLong()
            requestedEndExclusive = C.LENGTH_UNSET.toLong()
            if (wasOpened && spec != null) {
                listeners.forEach { it.onTransferEnd(this, spec, /* isNetwork = */ true) }
            }
        }
    }

    private fun closeCurrentChunk() {
        runCatching { activeStream?.close() }
        runCatching { activeResponse?.close() }
        activeStream = null
        activeResponse = null
    }

    companion object {
        private const val CHUNK_SIZE_BYTES = 512L * 1024L // 512 KB bounded chunks
        private const val MAX_CLIENT_ROTATIONS_PER_CHUNK = 2
        private const val TOKEN_EXPIRY_SAFETY_WINDOW_MS = 90_000L
        internal const val HEADER_USE_QUERY_RANGE = "X-PocketLink-Use-Query-Range"
        internal const val HEADER_VIDEO_ID = "X-PocketLink-Video-Id"
        private val RANGE_PARAM_REGEX = Regex("([?&])(?:range|rn|rbuf)=[^&]*", RegexOption.IGNORE_CASE)
        private val CLEN_PARAM_REGEX = Regex("[?&]clen=(\\d+)", RegexOption.IGNORE_CASE)
        private val EXPIRE_PARAM_REGEX = Regex("[?&]expire=(\\d+)", RegexOption.IGNORE_CASE)

        internal fun parseContentRangeTotal(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val slash = header.lastIndexOf('/')
            if (slash < 0 || slash + 1 >= header.length) return null
            return header.substring(slash + 1).trim().toLongOrNull()
        }

        internal fun extractClenFromUrl(url: String): Long? =
            CLEN_PARAM_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }

        internal fun extractExpireEpochMsFromUrl(url: String): Long? =
            EXPIRE_PARAM_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }?.times(1_000L)

        internal fun isStreamTokenExpiringSoon(
            url: String,
            nowMs: Long = System.currentTimeMillis(),
            safetyWindowMs: Long = TOKEN_EXPIRY_SAFETY_WINDOW_MS,
        ): Boolean {
            val expireMs = extractExpireEpochMsFromUrl(url) ?: return false
            return expireMs <= nowMs + safetyWindowMs
        }

        internal fun stripRangeQueryParams(url: String): String {
            val qIdx = url.indexOf('?')
            if (qIdx < 0) return url
            val base = url.substring(0, qIdx)
            val query = url.substring(qIdx + 1)
            val kept = query.split('&').filter { part ->
                val key = part.substringBefore('=').lowercase()
                part.isNotEmpty() && key != "range" && key != "rn" && key != "rbuf"
            }
            return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
        }

        internal fun buildQueryRangeUrl(url: String, start: Long, end: Long, seq: Int): String {
            val cleaned = stripRangeQueryParams(url)
            val sep = if (cleaned.contains('?')) '&' else '?'
            return "$cleaned${sep}range=$start-$end&rn=$seq&rbuf=0"
        }
    }
}

@OptIn(UnstableApi::class)
private class RoutingHttpDataSource(
    private val pocketLinkClientProvider: (() -> OkHttpClient)?,
    private val fallbackClient: OkHttpClient,
    private val ytClientProvider: (() -> OkHttpClient)?,
) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val urlText = dataSpec.uri.toString()
        val host = dataSpec.uri.host.orEmpty()
        val activeHost = MediaPlaybackBridge.activeServerUrl
        val isPocketLinkHost = activeHost.isNotBlank() && urlText.startsWith(activeHost, ignoreCase = true)
        val isHlsRequest = host.contains("manifest.googlevideo.com", ignoreCase = true) ||
            urlText.contains(".m3u8", ignoreCase = true) ||
            urlText.contains("/hls_playlist/", ignoreCase = true) ||
            urlText.contains("/hls_chunk/", ignoreCase = true)
        val isGoogleVideo = !isPocketLinkHost && !isHlsRequest && host.contains("googlevideo.com", ignoreCase = true)
        val client = if (isPocketLinkHost) {
            pocketLinkClientProvider?.invoke() ?: fallbackClient
        } else {
            ytClientProvider?.invoke() ?: fallbackClient
        }
        val customUa = dataSpec.httpRequestHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
            ?: if (isHlsRequest) MediaPlaybackBridge.latestHlsUserAgent.takeIf { it.isNotBlank() } else null
        val useQueryRangeHint = dataSpec.httpRequestHeaders.entries
            .firstOrNull { it.key.equals(ChunkedYoutubeDataSource.HEADER_USE_QUERY_RANGE, ignoreCase = true) }
            ?.value
            .equals("true", ignoreCase = true)
        val videoIdHint = dataSpec.httpRequestHeaders.entries
            .firstOrNull { it.key.equals(ChunkedYoutubeDataSource.HEADER_VIDEO_ID, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
        val (created, effectiveSpec) = if (isGoogleVideo) {
            val chunked = ChunkedYoutubeDataSource(
                client = client,
                userAgent = customUa,
                initialUseQueryRange = useQueryRangeHint,
                videoId = videoIdHint,
                streamRefresher = { vid -> MediaPlaybackBridge.refreshStreamForChunkedDataSource(vid) },
            ).apply {
                listeners.forEach { addTransferListener(it) }
            }
            chunked to dataSpec
        } else {
            val factory = OkHttpDataSource.Factory(client)
            val filteredHeaders = dataSpec.httpRequestHeaders.filterKeys {
                !it.equals("User-Agent", ignoreCase = true) &&
                    !it.equals(ChunkedYoutubeDataSource.HEADER_USE_QUERY_RANGE, ignoreCase = true) &&
                    !it.equals(ChunkedYoutubeDataSource.HEADER_VIDEO_ID, ignoreCase = true)
            }
            val cleanedSpec = if (!customUa.isNullOrBlank()) {
                factory.setUserAgent(customUa)
                dataSpec.buildUpon().setHttpRequestHeaders(filteredHeaders).build()
            } else if (filteredHeaders.size != dataSpec.httpRequestHeaders.size) {
                dataSpec.buildUpon().setHttpRequestHeaders(filteredHeaders).build()
            } else {
                dataSpec
            }
            val okDs = factory.createDataSource().apply {
                listeners.forEach { addTransferListener(it) }
            }
            okDs to cleanedSpec
        }
        delegate = created
        return created.open(effectiveSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders.orEmpty()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}

internal object MediaPlaybackBridge {
    @Volatile var isServiceRunning: Boolean = false
    @Volatile var isPlaying: Boolean = false
    @Volatile var activeServerUrl: String = ""
    @Volatile var mediaClientProvider: (() -> OkHttpClient)? = null
    @Volatile var ytMediaClientProvider: (() -> OkHttpClient)? = null
    @Volatile var ytStreamResolver: ((String) -> ResolvedExternalStream)? = null
    @Volatile var onYtStreamError: ((String) -> Unit)? = null
    @Volatile var onResolvedStreamMeta: ((String, String, String) -> Unit)? = null
    @Volatile var skipSilenceEnabled: Boolean = false
    @Volatile var skipSilenceListener: ((Boolean) -> Unit)? = null
    @Volatile var latestHlsUserAgent: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())

    private val fallbackMediaClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(6, 2, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 16
                maxRequestsPerHost = 6
            })
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun applySkipSilence(enabled: Boolean) {
        skipSilenceEnabled = enabled
        val listener = skipSilenceListener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener(enabled)
        } else {
            mainHandler.post { skipSilenceListener?.invoke(enabled) }
        }
    }

    /**
     * Marks the active client for [videoId] as failed/expired, resolves a fresh stream
     * from the next client in the waterfall, and updates the UI badge/source metadata.
     */
    internal fun refreshStreamForChunkedDataSource(videoId: String): ResolvedExternalStream? {
        onYtStreamError?.invoke(videoId)
        val resolver = ytStreamResolver ?: return null
        val resolved = resolver(videoId)
        onResolvedStreamMeta?.invoke(videoId, resolved.badgeLabel, resolved.sourceName)
        return resolved
    }

    internal fun extractVideoIdFromBridgeUri(uri: Uri): String {
        val fromQuery = uri.getQueryParameter("v")?.trim()
        if (!fromQuery.isNullOrEmpty()) return fromQuery
        val fromHost = uri.host?.trim()
        if (!fromHost.isNullOrEmpty() && !fromHost.equals("track", ignoreCase = true)) return fromHost
        return uri.schemeSpecificPart.removePrefix("//").substringBefore('?').trim()
    }

    @OptIn(UnstableApi::class)
    fun dynamicDataSourceFactory(context: Context): DataSource.Factory {
        val routingHttpFactory = DataSource.Factory {
            RoutingHttpDataSource(
                pocketLinkClientProvider = { mediaClientProvider?.invoke() ?: fallbackMediaClient },
                fallbackClient = fallbackMediaClient,
                ytClientProvider = { ytMediaClientProvider?.invoke() ?: fallbackMediaClient },
            )
        }
        val multiSchemeFactory = DefaultDataSource.Factory(context.applicationContext, routingHttpFactory)
        return ResolvingDataSource.Factory(multiSchemeFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == "pocketlink-yt") {
                val videoId = extractVideoIdFromBridgeUri(uri)
                val resolver = ytStreamResolver
                    ?: throw IOException("Discover audio resolver is not active")
                val resolved = resolver(videoId)
                onResolvedStreamMeta?.invoke(videoId, resolved.badgeLabel, resolved.sourceName)
                if (resolved.isHls) {
                    latestHlsUserAgent = resolved.userAgent
                    throw HlsSwitchException(videoId, resolved)
                }
                val mergedHeaders = LinkedHashMap<String, String>(dataSpec.httpRequestHeaders)
                if (resolved.userAgent.isNotBlank()) {
                    mergedHeaders["User-Agent"] = resolved.userAgent
                }
                if (resolved.useQueryRange) {
                    mergedHeaders[ChunkedYoutubeDataSource.HEADER_USE_QUERY_RANGE] = "true"
                }
                mergedHeaders[ChunkedYoutubeDataSource.HEADER_VIDEO_ID] = videoId
                resolved.headers.forEach { (k, v) -> mergedHeaders[k] = v }
                dataSpec.buildUpon()
                    .setUri(Uri.parse(resolved.url))
                    .setHttpRequestHeaders(mergedHeaders)
                    .build()
            } else {
                dataSpec
            }
        }
    }

    fun isServiceActionStopIntent(context: Context): Intent =
        Intent(context, MediaPlaybackService::class.java)
}
