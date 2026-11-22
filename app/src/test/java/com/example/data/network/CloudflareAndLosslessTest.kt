package com.example.data.network

import android.util.Base64
import com.example.data.model.NetworkRouteType
import com.example.data.model.classifyNetworkRoute
import com.example.data.model.parseServerEndpoint
import io.github.augztn.pocketlink.ssdbridge.AudioMetadataExtractor
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelAdapter
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelProviderId
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelProviderRegistry
import io.github.augztn.pocketlink.ssdbridge.tunnel.buildPocketLinkConnectUri
import io.github.augztn.pocketlink.ssdbridge.tunnel.normalizeCloudflarePublicUrl
import io.github.augztn.pocketlink.ssdbridge.tunnel.parseCloudflareTunnelToken
import io.github.augztn.pocketlink.ssdbridge.tunnel.parsePocketLinkConnectInput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudflareAndLosslessTest {

    @Test
    fun `tunnel provider registry exposes Cloudflare only for now`() {
        assertEquals(listOf(TunnelProviderId.CLOUDFLARE), TunnelProviderRegistry.availableProviders())
    }

    @Test
    fun `parseCloudflareTunnelToken decodes valid eyJ token and rejects malformed tokens`() {
        val rawJson = """{"a":"account1234567890","t":"tunnel-uuid-abcd-1234","s":"supersecretkey"}"""
        val encoded = Base64.encodeToString(rawJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val info = parseCloudflareTunnelToken("cloudflared tunnel run --token $encoded")
        assertNotNull(info)
        assertEquals("account1234567890", info!!.accountId)
        assertEquals("tunnel-uuid-abcd-1234", info.tunnelId)
        assertTrue(info.valid)

        assertNull(parseCloudflareTunnelToken("not-a-valid-token"))
        val incomplete = Base64.encodeToString("""{"a":"acc","t":""}""".toByteArray(), Base64.NO_WRAP)
        assertNull(parseCloudflareTunnelToken(incomplete))
    }

    @Test
    fun `normalizeCloudflarePublicUrl and endpoint parser classify Cloudflare hosts as HTTPS`() {
        val normalized = normalizeCloudflarePublicUrl("my-pocketlink.user.workers.dev/health").getOrThrow()
        assertEquals("https://my-pocketlink.user.workers.dev", normalized)
        assertTrue(normalizeCloudflarePublicUrl("http://insecure.trycloudflare.com").isFailure)

        val quick = parseServerEndpoint("calm-river-1234.trycloudflare.com", "8000")
        assertEquals("https://calm-river-1234.trycloudflare.com", quick.baseUrl)
        assertEquals(443, quick.port)
        assertEquals(NetworkRouteType.CLOUDFLARE, classifyNetworkRoute(quick.baseUrl))

        val worker = parseServerEndpoint("https://pocketlink-relay.example.workers.dev/", "")
        assertEquals(NetworkRouteType.CLOUDFLARE, classifyNetworkRoute(worker.baseUrl))
        assertEquals(
            "wss://pocketlink-relay.example.workers.dev/__pocketlink_tunnel",
            CloudflareTunnelAdapter.buildWorkerWebSocketUrl(worker.baseUrl),
        )
    }

    @Test
    fun `sanitizeForwardedRequestHeaders strips hop-by-hop and peer IP spoof headers`() {
        val cleaned = CloudflareTunnelAdapter.sanitizeForwardedRequestHeaders(
            mapOf(
                "Authorization" to "Basic dGVzdDp0ZXN0",
                "Range" to "bytes=0-1023",
                "Host" to "evil.example.com",
                "Remote-Addr" to "203.0.113.5",
                "http-client-ip" to "203.0.113.5",
                "Connection" to "keep-alive",
            )
        )
        assertEquals("Basic dGVzdDp0ZXN0", cleaned["Authorization"])
        assertEquals("bytes=0-1023", cleaned["Range"])
        assertFalse(cleaned.containsKey("Host"))
        assertFalse(cleaned.containsKey("Remote-Addr"))
        assertFalse(cleaned.containsKey("http-client-ip"))
        assertFalse(cleaned.containsKey("Connection"))
    }

    @Test
    fun `LocalLosslessMatcher detects matching lossless files and falls back to NewPipe when missing or disabled`() {
        val library = listOf(
            LocalLosslessEntry(
                id = "local_1",
                fileName = "01 - M83 - Midnight City (Official Audio).wav",
                titleHint = "Midnight City (Official Audio)",
                artistHint = "M83",
                extension = "wav",
                uriString = "file:///storage/emulated/0/Music/MidnightCity.wav",
                sourceLabel = "Local",
                sizeBytes = 42_000_000L,
            ),
            LocalLosslessEntry(
                id = "host_1",
                fileName = "01 - M83 - Midnight City (24-bit FLAC).flac",
                titleHint = "Midnight City",
                artistHint = "M83",
                extension = "flac",
                uriString = "http://127.0.0.1:8000/api/files/stream?path=Music/MidnightCity.flac",
                sourceLabel = "Host",
                sizeBytes = 38_000_000L,
            ),
            LocalLosslessEntry(
                id = "local_2",
                fileName = "Other Artist - Midnight City.flac",
                titleHint = "Midnight City",
                artistHint = "Different Band",
                extension = "flac",
                uriString = "file:///storage/emulated/0/Music/Other.flac",
                sourceLabel = "Local",
                sizeBytes = 25_000_000L,
            ),
        )

        val discoverTrack = YtTrack(
            videoId = "dX3k_QDnzHE",
            title = "Midnight City (Official Video)",
            artist = "M83",
            durationText = "4:04",
        )

        // When enabled and matched: prefers FLAC over WAV and returns local lossless IndexedFile
        val resolvedOn = LocalLosslessMatcher.resolveTrackForPlayback(discoverTrack, enabled = true, library = library)
        assertTrue(resolvedOn.isLocalLosslessMatch)
        assertFalse(resolvedOn.isYouTubeStream)
        assertEquals("http://127.0.0.1:8000/api/files/stream?path=Music/MidnightCity.flac", resolvedOn.streamUri)
        assertEquals("HOST FLAC · LOSSLESS", resolvedOn.localLosslessBadge)
        assertEquals("dX3k_QDnzHE", resolvedOn.path)

        // When disabled: always uses NewPipe / YouTube stream
        val resolvedOff = LocalLosslessMatcher.resolveTrackForPlayback(discoverTrack, enabled = false, library = library)
        assertFalse(resolvedOff.isLocalLosslessMatch)
        assertTrue(resolvedOff.isYouTubeStream)
        assertEquals("pocketlink-yt://track?v=dX3k_QDnzHE", resolvedOff.streamUri)

        // When artist conflicts or track missing: falls back to NewPipe
        val missingTrack = YtTrack(
            videoId = "abc12345678",
            title = "Instant Crush",
            artist = "Daft Punk",
            durationText = "5:37",
        )
        val fallback = LocalLosslessMatcher.resolveTrackForPlayback(missingTrack, enabled = true, library = library)
        assertFalse(fallback.isLocalLosslessMatch)
        assertTrue(fallback.isYouTubeStream)
        assertEquals("pocketlink-yt://track?v=abc12345678", fallback.streamUri)

        // Artist conflict should not match wrong band's same-title track
        val wrongArtistTrack = YtTrack(
            videoId = "zzz12345678",
            title = "Midnight City",
            artist = "Completely Unrelated Artist",
        )
        val conflictResult = LocalLosslessMatcher.findBestMatch(wrongArtistTrack, listOf(library.last()))
        assertNull(conflictResult)
    }

    @Test
    fun `PocketLink Client Connect Link builds and parses pocketlink URI, bare Cloudflare hosts, and LAN IP-port`() {
        val uri = buildPocketLinkConnectUri(
            publicUrl = "https://my-pocketlink.example.workers.dev",
            username = "admin",
            serverName = "Home SSD",
        )
        assertTrue(uri.startsWith("pocketlink://connect?"))

        val parsedLink = parsePocketLinkConnectInput(uri)
        assertNotNull(parsedLink)
        assertEquals("https://my-pocketlink.example.workers.dev", parsedLink!!.host)
        assertEquals("443", parsedLink.port)
        assertEquals("admin", parsedLink.username)
        assertEquals("Home SSD", parsedLink.serverName)
        assertTrue(parsedLink.isHttps)

        val bareCf = parsePocketLinkConnectInput("my-relay.workers.dev")
        assertNotNull(bareCf)
        assertEquals("https://my-relay.workers.dev", bareCf!!.host)
        assertEquals("443", bareCf.port)

        val tailscale = parsePocketLinkConnectInput("100.68.88.73:8000")
        assertNotNull(tailscale)
        assertEquals("100.68.88.73", tailscale!!.host)
        assertEquals("8000", tailscale.port)
    }

    @Test
    fun `AudioMetadataExtractor parses synthetic FLAC, ID3v2, and WAV container headers cleanly`() {
        // 1) Synthetic FLAC (STREAMINFO: 96kHz, 2ch, 24-bit, 9600000 samples = 100s + VORBIS_COMMENT)
        val flacOut = ByteArrayOutputStream()
        flacOut.write("fLaC".toByteArray(Charsets.US_ASCII))
        val streamInfo = ByteArray(34)
        // sampleRate=96000 (0x17700), channels-1=1 (001), bps-1=23 (10111), totalSamples=9_600_000 (0x927C00)
        val sr = 96_000L
        val chMinus1 = 1L
        val bpsMinus1 = 23L
        val totalSamples = 9_600_000L
        streamInfo[10] = ((sr ushr 12) and 0xFF).toByte()
        streamInfo[11] = ((sr ushr 4) and 0xFF).toByte()
        streamInfo[12] = (((sr and 0x0F) shl 4) or (chMinus1 shl 1) or ((bpsMinus1 ushr 4) and 0x01)).toByte()
        streamInfo[13] = (((bpsMinus1 and 0x0F) shl 4) or ((totalSamples ushr 32) and 0x0F)).toByte()
        streamInfo[14] = ((totalSamples ushr 24) and 0xFF).toByte()
        streamInfo[15] = ((totalSamples ushr 16) and 0xFF).toByte()
        streamInfo[16] = ((totalSamples ushr 8) and 0xFF).toByte()
        streamInfo[17] = (totalSamples and 0xFF).toByte()
        flacOut.write(0x00) // STREAMINFO, not last
        flacOut.write(byteArrayOf(0, 0, 34))
        flacOut.write(streamInfo)

        val vc = ByteArrayOutputStream()
        fun writeLEInt(out: ByteArrayOutputStream, v: Int) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        }
        val vendor = "PocketLinkTest".toByteArray(Charsets.UTF_8)
        writeLEInt(vc, vendor.size)
        vc.write(vendor)
        val comments = listOf(
            "TITLE=Get Lucky",
            "ARTIST=Daft Punk",
            "ARTIST=Pharrell Williams",
            "ALBUM=Random Access Memories",
            "SYNCEDLYRICS=[00:10.00] Like the legend of the phoenix",
        )
        writeLEInt(vc, comments.size)
        comments.forEach { c ->
            val b = c.toByteArray(Charsets.UTF_8)
            writeLEInt(vc, b.size)
            vc.write(b)
        }
        val vcBytes = vc.toByteArray()
        flacOut.write(0x84) // VORBIS_COMMENT (4) + isLast (0x80)
        flacOut.write(byteArrayOf(((vcBytes.size ushr 16) and 0xFF).toByte(), ((vcBytes.size ushr 8) and 0xFF).toByte(), (vcBytes.size and 0xFF).toByte()))
        flacOut.write(vcBytes)

        val flacMeta = AudioMetadataExtractor.parseHeaderBytes(flacOut.toByteArray(), "flac", includeLyrics = true)
        assertEquals("FLAC_HEADER", flacMeta.source)
        assertEquals("Get Lucky", flacMeta.title)
        assertEquals("Daft Punk, Pharrell Williams", flacMeta.artist)
        assertEquals("Random Access Memories", flacMeta.album)
        assertEquals(96_000, flacMeta.sampleRateHz)
        assertEquals(24, flacMeta.bitDepth)
        assertEquals(100_000L, flacMeta.durationMs)
        assertEquals("24-bit/96kHz", flacMeta.qualitySpecLabel)
        assertTrue(flacMeta.embeddedLyrics!!.contains("[00:10.00] Like the legend"))

        // 2) Synthetic ID3v2.3 (TIT2, TPE1, TALB, TLEN, USLT)
        val frames = ByteArrayOutputStream()
        fun writeId3Frame(id: String, payload: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
            frames.write(byteArrayOf(0, 0))
            frames.write(payload)
        }
        writeId3Frame("TIT2", byteArrayOf(3) + "Starboy".toByteArray(Charsets.UTF_8))
        writeId3Frame("TPE1", byteArrayOf(3) + "The Weeknd".toByteArray(Charsets.UTF_8))
        writeId3Frame("TALB", byteArrayOf(3) + "Starboy".toByteArray(Charsets.UTF_8))
        writeId3Frame("TLEN", byteArrayOf(3) + "230000".toByteArray(Charsets.UTF_8))
        writeId3Frame("USLT", byteArrayOf(3) + "eng".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + "[00:05.00] I'm tryna put you in the worst mood".toByteArray(Charsets.UTF_8))
        val frameBytes = frames.toByteArray()
        val id3Out = ByteArrayOutputStream()
        id3Out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        id3Out.write(byteArrayOf(3, 0, 0)) // v2.3.0, flags=0
        val sz = frameBytes.size
        id3Out.write(byteArrayOf(((sz ushr 21) and 0x7F).toByte(), ((sz ushr 14) and 0x7F).toByte(), ((sz ushr 7) and 0x7F).toByte(), (sz and 0x7F).toByte()))
        id3Out.write(frameBytes)

        val id3Meta = AudioMetadataExtractor.parseHeaderBytes(id3Out.toByteArray(), "mp3", includeLyrics = true)
        assertEquals("ID3V2", id3Meta.source)
        assertEquals("Starboy", id3Meta.title)
        assertEquals("The Weeknd", id3Meta.artist)
        assertEquals(230_000L, id3Meta.durationMs)
        assertTrue(id3Meta.embeddedLyrics!!.contains("worst mood"))

        // 3) Synthetic WAV (fmt + LIST INFO)
        val wavBody = ByteArrayOutputStream()
        wavBody.write("WAVE".toByteArray(Charsets.ISO_8859_1))
        wavBody.write("fmt ".toByteArray(Charsets.ISO_8859_1))
        writeLEInt(wavBody, 16)
        val fmtBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1) // PCM
            .putShort(2) // 2ch
            .putInt(44100) // 44.1kHz
            .putInt(176400) // byteRate
            .putShort(4) // blockAlign
            .putShort(16) // 16-bit
        wavBody.write(fmtBuf.array())
        val infoContent = ByteArrayOutputStream()
        infoContent.write("INFO".toByteArray(Charsets.ISO_8859_1))
        val inamBytes = "Instant Crush\u0000".toByteArray(Charsets.UTF_8)
        infoContent.write("INAM".toByteArray(Charsets.ISO_8859_1))
        writeLEInt(infoContent, inamBytes.size)
        infoContent.write(inamBytes)
        if (inamBytes.size % 2 == 1) infoContent.write(0)
        val infoBytes = infoContent.toByteArray()
        wavBody.write("LIST".toByteArray(Charsets.ISO_8859_1))
        writeLEInt(wavBody, infoBytes.size)
        wavBody.write(infoBytes)
        val bodyBytes = wavBody.toByteArray()
        val wavOut = ByteArrayOutputStream()
        wavOut.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        writeLEInt(wavOut, bodyBytes.size)
        wavOut.write(bodyBytes)

        val wavMeta = AudioMetadataExtractor.parseHeaderBytes(wavOut.toByteArray(), "wav")
        assertEquals("WAV_HEADER", wavMeta.source)
        assertEquals("Instant Crush", wavMeta.title)
        assertEquals(44_100, wavMeta.sampleRateHz)
        assertEquals(16, wavMeta.bitDepth)
    }

    @Test
    fun `LocalLosslessMatcher matches generic filename via embedded tags, handles multi-artist, guards duration, and formats Hi-Res badge`() {
        val genericFlac = LocalLosslessEntry(
            id = "hi_res_1",
            fileName = "04.flac",
            titleHint = "Get Lucky",
            artistHint = "Daft Punk, Pharrell Williams",
            albumHint = "Random Access Memories",
            extension = "flac",
            uriString = "file:///storage/emulated/0/Music/RAM/04.flac",
            sourceLabel = "Local",
            sizeBytes = 128_000_000L,
            durationMs = 369_000L,
            sampleRateHz = 96_000,
            bitDepth = 24,
            metadataSource = "FLAC_HEADER",
        )
        assertEquals("LOCAL FLAC · 24-bit/96kHz", genericFlac.badgeSummary)

        val matchingTrack = YtTrack(
            videoId = "5NV6Rdv1a3I",
            title = "Get Lucky (Official Audio)",
            artist = "Daft Punk",
            album = "Random Access Memories",
            durationText = "6:09",
        )
        val match = LocalLosslessMatcher.findBestMatch(matchingTrack, listOf(genericFlac))
        assertNotNull(match)
        assertEquals("hi_res_1", match!!.entry.id)

        // Duration guard (>45s mismatch rejects short radio edit / intro against long version)
        val wrongDurationTrack = YtTrack(
            videoId = "short_ver",
            title = "Get Lucky",
            artist = "Daft Punk",
            durationText = "2:00",
        )
        assertNull(LocalLosslessMatcher.findBestMatch(wrongDurationTrack, listOf(genericFlac)))
    }
}
