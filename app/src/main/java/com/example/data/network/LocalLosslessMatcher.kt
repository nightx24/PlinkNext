package com.example.data.network

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.example.data.model.IndexedFile
import com.example.data.model.MediaCategory
import com.example.data.store.AppLogger
import io.github.augztn.pocketlink.ssdbridge.AudioMetadataExtractor
import io.github.augztn.pocketlink.ssdbridge.InternalReadable
import io.github.augztn.pocketlink.ssdbridge.ScopedStorageRegistry
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class LocalLosslessEntry(
    val id: String,
    val fileName: String,
    val titleHint: String,
    val artistHint: String,
    val extension: String,
    val uriString: String,
    val sourceLabel: String, // "Local" or "Host"
    val sizeBytes: Long = 0L,
    val albumHint: String = "",
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val hasEmbeddedLyrics: Boolean = false,
    val metadataSource: String = "FILENAME",
    val hostVolume: String? = null,
    val hostPath: String? = null,
) {
    val formatLabel: String
        get() = extension.uppercase(Locale.US).ifBlank { "LOSSLESS" }

    val qualitySpecLabel: String
        get() {
            val parts = mutableListOf<String>()
            if (bitDepth > 0) parts.add("${bitDepth}-bit")
            if (sampleRateHz > 0) {
                val formatted = if (sampleRateHz % 1000 == 0) {
                    "${sampleRateHz / 1000}kHz"
                } else {
                    String.format(Locale.US, "%.1fkHz", sampleRateHz / 1000.0)
                }
                parts.add(formatted)
            }
            return parts.joinToString("/")
        }

    val badgeSummary: String
        get() = buildString {
            append(sourceLabel.uppercase(Locale.US))
            append(' ')
            append(formatLabel)
            if (qualitySpecLabel.isNotBlank()) {
                append(" · ")
                append(qualitySpecLabel)
            } else {
                append(" · LOSSLESS")
            }
        }
}

data class LocalLosslessMatch(
    val entry: LocalLosslessEntry,
    val confidence: Double,
)

/**
 * Telegram-Music-Addon + BitChord–inspired metadata-driven lossless track matcher for Discover.
 *
 * Extracts real embedded container tags (FLAC STREAMINFO + VORBIS_COMMENT, ID3v2, MP4/ALAC ilst,
 * WAV RIFF/INFO, MediaStore, and Host SQLite index) so files match accurately even when filenames
 * are generic (e.g., "04.flac"), using weighted Title + Multi-Artist + Album + Duration scoring.
 * Matched tracks play directly in lossless quality; unmatched tracks transparently fall back to
 * NewPipe / InnerTube Opus streaming.
 */
object LocalLosslessMatcher {

    val LOSSLESS_EXTENSIONS: Set<String> = setOf(
        "flac", "alac", "wav", "aiff", "aif", "wv", "ape", "dsf", "dff",
    )

    private val FORMAT_PRIORITY: Map<String, Int> = mapOf(
        "flac" to 9,
        "alac" to 8,
        "wav" to 7,
        "aiff" to 6,
        "aif" to 6,
        "wv" to 5,
        "ape" to 4,
        "dsf" to 3,
        "dff" to 3,
    )

    private val NOISE_PARENS_REGEX = Regex(
        "\\s*[(\\[]([^)\\]]*(?:official|audio|video|lyric|lyrics|visualizer|remaster(?:ed)?|hd|hq|4k|mv|live|explicit|clean|deluxe|bonus|flac|lossless|24\\s?-?bit|16\\s?-?bit|hi-?res|feat\\.?|ft\\.?|prod\\.?)[^)\\]]*)[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT_SUFFIX_REGEX = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val LEADING_TRACK_NUM_REGEX = Regex("^\\s*(?:\\d{1,3}\\s*[-._)]\\s*|\\d{2}\\s+)")
    private val MULTI_ARTIST_SPLIT_REGEX = Regex("\\s*(?:,|&|\\band\\b|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bx\\b|×|;|/)\\s*", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9\\s]+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun isLosslessFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in LOSSLESS_EXTENSIONS
    }

    /**
     * Collects all accessible lossless tracks (with embedded audio metadata) from the connected
     * PocketLink host and this device's local directories / MediaStore.
     */
    suspend fun scanLosslessLibrary(
        context: Context,
        activeApi: PocketLinkApi?,
    ): List<LocalLosslessEntry> {
        val entries = LinkedHashMap<String, LocalLosslessEntry>()

        // 1. Connected PocketLink host indexed music (paginated with safe 200-item pages + fallback)
        if (activeApi != null) {
            val hostItems = fetchHostMusicItemsSafely(activeApi)
            hostItems.forEach { item ->
                if (!isLosslessFileName(item.name)) return@forEach
                val ext = item.name.substringAfterLast('.', "").lowercase(Locale.US)
                val resolvedVolume = item.volume.takeIf { it.isNotBlank() && it != "internal" }
                val streamUrl = activeApi.streamUrl(item.path, resolvedVolume)
                val (pathArtist, pathTitle) = extractArtistAndTitleFromPath(item.path, item.name)
                val resolvedTitle = item.title?.takeIf { it.isNotBlank() } ?: pathTitle
                val resolvedArtist = item.artist?.takeIf { it.isNotBlank() } ?: pathArtist
                val hasEmbeddedTags = !item.title.isNullOrBlank() || !item.artist.isNullOrBlank() || !item.album.isNullOrBlank()
                val key = "host::${item.volume}::${item.path}"
                entries[key] = LocalLosslessEntry(
                    id = key,
                    fileName = item.name,
                    titleHint = resolvedTitle,
                    artistHint = resolvedArtist,
                    albumHint = item.album.orEmpty(),
                    durationMs = item.durationMs,
                    sampleRateHz = item.sampleRateHz,
                    bitDepth = item.bitDepth,
                    hasEmbeddedLyrics = item.hasEmbeddedLyrics,
                    metadataSource = if (hasEmbeddedTags) "HOST_TAGS" else "FILENAME",
                    extension = ext,
                    uriString = streamUrl,
                    sourceLabel = "Host",
                    sizeBytes = item.size,
                    hostVolume = item.volume,
                    hostPath = item.path,
                )
            }
        }

        // 2. Device MediaStore audio entries (enriched with FLAC/MP4/WAV header specs & embedded tags)
        runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val fileName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    if (!isLosslessFileName(fileName)) continue
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
                    val msTitle = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty().trim() else ""
                    val msArtist = if (artistIdx >= 0) cursor.getString(artistIdx).orEmpty().trim() else ""
                    val msAlbum = if (albumIdx >= 0) cursor.getString(albumIdx).orEmpty().trim() else ""
                    val msDuration = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L

                    // Parse binary header (FLAC STREAMINFO / VORBIS_COMMENT / MP4 / WAV) for exact specs & lyrics
                    val headerMeta = AudioMetadataExtractor.extractFromUri(
                        context = context,
                        uri = contentUri,
                        fileName = fileName,
                        sizeBytes = size,
                        includeLyrics = true,
                    )
                    val (pathArtist, pathTitle) = extractArtistAndTitleFromPath("", fileName)

                    val cleanTitle = headerMeta.title.ifBlank { msTitle.ifBlank { pathTitle } }
                    val cleanArtist = headerMeta.artist
                        .ifBlank { msArtist.takeUnless { it.equals("<unknown>", true) }.orEmpty() }
                        .ifBlank { pathArtist }
                    val cleanAlbum = headerMeta.album
                        .ifBlank { msAlbum.takeUnless { it.equals("<unknown>", true) }.orEmpty() }
                    val durationMs = if (headerMeta.durationMs > 0L) headerMeta.durationMs else msDuration
                    val metaSrc = when {
                        headerMeta.hasRichTags -> headerMeta.source
                        msTitle.isNotBlank() || msArtist.isNotBlank() -> "MEDIASTORE"
                        else -> "FILENAME"
                    }

                    val key = "local_ms::${fileName.lowercase(Locale.US)}::$size"
                    if (key !in entries) {
                        entries[key] = LocalLosslessEntry(
                            id = key,
                            fileName = fileName,
                            titleHint = cleanTitle,
                            artistHint = cleanArtist,
                            albumHint = cleanAlbum,
                            durationMs = durationMs,
                            sampleRateHz = headerMeta.sampleRateHz,
                            bitDepth = headerMeta.bitDepth,
                            hasEmbeddedLyrics = headerMeta.hasEmbeddedLyrics,
                            metadataSource = metaSrc,
                            extension = ext,
                            uriString = contentUri.toString(),
                            sourceLabel = "Local",
                            sizeBytes = size,
                        )
                    }
                }
            }
        }

        // 3. PocketLink sandbox & readable local Music/Download folders (extracts real container headers!)
        runCatching {
            val registry = ScopedStorageRegistry(context)
            val dirsToScan = linkedSetOf<File>()
            dirsToScan.add(registry.sandboxRootDir())
            if (registry.hasFullFilesAccess()) {
                val extRoot = registry.externalStorageDir()
                listOf("Music", "Download", "Downloads", "Audio", "FLAC", "Lossless").forEach { name ->
                    val candidate = File(extRoot, name)
                    if (candidate.isDirectory && candidate.canRead()) dirsToScan.add(candidate)
                }
            } else {
                listOf(Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_DOWNLOADS).forEach { dirName ->
                    val pub = Environment.getExternalStoragePublicDirectory(dirName)
                    if (pub != null && pub.isDirectory && pub.canRead()) dirsToScan.add(pub)
                }
            }

            for (root in dirsToScan) {
                scanDirectoryForLossless(root, depth = 0, maxDepth = 4) { file ->
                    val ext = file.extension.lowercase(Locale.US)
                    val key = "local_file::${file.absolutePath}"
                    if (key !in entries) {
                        val meta = AudioMetadataExtractor.extractFromFile(file, includeLyrics = true)
                        val (pathArtist, pathTitle) = extractArtistAndTitleFromPath(file.parentFile?.name.orEmpty(), file.name)
                        entries[key] = LocalLosslessEntry(
                            id = key,
                            fileName = file.name,
                            titleHint = meta.title.ifBlank { pathTitle },
                            artistHint = meta.artist.ifBlank { pathArtist },
                            albumHint = meta.album,
                            durationMs = meta.durationMs,
                            sampleRateHz = meta.sampleRateHz,
                            bitDepth = meta.bitDepth,
                            hasEmbeddedLyrics = meta.hasEmbeddedLyrics,
                            metadataSource = if (meta.hasRichTags) meta.source else "FILENAME",
                            extension = ext,
                            uriString = Uri.fromFile(file).toString(),
                            sourceLabel = "Local",
                            sizeBytes = file.length(),
                        )
                    }
                }
            }

            // Also check any SAF-mounted folders in PocketLink (e.g., Music or Lossless mounted via SAF)
            listOf("Music", "Download").forEach { mount ->
                val listed = registry.listDirectory(mount).orEmpty()
                listed.filter { !it.isDirectory && isLosslessFileName(it.name) }.forEach { entry ->
                    val readable = registry.resolveReadable("$mount/${entry.name}")
                    val (uriStr, extracted) = when (readable) {
                        is InternalReadable.LocalFile ->
                            Uri.fromFile(readable.file).toString() to AudioMetadataExtractor.extractFromFile(readable.file, includeLyrics = true)
                        is InternalReadable.SafDoc ->
                            readable.doc.uri.toString() to AudioMetadataExtractor.extractFromUri(context, readable.doc.uri, entry.name, entry.size, includeLyrics = true)
                        is InternalReadable.ContentItem ->
                            readable.uri.toString() to AudioMetadataExtractor.extractFromUri(context, readable.uri, entry.name, entry.size, includeLyrics = true)
                        null -> return@forEach
                    }
                    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
                    val (pathArtist, pathTitle) = extractArtistAndTitleFromPath(mount, entry.name)
                    val key = "local_saf::$mount/${entry.name}"
                    if (key !in entries) {
                        entries[key] = LocalLosslessEntry(
                            id = key,
                            fileName = entry.name,
                            titleHint = extracted.title.ifBlank { pathTitle },
                            artistHint = extracted.artist.ifBlank { pathArtist },
                            albumHint = extracted.album,
                            durationMs = extracted.durationMs,
                            sampleRateHz = extracted.sampleRateHz,
                            bitDepth = extracted.bitDepth,
                            hasEmbeddedLyrics = extracted.hasEmbeddedLyrics,
                            metadataSource = if (extracted.hasRichTags) extracted.source else "FILENAME",
                            extension = ext,
                            uriString = uriStr,
                            sourceLabel = "Local",
                            sizeBytes = entry.size,
                        )
                    }
                }
            }
        }

        return entries.values.toList()
    }

    /**
     * Fetches music items from the connected PocketLink host in safe 200-item pages
     * (compatible with both native Android hosts and older/FastAPI hosts with lower max limits).
     */
    private suspend fun fetchHostMusicItemsSafely(api: PocketLinkApi): List<IndexedFile> {
        val collected = mutableListOf<IndexedFile>()
        var pageSize = 200
        var offset = 0
        for (page in 0 until 5) {
            val result = runCatching {
                api.indexedMedia(MediaCategory.MUSIC, limit = pageSize, offset = offset).items
            }
            val items = result.getOrElse { err ->
                if (page == 0 && pageSize > 100) {
                    pageSize = 100
                    runCatching {
                        api.indexedMedia(MediaCategory.MUSIC, limit = pageSize, offset = 0).items
                    }.getOrElse { fallbackErr ->
                        AppLogger.warning("Host music index fetch fallback", fallbackErr.message ?: err.message)
                        // Final fallback: search general file index for .flac files
                        runCatching { api.searchIndexedFiles(query = ".flac", limit = 100, offset = 0).items }
                            .getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
            }
            if (items.isEmpty()) break
            collected.addAll(items)
            if (items.size < pageSize) break
            offset += items.size
        }
        return collected
    }

    private fun scanDirectoryForLossless(
        dir: File,
        depth: Int,
        maxDepth: Int,
        onFound: (File) -> Unit,
    ) {
        if (depth > maxDepth || !dir.isDirectory || !dir.canRead()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                scanDirectoryForLossless(child, depth + 1, maxDepth, onFound)
            } else if (child.isFile && isLosslessFileName(child.name)) {
                onFound(child)
            }
        }
    }

    /**
     * Multi-signal metadata matcher: evaluates embedded Title, multi-artist splits,
     * Album match, Duration alignment, and fuzzy Levenshtein title similarity.
     * Returns `null` if no high-confidence match exists (falling back to NewPipe Opus).
     */
    fun findBestMatch(
        track: YtTrack,
        library: List<LocalLosslessEntry>,
    ): LocalLosslessMatch? {
        if (library.isEmpty()) return null
        val targetTitle = normalizeForMatch(track.title)
        if (targetTitle.length < 2) return null

        val rawTargetArtist = track.artist.takeUnless {
            it.equals("Discover", ignoreCase = true) || it.equals("YouTube Music", ignoreCase = true)
        }.orEmpty()
        val targetArtist = normalizeForMatch(rawTargetArtist)
        val targetArtistParts = splitNormalizedArtists(rawTargetArtist)
        val targetAlbum = normalizeForMatch(track.album)
        val targetDurationMs = YtMusicClient.parseDurationTextToMs(track.durationText)

        val targetTitleTokens = tokenize(targetTitle)
        val targetArtistTokens = tokenize(targetArtist)
        val targetCombinedTokens = targetTitleTokens + targetArtistTokens

        var best: LocalLosslessMatch? = null

        for (entry in library) {
            val candTitle = normalizeForMatch(entry.titleHint)
            val candArtist = normalizeForMatch(entry.artistHint)
            val candArtistParts = splitNormalizedArtists(entry.artistHint)
            val candAlbum = normalizeForMatch(entry.albumHint)
            val candBaseName = normalizeForMatch(entry.fileName.substringBeforeLast('.'))
            if (candTitle.isEmpty() && candBaseName.isEmpty()) continue

            val effectiveCandTitle = candTitle.ifEmpty { candBaseName }
            val candTitleTokens = tokenize(effectiveCandTitle)
            val candArtistTokens = tokenize(candArtist)
            val candAllTokens = tokenize("$candBaseName $candTitle $candArtist")

            val titleExact = candTitle == targetTitle || candBaseName == targetTitle
            val titleJaccard = jaccard(targetTitleTokens, candTitleTokens)
            val titleFuzzy = if (!titleExact && targetTitle.length >= 4 && effectiveCandTitle.length >= 4) {
                normalizedLevenshteinSimilarity(targetTitle, effectiveCandTitle)
            } else 0.0
            val combinedJaccard = if (targetArtistTokens.isNotEmpty()) {
                jaccard(targetCombinedTokens, candAllTokens)
            } else {
                titleJaccard
            }

            // Multi-artist aware check (e.g., "Daft Punk, Pharrell Williams" matches "Daft Punk")
            val artistPartMatch = targetArtistParts.isNotEmpty() && candArtistParts.isNotEmpty() &&
                targetArtistParts.any { tPart ->
                    candArtistParts.any { cPart ->
                        tPart == cPart ||
                            (tPart.length >= 3 && cPart.contains(tPart)) ||
                            (cPart.length >= 3 && tPart.contains(cPart))
                    }
                }

            val artistMatched = artistPartMatch || (targetArtistTokens.isNotEmpty() && (
                (candArtistTokens.isNotEmpty() && (
                    tokenOverlapRatio(targetArtistTokens, candArtistTokens) >= 0.5 ||
                        tokenOverlapRatio(candArtistTokens, targetArtistTokens) >= 0.5
                    )) ||
                    tokenOverlapRatio(targetArtistTokens, candAllTokens) >= 0.5
                ))

            val artistConflict = targetArtistTokens.isNotEmpty() &&
                candArtistTokens.isNotEmpty() &&
                !artistMatched

            var score: Double = when {
                titleExact && artistMatched -> 1.0
                titleExact && !artistConflict -> 0.88
                combinedJaccard >= 0.82 && !artistConflict -> 0.90 + (combinedJaccard - 0.82) * 0.4
                titleJaccard >= 0.80 && artistMatched -> 0.86 + (titleJaccard - 0.80) * 0.4
                titleFuzzy >= 0.88 && artistMatched -> 0.84 + (titleFuzzy - 0.88) * 0.6
                titleJaccard >= 0.92 && !artistConflict -> 0.80
                titleFuzzy >= 0.92 && !artistConflict -> 0.79
                else -> 0.0
            }

            if (score <= 0.0) continue

            // Album alignment bonus when both sides carry album tags
            if (targetAlbum.length >= 3 && candAlbum.length >= 3) {
                val albumTokensA = tokenize(targetAlbum)
                val albumTokensB = tokenize(candAlbum)
                if (targetAlbum == candAlbum || jaccard(albumTokensA, albumTokensB) >= 0.6) {
                    score += 0.08
                }
            }

            // Duration alignment bonus & version-mismatch guard (Studio vs Remix/Live/Short Edit)
            if (targetDurationMs > 15_000L && entry.durationMs > 15_000L) {
                val diffMs = abs(targetDurationMs - entry.durationMs)
                score += when {
                    diffMs <= 5_000L -> 0.10
                    diffMs <= 12_000L -> 0.04
                    diffMs > 45_000L -> -0.25
                    else -> 0.0
                }
            }

            if (score < MATCH_THRESHOLD) continue

            val qualityScore = hiResRank(entry)
            val currentBest = best
            val isBetter = currentBest == null ||
                score > currentBest.confidence + 0.02 ||
                (abs(score - currentBest.confidence) <= 0.02 && qualityScore > hiResRank(currentBest.entry))
            if (isBetter) {
                best = LocalLosslessMatch(entry = entry, confidence = score.coerceAtMost(1.15))
            }
        }

        return best
    }

    private fun hiResRank(entry: LocalLosslessEntry): Long {
        val formatRank = (FORMAT_PRIORITY[entry.extension.lowercase(Locale.US)] ?: 1).toLong()
        val bitBonus = entry.bitDepth.coerceAtLeast(0).toLong() * 100_000L
        val rateBonus = entry.sampleRateHz.coerceAtLeast(0).toLong()
        return (formatRank * 10_000_000L) + bitBonus + rateBonus
    }

    /**
     * Converts a Discover [YtTrack] into a playable [IndexedFile], substituting a matched
     * local/host lossless file (with rich metadata & audiophile badge) when [enabled] is true,
     * or falling back cleanly to NewPipe Opus streaming (`pocketlink-yt://...`) otherwise.
     */
    fun resolveTrackForPlayback(
        track: YtTrack,
        enabled: Boolean,
        library: List<LocalLosslessEntry>,
    ): IndexedFile {
        if (!enabled || library.isEmpty()) return track.toIndexedFile()
        val match = findBestMatch(track, library) ?: return track.toIndexedFile()
        val entry = match.entry
        val resolvedDurationMs = entry.durationMs.takeIf { it > 0L }
            ?: YtMusicClient.parseDurationTextToMs(track.durationText)
        return IndexedFile(
            volume = VOLUME_LOCAL_LOSSLESS,
            path = track.videoId,
            name = track.title,
            type = "file",
            size = entry.sizeBytes,
            modified = "",
            mime = mimeForLosslessExtension(entry.extension),
            category = "music",
            artist = track.artist.ifBlank { entry.artistHint.ifBlank { "Local Lossless" } },
            artworkUrl = track.thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg" },
            streamUri = entry.uriString,
            durationText = track.durationText,
            localLosslessBadge = entry.badgeSummary,
            title = entry.titleHint.ifBlank { track.title },
            album = entry.albumHint.ifBlank { track.album }.takeIf { it.isNotBlank() },
            durationMs = resolvedDurationMs,
            sampleRateHz = entry.sampleRateHz,
            bitDepth = entry.bitDepth,
            hasEmbeddedLyrics = entry.hasEmbeddedLyrics,
            sourceVolume = entry.hostVolume,
            sourceFilePath = entry.hostPath,
        )
    }

    internal fun normalizeForMatch(raw: String): String {
        if (raw.isBlank()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return decomposed
            .let { LEADING_TRACK_NUM_REGEX.replace(it, "") }
            .let { NOISE_PARENS_REGEX.replace(it, " ") }
            .let { FEAT_SUFFIX_REGEX.replace(it, "") }
            .lowercase(Locale.US)
            .let { NON_ALNUM_REGEX.replace(it, " ") }
            .let { MULTI_SPACE_REGEX.replace(it, " ") }
            .trim()
    }

    private fun splitNormalizedArtists(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(MULTI_ARTIST_SPLIT_REGEX)
            .map { normalizeForMatch(it) }
            .filter { it.length >= 2 }
            .distinct()
    }

    internal fun extractArtistAndTitleFromPath(path: String, fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()
        val cleanedBase = LEADING_TRACK_NUM_REGEX.replace(base, "").trim()
        val dashIdx = cleanedBase.indexOf(" - ")
        if (dashIdx > 0 && dashIdx < cleanedBase.length - 3) {
            val left = cleanedBase.substring(0, dashIdx).trim()
            val right = cleanedBase.substring(dashIdx + 3).trim()
            if (left.isNotEmpty() && right.isNotEmpty()) {
                return left to right
            }
        }
        val parentFolder = path.trimEnd('/').substringBeforeLast('/', "").substringAfterLast('/', "")
        val parentHint = parentFolder.takeUnless {
            it.equals("Music", true) || it.equals("Download", true) || it.equals("Downloads", true) || it.equals("FLAC", true)
        }.orEmpty()
        return parentHint to cleanedBase
    }

    private fun tokenize(normalized: String): Set<String> =
        normalized.split(' ')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else inter / union
    }

    private fun tokenOverlapRatio(needle: Set<String>, haystack: Set<String>): Double {
        if (needle.isEmpty() || haystack.isEmpty()) return 0.0
        val hits = needle.count { it in haystack }
        return hits.toDouble() / needle.size.toDouble()
    }

    private fun normalizedLevenshteinSimilarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        if (abs(a.length - b.length) > maxLen / 2) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return 1.0 - (prev[b.length].toDouble() / maxLen.toDouble())
    }

    private fun mimeForLosslessExtension(ext: String): String = when (ext.lowercase(Locale.US)) {
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "alac" -> "audio/mp4"
        "aiff", "aif" -> "audio/aiff"
        "wv" -> "audio/x-wavpack"
        "ape" -> "audio/x-ape"
        else -> "audio/flac"
    }

    const val VOLUME_LOCAL_LOSSLESS = "local_lossless"
    private const val MATCH_THRESHOLD = 0.78
}
