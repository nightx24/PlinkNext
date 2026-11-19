package io.github.augztn.pocketlink.ssdbridge

/** Deterministic identity independent of the current volume enumeration index. */
internal fun externalVolumeIdentity(uuid: String?, directoryPath: String?, description: String): String {
    uuid?.trim()?.takeIf { it.isNotEmpty() }?.let { return "uuid:${it.lowercase()}" }
    directoryPath?.trim()?.takeIf { it.isNotEmpty() }?.let { return "path:$it" }
    return "description:${description.trim().lowercase()}"
}

internal fun externalVolumeKey(identity: String): String {
    val suffix = java.security.MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it) }
    return "usb-$suffix"
}
