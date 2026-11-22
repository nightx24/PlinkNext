package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Rich audio metadata extracted directly from embedded container tags (FLAC STREAMINFO +
 * VORBIS_COMMENT, ID3v2, MP4/ALAC ilst atoms, WAV RIFF/INFO) with MediaMetadataRetriever
 * fallback — inspired by Telegram-Music-Addon & BitChord's header-first metadata pipeline.
 */
data class ExtractedAudioMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateBps: Int = 0,
    val channels: Int = 0,
    val embeddedLyrics: String? = null,
    val source: String = "",
) {
    val hasRichTags: Boolean
        get() = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()

    val hasEmbeddedLyrics: Boolean
        get() = !embeddedLyrics.isNullOrBlank()

    /** Compact audiophile spec string such as "24-bit/96kHz" or "16-bit/44.1kHz". */
    val qualitySpecLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (bitDepth > 0) parts.add("${bitDepth}-bit")
            if (sampleRateHz > 0) {
                val khz = sampleRateHz / 1000.0
                val formatted = if (sampleRateHz % 1000 == 0) {
                    "${sampleRateHz / 1000}kHz"
                } else {
                    String.format(Locale.US, "%.1fkHz", khz)
                }
                parts.add(formatted)
            }
            return parts.joinToString("/")
        }
}

object AudioMetadataExtractor {

    private const val MAX_HEADER_BYTES = 256 * 1024 // Read at most 256 KB of header tags

    fun extractFromFile(file: File, includeLyrics: Boolean = false): ExtractedAudioMetadata {
        if (!file.exists() || !file.isFile || !file.canRead()) return ExtractedAudioMetadata()
        val ext = file.extension.lowercase(Locale.US)
        val headerMeta = runCatching {
            FileInputStream(file).use { fis ->
                extractFromStream(fis, ext, file.length(), includeLyrics)
            }
        }.getOrDefault(ExtractedAudioMetadata())

        val sidecarLyrics = if (includeLyrics && headerMeta.embeddedLyrics.isNullOrBlank()) {
            readSidecarLrc(file)
        } else {
            null
        }

        val merged = if (sidecarLyrics != null) {
            headerMeta.copy(embeddedLyrics = sidecarLyrics)
        } else {
            headerMeta
        }

        if (merged.title.isNotBlank() && merged.artist.isNotBlank() && merged.durationMs > 0L) {
            return merged
        }

        // Fill any missing fields from Android MediaMetadataRetriever
        val mmrMeta = extractViaMediaMetadataRetriever { setDataSource(file.absolutePath) }
        return mergeMetadata(primary = merged, fallback = mmrMeta)
    }

    fun extractFromUri(
        context: Context,
        uri: Uri,
        fileName: String,
        sizeBytes: Long = 0L,
        includeLyrics: Boolean = false,
    ): ExtractedAudioMetadata {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val headerMeta = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                extractFromStream(input, ext, sizeBytes, includeLyrics)
            }
        }.getOrNull() ?: ExtractedAudioMetadata()

        if (headerMeta.title.isNotBlank() && headerMeta.artist.isNotBlank() && headerMeta.durationMs > 0L) {
            return headerMeta
        }

        val mmrMeta = extractViaMediaMetadataRetriever { setDataSource(context, uri) }
        return mergeMetadata(primary = headerMeta, fallback = mmrMeta)
    }

    fun extractFromStream(
        rawStream: InputStream,
        extension: String,
        totalLengthBytes: Long = 0L,
        includeLyrics: Boolean = false,
    ): ExtractedAudioMetadata {
        val buffered = BufferedInputStream(rawStream, 16 * 1024)
        val headerBytes = readUpTo(buffered, MAX_HEADER_BYTES)
        if (headerBytes.size < 12) return ExtractedAudioMetadata()
        return parseHeaderBytes(headerBytes, extension, totalLengthBytes, includeLyrics)
    }

    /**
     * Pure in-memory parser for container headers — directly unit-testable without disk I/O.
     */
    fun parseHeaderBytes(
        bytes: ByteArray,
        extension: String = "",
        totalLengthBytes: Long = bytes.size.toLong(),
        includeLyrics: Boolean = true,
    ): ExtractedAudioMetadata {
        if (bytes.size < 12) return ExtractedAudioMetadata()

        // Handle ID3v2 prefix (can appear on MP3, WAV, AIFF, or even prepended to FLAC)
        var id3Meta: ExtractedAudioMetadata? = null
        var offset = 0
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val (parsed, id3TotalSize) = parseId3v2(bytes, includeLyrics)
            id3Meta = parsed
            if (id3TotalSize in 10 until bytes.size) {
                offset = id3TotalSize
            }
        }

        val containerMeta = when {
            matchesAscii(bytes, offset, "fLaC") ->
                parseFlac(bytes, offset, includeLyrics)
            matchesAscii(bytes, offset, "RIFF") && matchesAscii(bytes, offset + 8, "WAVE") ->
                parseWav(bytes, offset, totalLengthBytes)
            matchesAscii(bytes, offset + 4, "ftyp") ->
                parseMp4(bytes, offset, includeLyrics)
            else -> ExtractedAudioMetadata()
        }

        return when {
            id3Meta != null && containerMeta.source.isNotBlank() -> mergeMetadata(containerMeta, id3Meta)
            containerMeta.source.isNotBlank() -> containerMeta
            id3Meta != null -> id3Meta
            else -> ExtractedAudioMetadata()
        }
    }

    fun readSidecarLrc(audioFile: File): String? {
        val parent = audioFile.parentFile ?: return null
        val base = audioFile.nameWithoutExtension
        val candidates = listOf(
            File(parent, "$base.lrc"),
            File(parent, "$base.LRC"),
            File(parent, "$base.txt"),
        )
        for (lrc in candidates) {
            if (lrc.isFile && lrc.canRead() && lrc.length() in 16L..(512 * 1024L)) {
                val text = runCatching { lrc.readText(Charsets.UTF_8).trim() }.getOrNull()
                if (!text.isNullOrBlank()) {
                    // For .txt only accept if it looks like LRC timestamps
                    if (lrc.extension.equals("lrc", ignoreCase = true) || text.contains("[0")) {
                        return text
                    }
                }
            }
        }
        return null
    }

    private fun parseFlac(
        bytes: ByteArray,
        startOffset: Int,
        includeLyrics: Boolean,
    ): ExtractedAudioMetadata {
        var pos = startOffset + 4 // skip "fLaC"
        var sampleRate = 0
        var channels = 0
        var bitDepth = 0
        var durationMs = 0L
        var title = ""
        var artist = ""
        var albumArtist = ""
        var album = ""
        var syncedLyrics: String? = null
        var plainLyrics: String? = null

        while (pos + 4 <= bytes.size) {
            val headerByte = bytes[pos].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val blockLen = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            pos += 4

            if (blockLen < 0 || pos + blockLen > bytes.size) {
                // If VORBIS_COMMENT was partially truncated at 256 KB, stop safely
                break
            }

            when (blockType) {
                0 -> { // STREAMINFO (34 bytes)
                    if (blockLen >= 18) {
                        // Bytes 10..17 contain:
                        // sampleRate (20 bits) | channels-1 (3 bits) | bps-1 (5 bits) | totalSamples (36 bits)
                        val b10 = bytes[pos + 10].toLong() and 0xFF
                        val b11 = bytes[pos + 11].toLong() and 0xFF
                        val b12 = bytes[pos + 12].toLong() and 0xFF
                        val b13 = bytes[pos + 13].toLong() and 0xFF
                        val b14 = bytes[pos + 14].toLong() and 0xFF
                        val b15 = bytes[pos + 15].toLong() and 0xFF
                        val b16 = bytes[pos + 16].toLong() and 0xFF
                        val b17 = bytes[pos + 17].toLong() and 0xFF

                        sampleRate = ((b10 shl 12) or (b11 shl 4) or (b12 ushr 4)).toInt()
                        channels = (((b12 ushr 1) and 0x07) + 1).toInt()
                        bitDepth = ((((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1).toInt()
                        val totalSamples = ((b13 and 0x0F) shl 32) or
                            (b14 shl 24) or (b15 shl 16) or (b16 shl 8) or b17
                        if (sampleRate > 0 && totalSamples > 0L) {
                            durationMs = (totalSamples * 1000L) / sampleRate
                        }
                    }
                }
                4 -> { // VORBIS_COMMENT (little-endian length-prefixed UTF-8 strings)
                    var cPos = pos
                    val end = pos + blockLen
                    if (cPos + 4 <= end) {
                        val vendorLen = readIntLE(bytes, cPos)
                        cPos += 4 + vendorLen.coerceAtLeast(0)
                    }
                    if (cPos + 4 <= end) {
                        val commentCount = readIntLE(bytes, cPos).coerceIn(0, 512)
                        cPos += 4
                        for (i in 0 until commentCount) {
                            if (cPos + 4 > end) break
                            val len = readIntLE(bytes, cPos)
                            cPos += 4
                            if (len <= 0 || cPos + len > end) {
                                cPos += len.coerceAtLeast(0)
                                continue
                            }
                            val entry = String(bytes, cPos, len, Charsets.UTF_8)
                            cPos += len
                            val eq = entry.indexOf('=')
                            if (eq <= 0) continue
                            val key = entry.substring(0, eq).trim().uppercase(Locale.US)
                            val value = entry.substring(eq + 1).trim()
                            if (value.isBlank()) continue
                            when (key) {
                                "TITLE" -> if (title.isBlank()) title = value
                                "ARTIST", "PERFORMER" -> {
                                    artist = if (artist.isBlank()) value else if (!artist.contains(value, true)) "$artist, $value" else artist
                                }
                                "ALBUMARTIST", "ALBUM ARTIST" -> if (albumArtist.isBlank()) albumArtist = value
                                "ALBUM" -> if (album.isBlank()) album = value
                                "SYNCEDLYRICS", "SYNCED_LYRICS" -> if (includeLyrics) syncedLyrics = value
                                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "LYRICS:ENG" -> {
                                    if (includeLyrics) {
                                        if (value.contains("[0")) syncedLyrics = value else plainLyrics = value
                                    }
                                }
                            }
                        }
                    }
                }
            }

            pos += blockLen
            if (isLast) break
        }

        val resolvedArtist = artist.ifBlank { albumArtist }
        val lyrics = syncedLyrics ?: plainLyrics
        return ExtractedAudioMetadata(
            title = title,
            artist = resolvedArtist,
            album = album,
            durationMs = durationMs,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            embeddedLyrics = lyrics,
            source = "FLAC_HEADER",
        )
    }

    private fun parseId3v2(
        bytes: ByteArray,
        includeLyrics: Boolean,
    ): Pair<ExtractedAudioMetadata, Int> {
        if (bytes.size < 10) return ExtractedAudioMetadata() to 0
        val majorVersion = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = readSyncSafeInt(bytes, 6)
        val totalSize = 10 + tagSize
        if (majorVersion !in 3..4 || tagSize <= 0) {
            return ExtractedAudioMetadata() to totalSize
        }

        var pos = 10
        // Skip extended header if present
        if ((flags and 0x40) != 0 && pos + 4 <= bytes.size) {
            val extSize = if (majorVersion == 4) readSyncSafeInt(bytes, pos) else readIntBE(bytes, pos)
            pos += if (majorVersion == 4) extSize.coerceAtLeast(4) else (extSize + 4).coerceAtLeast(4)
        }

        val limit = minOf(totalSize, bytes.size)
        var title = ""
        var artist = ""
        var albumArtist = ""
        var album = ""
        var durationMs = 0L
        var lyrics: String? = null

        while (pos + 10 <= limit) {
            val frameId = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (frameId[0] == '\u0000' || !frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val frameSize = if (majorVersion == 4) readSyncSafeInt(bytes, pos + 4) else readIntBE(bytes, pos + 4)
            pos += 10
            if (frameSize <= 0 || pos + frameSize > limit) break

            when (frameId) {
                "TIT2" -> title = decodeId3TextFrame(bytes, pos, frameSize).ifBlank { title }
                "TPE1" -> artist = decodeId3TextFrame(bytes, pos, frameSize).ifBlank { artist }
                "TPE2" -> albumArtist = decodeId3TextFrame(bytes, pos, frameSize).ifBlank { albumArtist }
                "TALB" -> album = decodeId3TextFrame(bytes, pos, frameSize).ifBlank { album }
                "TLEN" -> {
                    val ms = decodeId3TextFrame(bytes, pos, frameSize).toLongOrNull() ?: 0L
                    if (ms > 0L) durationMs = ms
                }
                "USLT" -> {
                    if (includeLyrics && lyrics.isNullOrBlank()) {
                        lyrics = decodeId3UsltFrame(bytes, pos, frameSize)
                    }
                }
            }
            pos += frameSize
        }

        return ExtractedAudioMetadata(
            title = title,
            artist = artist.ifBlank { albumArtist },
            album = album,
            durationMs = durationMs,
            embeddedLyrics = lyrics,
            source = "ID3V2",
        ) to totalSize
    }

    private fun parseMp4(
        bytes: ByteArray,
        startOffset: Int,
        includeLyrics: Boolean,
    ): ExtractedAudioMetadata {
        var title = ""
        var artist = ""
        var albumArtist = ""
        var album = ""
        var durationMs = 0L
        var lyrics: String? = null

        fun walkBoxes(start: Int, end: Int, depth: Int) {
            if (depth > 8) return
            var pos = start
            while (pos + 8 <= end) {
                val size = readIntBE(bytes, pos)
                val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
                val boxEnd = if (size == 0) end else (pos + size).coerceAtMost(end)
                if (size < 8 || boxEnd <= pos) break
                val contentStart = pos + 8

                when (type) {
                    "moov", "udta", "ilst", "trak", "mdia" -> walkBoxes(contentStart, boxEnd, depth + 1)
                    "meta" -> if (contentStart + 4 <= boxEnd) walkBoxes(contentStart + 4, boxEnd, depth + 1)
                    "mvhd" -> {
                        if (contentStart + 20 <= boxEnd) {
                            val version = bytes[contentStart].toInt() and 0xFF
                            if (version == 0 && contentStart + 20 <= boxEnd) {
                                val timescale = readIntBE(bytes, contentStart + 12).toLong() and 0xFFFFFFFFL
                                val duration = readIntBE(bytes, contentStart + 16).toLong() and 0xFFFFFFFFL
                                if (timescale > 0L && duration > 0L) {
                                    durationMs = (duration * 1000L) / timescale
                                }
                            } else if (version == 1 && contentStart + 32 <= boxEnd) {
                                val timescale = readIntBE(bytes, contentStart + 20).toLong() and 0xFFFFFFFFL
                                val duration = readLongBE(bytes, contentStart + 24)
                                if (timescale > 0L && duration > 0L) {
                                    durationMs = (duration * 1000L) / timescale
                                }
                            }
                        }
                    }
                    "\u00A9nam" -> title = extractMp4DataAtomText(bytes, contentStart, boxEnd).ifBlank { title }
                    "\u00A9ART" -> artist = extractMp4DataAtomText(bytes, contentStart, boxEnd).ifBlank { artist }
                    "aART" -> albumArtist = extractMp4DataAtomText(bytes, contentStart, boxEnd).ifBlank { albumArtist }
                    "\u00A9alb" -> album = extractMp4DataAtomText(bytes, contentStart, boxEnd).ifBlank { album }
                    "\u00A9lyr" -> if (includeLyrics) {
                        lyrics = extractMp4DataAtomText(bytes, contentStart, boxEnd).takeIf { it.isNotBlank() } ?: lyrics
                    }
                }
                pos = boxEnd
            }
        }

        walkBoxes(startOffset, bytes.size, 0)
        return ExtractedAudioMetadata(
            title = title,
            artist = artist.ifBlank { albumArtist },
            album = album,
            durationMs = durationMs,
            embeddedLyrics = lyrics,
            source = "MP4_ATOM",
        )
    }

    private fun extractMp4DataAtomText(bytes: ByteArray, start: Int, end: Int): String {
        var pos = start
        while (pos + 16 <= end) {
            val size = readIntBE(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val boxEnd = (pos + size).coerceAtMost(end)
            if (size < 16 || boxEnd <= pos) break
            if (type == "data") {
                val payloadStart = pos + 16
                if (payloadStart < boxEnd) {
                    return String(bytes, payloadStart, boxEnd - payloadStart, Charsets.UTF_8)
                        .trim('\u0000', ' ', '\n', '\r')
                }
            }
            pos = boxEnd
        }
        return ""
    }

    private fun parseWav(
        bytes: ByteArray,
        startOffset: Int,
        totalLengthBytes: Long,
    ): ExtractedAudioMetadata {
        var pos = startOffset + 12
        var sampleRate = 0
        var channels = 0
        var bitDepth = 0
        var byteRate = 0
        var dataSize = 0L
        var title = ""
        var artist = ""
        var album = ""

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.ISO_8859_1)
            val chunkSize = readIntLE(bytes, pos + 4)
            pos += 8
            if (chunkSize < 0) break

            when (chunkId) {
                "fmt " -> {
                    if (pos + 16 <= bytes.size) {
                        channels = readShortLE(bytes, pos + 2)
                        sampleRate = readIntLE(bytes, pos + 4)
                        byteRate = readIntLE(bytes, pos + 8)
                        bitDepth = readShortLE(bytes, pos + 14)
                    }
                }
                "data" -> {
                    dataSize = chunkSize.toLong() and 0xFFFFFFFFL
                }
                "LIST" -> {
                    val listEnd = (pos + chunkSize).coerceAtMost(bytes.size)
                    if (pos + 4 <= listEnd && matchesAscii(bytes, pos, "INFO")) {
                        var sub = pos + 4
                        while (sub + 8 <= listEnd) {
                            val subId = String(bytes, sub, 4, Charsets.ISO_8859_1)
                            val subSize = readIntLE(bytes, sub + 4)
                            sub += 8
                            if (subSize <= 0 || sub + subSize > listEnd) break
                            val text = String(bytes, sub, subSize, Charsets.UTF_8).trim('\u0000', ' ')
                            when (subId) {
                                "INAM" -> title = text
                                "IART" -> artist = text
                                "IPRD" -> album = text
                            }
                            sub += subSize + (subSize and 1)
                        }
                    }
                }
            }
            val next = pos + chunkSize + (chunkSize and 1)
            if (next <= pos || next > bytes.size) break
            pos = next
        }

        val effectiveData = if (dataSize > 0L) dataSize else (totalLengthBytes - 44L).coerceAtLeast(0L)
        val durationMs = if (byteRate > 0 && effectiveData > 0L) (effectiveData * 1000L) / byteRate else 0L

        return ExtractedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateBps = byteRate * 8,
            channels = channels,
            source = "WAV_HEADER",
        )
    }

    private fun extractViaMediaMetadataRetriever(
        configure: MediaMetadataRetriever.() -> Unit,
    ): ExtractedAudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.configure()
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty().trim()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty().trim()
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val sampleRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else 0
            val bitDepth = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            } else 0
            ExtractedAudioMetadata(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                bitrateBps = bitrate,
                source = "MEDIA_RETRIEVER",
            )
        } catch (_: Throwable) {
            ExtractedAudioMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun mergeMetadata(
        primary: ExtractedAudioMetadata,
        fallback: ExtractedAudioMetadata,
    ): ExtractedAudioMetadata = ExtractedAudioMetadata(
        title = primary.title.ifBlank { fallback.title },
        artist = primary.artist.ifBlank { fallback.artist },
        album = primary.album.ifBlank { fallback.album },
        durationMs = if (primary.durationMs > 0L) primary.durationMs else fallback.durationMs,
        sampleRateHz = if (primary.sampleRateHz > 0) primary.sampleRateHz else fallback.sampleRateHz,
        bitDepth = if (primary.bitDepth > 0) primary.bitDepth else fallback.bitDepth,
        bitrateBps = if (primary.bitrateBps > 0) primary.bitrateBps else fallback.bitrateBps,
        channels = if (primary.channels > 0) primary.channels else fallback.channels,
        embeddedLyrics = primary.embeddedLyrics ?: fallback.embeddedLyrics,
        source = primary.source.ifBlank { fallback.source },
    )

    private fun decodeId3TextFrame(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 1) return ""
        val encoding = bytes[offset].toInt() and 0xFF
        return decodeId3String(bytes, offset + 1, length - 1, encoding)
            .replace('\u0000', ',')
            .trim(',', ' ', '\r', '\n')
    }

    private fun decodeId3UsltFrame(bytes: ByteArray, offset: Int, length: Int): String? {
        if (length <= 5) return null
        val encoding = bytes[offset].toInt() and 0xFF
        // Bytes 1..3 = 3-char ISO-639-2 language code, then null-terminated content descriptor, then lyrics
        var pos = offset + 4
        val end = offset + length
        val isUtf16 = encoding == 1 || encoding == 2
        if (isUtf16) {
            while (pos + 1 < end) {
                if (bytes[pos] == 0.toByte() && bytes[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < end) {
                if (bytes[pos] == 0.toByte()) {
                    pos += 1
                    break
                }
                pos++
            }
        }
        if (pos >= end) return null
        return decodeId3String(bytes, pos, end - pos, encoding).trim('\u0000', ' ', '\r', '\n')
            .takeIf { it.isNotBlank() }
    }

    private fun decodeId3String(bytes: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (length <= 0) return ""
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return runCatching { String(bytes, offset, length, charset) }.getOrDefault("")
    }

    private fun readUpTo(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < maxBytes) {
            val want = minOf(buf.size, maxBytes - total)
            val read = stream.read(buf, 0, want)
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun matchesAscii(bytes: ByteArray, offset: Int, ascii: String): Boolean {
        if (offset < 0 || offset + ascii.length > bytes.size) return false
        for (i in ascii.indices) {
            if (bytes[offset + i] != ascii[i].code.toByte()) return false
        }
        return true
    }

    private fun readSyncSafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun readLongBE(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
}
