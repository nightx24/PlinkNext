package com.example.data.network

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppSettingsState
import com.example.data.model.YtAudioQuality
import com.example.data.store.DiagnosticLogGenerator
import com.example.media.AudioPlaybackState
import io.github.augztn.pocketlink.ssdbridge.ChunkedYoutubeDataSource
import io.github.augztn.pocketlink.ssdbridge.LocalHostStatus
import io.github.augztn.pocketlink.ssdbridge.MediaPlaybackBridge
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class YtMusicParsingAndLyricsTest {

    @Test
    fun `parseTracksFromJsonTree parses responsive, twoRow, playlistPanel items and deduplicates videoIds`() {
        val responsiveItem = JSONObject().put(
            "musicResponsiveListItemRenderer",
            JSONObject()
                .put("playlistItemData", JSONObject().put("videoId", "vid_resp_1"))
                .put(
                    "flexColumns",
                    JSONArray()
                        .put(
                            JSONObject().put(
                                "musicResponsiveListItemFlexColumnRenderer",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put(
                                        "runs",
                                        JSONArray().put(JSONObject().put("text", "Midnight City"))
                                    )
                                )
                            )
                        )
                        .put(
                            JSONObject().put(
                                "musicResponsiveListItemFlexColumnRenderer",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put(
                                        "runs",
                                        JSONArray()
                                            .put(JSONObject().put("text", "Song"))
                                            .put(JSONObject().put("text", " • "))
                                            .put(JSONObject().put("text", "M83"))
                                            .put(JSONObject().put("text", " • "))
                                            .put(JSONObject().put("text", "Hurry Up, We're Dreaming"))
                                    )
                                )
                            )
                        )
                )
                .put(
                    "fixedColumns",
                    JSONArray().put(
                        JSONObject().put(
                            "musicResponsiveListItemFlexColumnRenderer",
                            JSONObject().put(
                                "text",
                                JSONObject().put(
                                    "runs",
                                    JSONArray().put(JSONObject().put("text", "4:03"))
                                )
                            )
                        )
                    )
                )
                .put(
                    "thumbnail",
                    JSONObject().put(
                        "musicThumbnailRenderer",
                        JSONObject().put(
                            "thumbnail",
                            JSONObject().put(
                                "thumbnails",
                                JSONArray().put(
                                    JSONObject().put(
                                        "url",
                                        "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"
                                    )
                                )
                            )
                        )
                    )
                )
        )

        val duplicateResponsive = JSONObject(responsiveItem.toString())

        val twoRowItem = JSONObject().put(
            "musicTwoRowItemRenderer",
            JSONObject()
                .put(
                    "navigationEndpoint",
                    JSONObject().put("watchEndpoint", JSONObject().put("videoId", "vid_two_2"))
                )
                .put(
                    "title",
                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Starlight")))
                )
                .put(
                    "subtitle",
                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Song • Muse")))
                )
        )

        val playlistItem = JSONObject().put(
            "playlistPanelVideoRenderer",
            JSONObject()
                .put("videoId", "vid_panel_3")
                .put(
                    "title",
                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Daydreaming")))
                )
                .put(
                    "shortBylineText",
                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Radiohead • 2016")))
                )
                .put(
                    "lengthText",
                    JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "6:24")))
                )
        )

        val root = JSONObject().put(
            "contents",
            JSONArray()
                .put(responsiveItem)
                .put(duplicateResponsive)
                .put(twoRowItem)
                .put(playlistItem)
        )

        val parsed = YtMusicClient.parseTracksFromJsonTree(root, limit = 10)
        assertEquals(3, parsed.size)

        val first = parsed[0]
        assertEquals("vid_resp_1", first.videoId)
        assertEquals("Midnight City", first.title)
        assertEquals("M83", first.artist)
        assertEquals("Hurry Up, We're Dreaming", first.album)
        assertEquals("4:03", first.durationText)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", first.thumbnailUrl)

        val second = parsed[1]
        assertEquals("vid_two_2", second.videoId)
        assertEquals("Starlight", second.title)
        assertEquals("Muse", second.artist)
        assertEquals("https://i.ytimg.com/vi/vid_two_2/hqdefault.jpg", second.thumbnailUrl)

        val third = parsed[2]
        assertEquals("vid_panel_3", third.videoId)
        assertEquals("Daydreaming", third.title)
        assertEquals("Radiohead", third.artist)
        assertEquals("6:24", third.durationText)
    }

    @Test
    fun `parseSuggestionsFromJsonTree extracts unique suggestions in order`() {
        fun suggestionNode(text: String): JSONObject = JSONObject().put(
            "searchSuggestionRenderer",
            JSONObject().put(
                "suggestion",
                JSONObject().put("runs", JSONArray().put(JSONObject().put("text", text)))
            )
        )

        val root = JSONObject().put(
            "contents",
            JSONArray()
                .put(suggestionNode("daft punk"))
                .put(suggestionNode("daft punk"))
                .put(suggestionNode("daft punk instant crush"))
                .put(suggestionNode("   "))
                .put(suggestionNode("daft punk get lucky"))
        )

        val suggestions = YtMusicClient.parseSuggestionsFromJsonTree(root)
        assertEquals(
            listOf("daft punk", "daft punk instant crush", "daft punk get lucky"),
            suggestions
        )
    }

    @Test
    fun `selectBestAudioStream strictly prefers Opus across HIGH MEDIUM LOW tiers and parses expire timestamp`() {
        val formats = JSONArray()
            .put(
                JSONObject()
                    .put("itag", 139)
                    .put("url", "https://audio.example/m4a_low?expire=1800000000")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.5\"")
                    .put("bitrate", 48_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 140)
                    .put("url", "https://audio.example/m4a_mid?expire=1800000050")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    .put("bitrate", 129_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 141)
                    .put("url", "https://audio.example/m4a_high?expire=1800000080")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    .put("bitrate", 256_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 249)
                    .put("url", "https://audio.example/opus_low?expire=1800000100")
                    .put("mimeType", "audio/webm; codecs=\"opus\"")
                    .put("bitrate", 52_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 250)
                    .put("url", "https://audio.example/opus_med?expire=1800000150")
                    .put("mimeType", "audio/webm; codecs=\"opus\"")
                    .put("bitrate", 72_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 251)
                    .put("url", "https://audio.example/opus_high?expire=1800000200")
                    .put("mimeType", "audio/webm; codecs=\"opus\"")
                    .put("bitrate", 160_000L)
            )
            .put(
                JSONObject()
                    .put("url", "https://video.example/1080p?expire=1800000300")
                    .put("mimeType", "video/mp4")
                    .put("bitrate", 2_500_000L)
            )

        val playerJson = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("streamingData", JSONObject().put("adaptiveFormats", formats))

        val high = YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.HIGH, "ANDROID_VR")
        assertNotNull(high)
        assertEquals("https://audio.example/opus_high?expire=1800000200", high!!.url)
        assertEquals(251, high.itag)
        assertTrue(high.isOpus)
        assertEquals(160_000L, high.bitrateBps)
        assertEquals("ANDROID_VR", high.clientName)
        assertEquals(1800000200L * 1000L, high.expiresAtMs)
        assertEquals("OPUS · 160K", high.badgeLabel)

        val medium = YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.MEDIUM, "ANDROID_VR")
        assertNotNull(medium)
        assertEquals("https://audio.example/opus_med?expire=1800000150", medium!!.url)
        assertEquals(250, medium.itag)
        assertTrue(medium.isOpus)
        assertEquals("OPUS · 72K", medium.badgeLabel)

        val low = YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.LOW, "ANDROID_VR")
        assertNotNull(low)
        assertEquals("https://audio.example/opus_low?expire=1800000100", low!!.url)
        assertEquals(249, low.itag)
        assertTrue(low.isOpus)
        assertEquals("OPUS · 52K", low.badgeLabel)
    }

    @Test
    fun `selectBestAudioStream falls back to M4A tiers only when no Opus streams exist`() {
        val m4aOnlyFormats = JSONArray()
            .put(
                JSONObject()
                    .put("itag", 139)
                    .put("url", "https://audio.example/m4a_low?expire=1800000000")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.5\"")
                    .put("bitrate", 48_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 140)
                    .put("url", "https://audio.example/m4a_mid?expire=1800000100")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    .put("bitrate", 128_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 141)
                    .put("url", "https://audio.example/m4a_high?expire=1800000200")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    .put("bitrate", 256_000L)
            )

        val playerJson = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("streamingData", JSONObject().put("adaptiveFormats", m4aOnlyFormats))

        assertEquals(141, YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.HIGH, "IOS")?.itag)
        assertEquals(140, YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.MEDIUM, "IOS")?.itag)
        assertEquals(139, YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.LOW, "IOS")?.itag)
    }

    @Test
    fun `BitChord Opus priority prefers Opus itag 251 over AAC in HIGH mode and AUTO adapts to network`() {
        val formats = JSONArray()
            .put(
                JSONObject()
                    .put("itag", 140)
                    .put("url", "https://audio.example/aac128?expire=1800000200")
                    .put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"")
                    .put("bitrate", 142_000L)
            )
            .put(
                JSONObject()
                    .put("itag", 251)
                    .put("url", "https://audio.example/opus160?expire=1800000200")
                    .put("mimeType", "audio/webm; codecs=\"opus\"")
                    .put("bitrate", 156_000L)
            )
        val playerJson = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("streamingData", JSONObject().put("adaptiveFormats", formats))

        val picked = YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.HIGH, "NEWPIPE")
        assertNotNull(picked)
        assertEquals("https://audio.example/opus160?expire=1800000200", picked!!.url)
        assertEquals(251, picked.itag)
        assertEquals("OPUS · 156K", picked.badgeLabel)

        // Mochy-Music 4-tier AUTO network adaptation & legacy pref migration
        assertEquals(YtAudioQuality.HIGH, YtMusicClient.resolveEffectiveQuality(YtAudioQuality.AUTO, isUnmeteredNetwork = true))
        assertEquals(YtAudioQuality.MEDIUM, YtMusicClient.resolveEffectiveQuality(YtAudioQuality.AUTO, isUnmeteredNetwork = false))
        assertEquals(YtAudioQuality.MEDIUM, YtAudioQuality.fromPref("balanced"))
        assertEquals(YtAudioQuality.LOW, YtAudioQuality.fromPref("data_saver"))
        assertEquals(YtAudioQuality.AUTO, YtAudioQuality.fromPref("auto"))
    }

    @Test
    fun `selectBestAudioStream returns null when playabilityStatus is not OK`() {
        val playerJson = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED"))
            .put(
                "streamingData",
                JSONObject().put(
                    "adaptiveFormats",
                    JSONArray().put(
                        JSONObject()
                            .put("url", "https://audio.example/high")
                            .put("mimeType", "audio/mp4")
                            .put("bitrate", 192_000L)
                    )
                )
            )

        assertNull(
            YtMusicClient.selectBestAudioStream(playerJson, YtAudioQuality.HIGH, "ANDROID_VR")
        )
    }

    @Test
    fun `selectBestPipedAndInvidiousAudioStream picks expected audio stream`() {
        val pipedJson = JSONObject().put(
            "audioStreams",
            JSONArray()
                .put(JSONObject().put("url", "https://pipedproxy.example/low").put("mimeType", "audio/mp4").put("bitrate", 64_000L))
                .put(JSONObject().put("url", "https://pipedproxy.example/high").put("mimeType", "audio/webm").put("bitrate", 160_000L))
        )
        val pipedHigh = YtMusicClient.selectBestPipedAudioStream(pipedJson, YtAudioQuality.HIGH, "Piped")
        assertNotNull(pipedHigh)
        assertEquals("https://pipedproxy.example/high", pipedHigh!!.url)
        assertEquals("OPUS · 160K", pipedHigh.badgeLabel)

        val invidiousJson = JSONObject().put(
            "adaptiveFormats",
            JSONArray()
                .put(JSONObject().put("type", "audio/mp4; codecs=\"mp4a.40.2\"").put("itag", "140").put("bitrate", "129000"))
                .put(JSONObject().put("type", "audio/webm; codecs=\"opus\"").put("itag", "251").put("bitrate", "160000"))
        )
        val invStream = YtMusicClient.selectBestInvidiousAudioStream(invidiousJson, "https://inv.nadeko.net", "_AbC-123xYz", YtAudioQuality.HIGH)
        assertNotNull(invStream)
        assertEquals("https://inv.nadeko.net/latest_version?id=_AbC-123xYz&itag=251&local=true", invStream!!.url)
    }

    @Test
    fun `parseLrcLines converts timestamps filters blanks and sorts chronologically`() {
        val rawLrc = """
            [00:12.50] Second line
            [00:05.5]   First line  
            [00:08.000]   
            [01:02.125] Third line
            [02:00] Fourth line
        """.trimIndent()

        val lines = YtMusicClient.parseLrcLines(rawLrc)
        assertEquals(4, lines.size)

        assertEquals(5_500L, lines[0].timeMs)
        assertEquals("First line", lines[0].text)

        assertEquals(12_500L, lines[1].timeMs)
        assertEquals("Second line", lines[1].text)

        assertEquals(62_125L, lines[2].timeMs)
        assertEquals("Third line", lines[2].text)

        assertEquals(120_000L, lines[3].timeMs)
        assertEquals("Fourth line", lines[3].text)
    }

    @Test
    fun `YtTrack toIndexedFile and bridge URI preserve underscores and hyphens`() {
        val track = YtTrack(
            videoId = "_AbC-123xYz",
            title = "Never Gonna Give You Up",
            artist = "",
            durationText = "3:33",
            thumbnailUrl = "",
        )

        val file = track.toIndexedFile()
        assertTrue(file.isYouTubeStream)
        assertEquals("pocketlink-yt://track?v=_AbC-123xYz", file.streamUri)
        assertEquals("_AbC-123xYz", MediaPlaybackBridge.extractVideoIdFromBridgeUri(Uri.parse(file.streamUri)))
        assertEquals("dQw4w9WgXcQ", MediaPlaybackBridge.extractVideoIdFromBridgeUri(Uri.parse("pocketlink-yt://dQw4w9WgXcQ")))
        assertEquals("Never Gonna Give You Up", file.displayTitle)
        assertEquals("Discover", file.artist)
        assertEquals("https://i.ytimg.com/vi/_AbC-123xYz/hqdefault.jpg", file.artworkUrl)
        assertEquals("3:33", file.durationText)
        assertEquals("audio/webm", file.mime)
        assertEquals("_AbC-123xYz", NewPipeAudioExtractor.extractVideoIdFromUrl("https://www.youtube.com/watch?v=_AbC-123xYz&list=RD1"))
    }

    @Test
    fun `DiagnosticLogGenerator builds structured report with stream trace and decoder format`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selected = YtStreamInfo(
            url = "https://audio.example/opus251",
            mimeType = "audio/webm; codecs=\"opus\"",
            bitrateBps = 160_000L,
            clientName = "NEWPIPE",
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            itag = 251,
        )
        val trace = StreamResolutionTrace(
            timestampMs = System.currentTimeMillis(),
            videoId = "dQw4w9WgXcQ",
            requestedQuality = YtAudioQuality.HIGH,
            effectiveQuality = YtAudioQuality.HIGH,
            isUnmeteredNetwork = true,
            resolverProfile = "AUTO",
            selected = selected,
            probes = listOf(
                StreamProbeEvent(
                    source = "NEWPIPE",
                    itag = 251,
                    codec = "OPUS",
                    mimeType = "audio/webm; codecs=\"opus\"",
                    bitrateKbps = 160,
                    httpStatus = 206,
                    responseContentType = "audio/webm",
                    accepted = true,
                )
            ),
            errors = emptyList(),
        )
        val report = DiagnosticLogGenerator.generateReport(
            context = context,
            settings = AppSettingsState(ytAudioQuality = YtAudioQuality.HIGH),
            effectiveQuality = YtAudioQuality.HIGH,
            playbackState = AudioPlaybackState(
                queue = listOf(YtTrack("dQw4w9WgXcQ", "Test Song", "Test Artist", "3:33", "").toIndexedFile()),
                currentIndex = 0,
                streamBadge = "OPUS · 160K",
                actualAudioFormat = "audio/opus · 160k · 48000Hz · 2ch",
                isPlaying = true,
            ),
            lastTrace = trace,
            localLosslessCount = 12,
            hostStatus = LocalHostStatus(),
            tunnelStatus = TunnelStatusSnapshot(),
            probeOutput = "HIGH -> OPUS · 160K (itag=251)\nMEDIUM -> OPUS · 70K (itag=250)\nLOW -> OPUS · 50K (itag=249)",
        )

        assertTrue(report.contains("POCKETLINK DIAGNOSTIC LOG REPORT"))
        assertTrue(report.contains("Requested Quality     : High"))
        assertTrue(report.contains("ExoPlayer Format      : audio/opus · 160k · 48000Hz · 2ch"))
        assertTrue(report.contains("SELECTED STREAM       : OPUS · 160K | itag=251 | opus=true"))
        assertTrue(report.contains("#1 [PASS] src=NEWPIPE itag=251 codec=OPUS 160k"))
        assertTrue(report.contains("[4] LIVE MULTI-QUALITY PROBE RESULTS"))
        assertTrue(report.contains("12 lossless files indexed"))
    }

    @Test
    fun `selectBestLrclibCandidate prefers synced duration-matched original over wrong index-0 cover`() {
        val wrongCover = JSONObject()
            .put("trackName", "The Only Exception (Acoustic Cover)")
            .put("artistName", "Random Cover Band")
            .put("albumName", "Acoustic Covers")
            .put("duration", 190.0)
            .put("plainLyrics", "Wrong plain lyrics")

        val exactOriginal = JSONObject()
            .put("trackName", "The Only Exception")
            .put("artistName", "Paramore")
            .put("albumName", "Brand New Eyes")
            .put("duration", 268.0)
            .put("syncedLyrics", "[00:12.00] When I was younger I saw\n[00:16.50] My daddy cry and curse at the wind")

        val picked = YtMusicClient.selectBestLrclibCandidate(
            candidates = listOf(wrongCover, exactOriginal),
            targetTitle = "The Only Exception",
            targetArtist = "Paramore",
            targetAlbum = "Brand New Eyes",
            targetDurationSec = 267,
        )
        assertNotNull(picked)
        assertTrue(picked!!.isSynced)
        assertEquals(2, picked.lines.size)
        assertEquals("When I was younger I saw", picked.lines[0].text)

        assertEquals("Daft Punk", YtMusicClient.extractPrimaryArtist("Daft Punk, Pharrell Williams & Nile Rodgers"))
        assertEquals(268_000L, YtMusicClient.parseDurationTextToMs("4:28"))
        assertEquals(3_723_000L, YtMusicClient.parseDurationTextToMs("1:02:03"))

        val parsedEmbedded = YtMusicClient.parseRawLyricsText(
            "[00:01.00] Hello\n[00:04.50] World",
            sourceLabel = "Embedded Tag",
        )
        assertNotNull(parsedEmbedded)
        assertTrue(parsedEmbedded!!.isSynced)
        assertEquals("Embedded Tag Synced", parsedEmbedded.source)
    }

    @Test
    fun `ChunkedYoutubeDataSource parses Content-Range, clen, and builds clean native query range URLs`() {
        assertEquals(4_123_456L, ChunkedYoutubeDataSource.parseContentRangeTotal("bytes 0-524287/4123456"))
        assertNull(ChunkedYoutubeDataSource.parseContentRangeTotal("bytes 0-524287/*"))
        assertNull(ChunkedYoutubeDataSource.parseContentRangeTotal(null))

        val sampleUrl = "https://rr1---sn.googlevideo.com/videoplayback?expire=1800000000&itag=251&clen=4123456&range=0-100&rn=9&c=ANDROID_VR"
        assertEquals(4_123_456L, ChunkedYoutubeDataSource.extractClenFromUrl(sampleUrl))

        val stripped = ChunkedYoutubeDataSource.stripRangeQueryParams(sampleUrl)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?expire=1800000000&itag=251&clen=4123456&c=ANDROID_VR", stripped)

        val ranged = ChunkedYoutubeDataSource.buildQueryRangeUrl(sampleUrl, 0L, 524_287L, 1)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?expire=1800000000&itag=251&clen=4123456&c=ANDROID_VR&range=0-524287&rn=1&rbuf=0",
            ranged,
        )
    }

    @Test
    fun `computeSecondProbeOffset probes strictly past 1 MiB 403 wall for tracks over 1 MiB`() {
        // 4 MB track: must probe well past 1,048,576 bytes (60% -> ~2.4 MB)
        val fourMbOffset = YtMusicClient.computeSecondProbeOffset(4_000_000L)
        assertTrue("Expected > 1 MiB probe offset, got $fourMbOffset", fourMbOffset > 1_048_576L)
        assertEquals(2_400_000L, fourMbOffset)

        // 1.3 MB track: must be at least 1,114,112 bytes (past 1 MiB)
        val onePointThreeMbOffset = YtMusicClient.computeSecondProbeOffset(1_300_000L)
        assertEquals(1_114_112L, onePointThreeMbOffset)

        // 1.06 MB track: still strictly past 1,048,576 bytes
        assertEquals(1_050_000L, YtMusicClient.computeSecondProbeOffset(1_100_000L))

        // Short 400 KB clip: probes midpoint
        assertEquals(200_000L, YtMusicClient.computeSecondProbeOffset(400_000L))

        // Tiny <96 KB clip: skips second probe
        assertEquals(-1L, YtMusicClient.computeSecondProbeOffset(64_000L))
    }

    @Test
    fun `selectOrderedAudioStreams retains progressive formats and HLS manifest fallback after Opus`() {
        val adaptive = JSONArray().put(
            JSONObject()
                .put("itag", 251)
                .put("url", "https://rr1.googlevideo.com/videoplayback?itag=251&expire=1800000000")
                .put("mimeType", "audio/webm; codecs=\"opus\"")
                .put("bitrate", 148_000L)
        )
        val progressive = JSONArray().put(
            JSONObject()
                .put("itag", 18)
                .put("url", "https://rr1.googlevideo.com/videoplayback?itag=18&expire=1800000000")
                .put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
                .put("bitrate", 384_000L)
        )
        val hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1800000000/file/index.m3u8"
        val playerJson = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put(
                "streamingData",
                JSONObject()
                    .put("adaptiveFormats", adaptive)
                    .put("formats", progressive)
                    .put("hlsManifestUrl", hlsUrl)
            )

        val ordered = YtMusicClient.selectOrderedAudioStreams(playerJson, YtAudioQuality.HIGH, "VISIONOS")
        assertEquals(3, ordered.size)
        assertTrue(ordered[0].isOpus)
        assertEquals(251, ordered[0].itag)

        assertEquals(18, ordered[1].itag)
        assertEquals("MP4", ordered[1].codecLabel)

        assertTrue(ordered[2].isHls)
        assertEquals("HLS", ordered[2].codecLabel)
        assertEquals(hlsUrl, ordered[2].url)
        assertEquals("application/x-mpegURL", ordered[2].mimeType)
    }
}
