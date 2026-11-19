package io.github.augztn.pocketlink.ssdbridge

internal data class ExternalVolumeCapacity(
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long,
    val usagePercent: Double,
)

/** Extracts the UUID portion of an external-storage document ID, excluding primary storage. */
internal fun externalStorageVolumeId(documentId: String): String? {
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    return documentId.substring(0, separator)
        .trim()
        .takeIf { it.isNotEmpty() && !it.equals("primary", ignoreCase = true) }
}

/** Builds capacity values only from valid filesystem totals and available-byte readings. */
internal fun externalVolumeCapacity(
    totalBytes: Long,
    availableBytes: Long,
): ExternalVolumeCapacity? {
    if (totalBytes <= 0L || availableBytes < 0L || availableBytes > totalBytes) return null
    val usedBytes = totalBytes - availableBytes
    return ExternalVolumeCapacity(
        totalBytes = totalBytes,
        usedBytes = usedBytes,
        availableBytes = availableBytes,
        usagePercent = usedBytes.toDouble() * 100.0 / totalBytes.toDouble(),
    )
}
