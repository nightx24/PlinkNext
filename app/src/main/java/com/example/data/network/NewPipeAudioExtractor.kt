package com.example.data.network

import com.example.data.model.YtAudioQuality
import com.example.data.store.AppLogger
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * NewPipeExtractor OkHttp Downloader implementation (matching InnerTune / Flow / Echo / NewPipe).
 * Handles YouTube web/client HTTP calls, JS player fetching, and 429 ReCaptcha signaling.
 */
internal class NewPipeOkHttpDownloader(
    private val client: OkHttpClient,
) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val dataToSend = request.dataToSend()
        val requestBody = when {
            dataToSend != null -> dataToSend.toRequestBody(null, 0, dataToSend.size)
            httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true) ->
                ByteArray(0).toRequestBody(null, 0, 0)
            else -> null
        }

        val reqBuilder = okhttp3.Request.Builder()
            .url(url)
            .method(httpMethod, requestBody)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")

        request.headers().forEach { (key, values) ->
            reqBuilder.removeHeader(key)
            values.forEach { value -> reqBuilder.addHeader(key, value) }
        }

        client.newCall(reqBuilder.build()).execute().use { res ->
            if (res.code == 429) {
                throw ReCaptchaException("Rate limited (HTTP 429)", url)
            }
            val bodyString = res.body?.string().orEmpty()
            val latestUrl = res.request.url.toString()
            return Response(
                res.code,
                res.message,
                res.headers.toMultimap(),
                bodyString,
                latestUrl,
            )
        }
    }

    companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}

/**
 * Facade over TeamNewPipe's NewPipeExtractor for Discover streaming, search, and related tracks.
 * Runs Rhino JS signature & n-parameter deobfuscation natively on-device so YouTube streams play cleanly.
 */
internal class NewPipeAudioExtractor(
    private val httpClient: OkHttpClient,
) {
    @Volatile
    private var initialized = false
    private val initLock = Any()

    fun ensureInitialized(countryCode: String = "US") {
        val cleanCountry = countryCode.ifBlank { "US" }.uppercase(Locale.US)
        if (!initialized) {
            synchronized(initLock) {
                if (!initialized) {
                    NewPipe.init(
                        NewPipeOkHttpDownloader(httpClient),
                        Localization("en", cleanCountry),
                        ContentCountry(cleanCountry),
                    )
                    initialized = true
                }
            }
        } else {
            runCatching {
                NewPipe.setPreferredContentCountry(ContentCountry(cleanCountry))
            }
        }
    }

    /**
     * Resolves ordered candidate audio streams for [videoId] using NewPipeExtractor's
     * full StreamInfo pipeline (including JS cipher & n-throttling deobfuscation).
     */
    fun extractCandidateStreams(
        videoId: String,
        quality: YtAudioQuality,
        countryCode: String,
    ): List<YtStreamInfo> {
        ensureInitialized(countryCode)
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)

        val validAudio = streamInfo.audioStreams
            .orEmpty()
            .filter { stream ->
                val url = stream.content.orEmpty().trim()
                url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
            }

        val progressiveFallback = streamInfo.videoStreams
            .orEmpty()
            .filter { !it.isVideoOnly && !it.content.isNullOrBlank() }
            .sortedByDescending { it.height }
            .map { vs ->
                val url = vs.content.orEmpty().trim()
                YtStreamInfo(
                    url = url,
                    mimeType = vs.format?.mimeType ?: "video/mp4",
                    bitrateBps = 128_000L,
                    clientName = "NewPipe",
                    expiresAtMs = YtMusicClient.extractExpireEpochMs(url)
                        ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4)),
                    userAgent = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
                    codecLabel = "MP4",
                    itag = vs.itag.takeIf { it > 0 },
                )
            }

        if (validAudio.isEmpty()) {
            return progressiveFallback
        }

        val ordered = orderAudioStreamsByQuality(validAudio, quality)
        val summary = ordered.take(6).joinToString(", ") { s ->
            val tag = if (s.itag > 0) "itag=${s.itag}" else "itag=?"
            val kind = if (isOpusAudioStream(s)) "OPUS" else (s.format?.suffix?.uppercase(Locale.US) ?: "AUDIO")
            "$tag($kind ${s.averageBitrate}k)"
        }
        AppLogger.info("NewPipe streams for $videoId [$quality]", "${ordered.size} audio, ${progressiveFallback.size} progressive: $summary")
        val audioCandidates = ordered.map { audio ->
            val url = audio.content.orEmpty().trim()
            val bitrateBps = (audio.averageBitrate.takeIf { it > 0 } ?: 128).toLong() * 1_000L
            val mime = audio.format?.mimeType ?: "audio/webm"
            val isOpus = isOpusAudioStream(audio)
            val codecLabel = when {
                isOpus -> "OPUS"
                mime.contains("mp4", ignoreCase = true) || audio.format?.suffix.equals("m4a", ignoreCase = true) -> "M4A"
                else -> audio.format?.suffix?.uppercase(Locale.US)?.ifBlank { "AUDIO" } ?: "AUDIO"
            }
            YtStreamInfo(
                url = url,
                mimeType = mime,
                bitrateBps = bitrateBps,
                clientName = "NewPipe",
                expiresAtMs = YtMusicClient.extractExpireEpochMs(url)
                    ?: (System.currentTimeMillis() + TimeUnit.HOURS.toMillis(4)),
                userAgent = NewPipeOkHttpDownloader.BROWSER_USER_AGENT,
                codecLabel = codecLabel,
                itag = audio.itag.takeIf { it > 0 },
            )
        }
        return audioCandidates + progressiveFallback
    }

    /**
     * Searches songs via NewPipeExtractor (used as fallback when direct InnerTube search is blocked/empty).
     */
    fun searchTracks(query: String, countryCode: String, limit: Int = 25): List<YtTrack> {
        ensureInitialized(countryCode)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val filters = listOf(listOf("music_songs"), emptyList())
        for (filter in filters) {
            val found = runCatching {
                val handler = ServiceList.YouTube.searchQHFactory.fromQuery(trimmed, filter, "")
                val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
                info.relatedItems
                    .orEmpty()
                    .filterIsInstance<StreamInfoItem>()
                    .mapNotNull { it.toYtTrack() }
                    .distinctBy { it.videoId }
                    .take(limit)
            }.getOrNull().orEmpty()
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /**
     * Fetches related tracks via NewPipeExtractor StreamInfo (used when Up Next radio needs a fallback).
     */
    fun fetchRelatedTracks(videoId: String, countryCode: String, limit: Int = 18): List<YtTrack> {
        ensureInitialized(countryCode)
        return runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            info.relatedItems
                .orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toYtTrack() }
                .filter { it.videoId != videoId }
                .distinctBy { it.videoId }
                .take(limit)
        }.onFailure { err ->
            AppLogger.warning("NewPipe related tracks failed", err.message ?: err.javaClass.simpleName)
        }.getOrDefault(emptyList())
    }

    private fun StreamInfoItem.toYtTrack(): YtTrack? {
        val rawUrl = url.orEmpty()
        val id = extractVideoIdFromUrl(rawUrl) ?: return null
        val titleText = name.orEmpty().trim()
        if (titleText.isEmpty()) return null
        val artistText = uploaderName.orEmpty().trim().ifBlank { "Discover" }
        val durationSec = duration
        val durationFormatted = if (durationSec > 0L) {
            val mins = durationSec / 60L
            val secs = durationSec % 60L
            "$mins:${secs.toString().padStart(2, '0')}"
        } else ""
        val thumb = thumbnails.orEmpty().lastOrNull()?.url.orEmpty()
            .ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        return YtTrack(
            videoId = id,
            title = titleText,
            artist = artistText,
            durationText = durationFormatted,
            thumbnailUrl = thumb,
        )
    }

    companion object {
        private val OPUS_ITAGS = setOf(774, 251, 250, 249)

        internal fun isOpusAudioStream(stream: AudioStream): Boolean {
            if (stream.itag in OPUS_ITAGS) return true
            val codec = stream.codec.orEmpty()
            val mime = stream.format?.mimeType.orEmpty()
            val suffix = stream.format?.suffix.orEmpty()
            return codec.contains("opus", ignoreCase = true) ||
                mime.contains("opus", ignoreCase = true) ||
                mime.contains("webm", ignoreCase = true) ||
                suffix.equals("opus", ignoreCase = true) ||
                suffix.equals("webm", ignoreCase = true)
        }

        internal fun orderAudioStreamsByQuality(
            streams: List<AudioStream>,
            quality: YtAudioQuality,
            isUnmeteredNetwork: Boolean = true,
        ): List<AudioStream> {
            // BitChord + Mochy-Music strategy:
            // 1) Strictly prioritize Opus (itag 774 / 251 / 250 / 249) over M4A/MP4 across ALL quality tiers.
            // 2) Within each codec pool, pick distinct bitrate tiers:
            //    HIGH   -> itag 774 (256k) / 251 (~160k) / highest bitrate
            //    MEDIUM -> itag 250 (~70k) / middle bitrate tier (clearly distinct from HIGH 251 & LOW 249)
            //    LOW    -> itag 249 (~50k) / lowest bitrate
            val resolved = when (quality) {
                YtAudioQuality.AUTO -> if (isUnmeteredNetwork) YtAudioQuality.HIGH else YtAudioQuality.MEDIUM
                else -> quality
            }

            fun sortPool(pool: List<AudioStream>, isOpusPool: Boolean): List<AudioStream> {
                if (pool.size <= 1) return pool
                return when (resolved) {
                    YtAudioQuality.HIGH, YtAudioQuality.AUTO -> pool.sortedWith(
                        compareByDescending<AudioStream> { if (it.itag == 774) 2 else if (it.itag == 251) 1 else 0 }
                            .thenByDescending { it.averageBitrate.takeIf { b -> b > 0 } ?: 0 }
                    )
                    YtAudioQuality.MEDIUM -> {
                        val targetKbps = if (isOpusPool) 70 else 96
                        val distinctRates = pool.mapNotNull { it.averageBitrate.takeIf { b -> b > 0 } }.distinct().sorted()
                        val middleRate = if (distinctRates.size >= 3) distinctRates[distinctRates.size / 2] else targetKbps
                        pool.sortedWith(
                            compareByDescending<AudioStream> { if (isOpusPool && it.itag == 250) 1 else 0 }
                                .thenBy { abs((it.averageBitrate.takeIf { b -> b > 0 } ?: middleRate) - middleRate) }
                                .thenByDescending { it.averageBitrate }
                        )
                    }
                    YtAudioQuality.LOW -> pool.sortedWith(
                        compareByDescending<AudioStream> { if (isOpusPool && it.itag == 249) 1 else 0 }
                            .thenBy { if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE }
                    )
                }
            }

            val (opusPool, nonOpusPool) = streams.partition { isOpusAudioStream(it) }
            return sortPool(opusPool, isOpusPool = true) + sortPool(nonOpusPool, isOpusPool = false)
        }

        internal fun extractVideoIdFromUrl(url: String): String? {
            if (url.isBlank()) return null
            val vParam = Regex("[?&]v=([A-Za-z0-9_-]{6,20})").find(url)?.groupValues?.getOrNull(1)
            if (!vParam.isNullOrBlank()) return vParam
            val shortLink = Regex("youtu\\.be/([A-Za-z0-9_-]{6,20})").find(url)?.groupValues?.getOrNull(1)
            if (!shortLink.isNullOrBlank()) return shortLink
            return null
        }
    }
}
