package io.github.augztn.pocketlink.ssdbridge

import java.net.InetAddress

/**
 * Allows loopback, private LAN/Wi-Fi/Hotspot networks (RFC 1918 / RFC 3927 / IPv6 Unique-Local & Link-Local),
 * and Tailscale WireGuard CGNAT ranges (100.64.0.0/10 and fd7a:115c:a1e0::/48).
 */
internal object NetworkAccessPolicy {
    fun allowsCleartextPeer(remoteAddress: String?): Boolean {
        val value = remoteAddress?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.substringBefore('%')
            ?.takeIf { it.isNotEmpty() } ?: return false

        val address = try {
            if (value.contains(':')) InetAddress.getByName(value)
            else {
                val octets = value.split('.')
                if (octets.size != 4) return false
                val bytes = octets.map {
                    it.toIntOrNull()?.takeIf { number -> number in 0..255 } ?: return false
                }.map(Int::toByte).toByteArray()
                InetAddress.getByAddress(bytes)
            }
        } catch (_: Exception) {
            return false
        }

        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }

        val bytes = address.address
        if (bytes.size == 4) {
            return isAllowedIpv4(bytes)
        }

        // Some Android/network stacks expose IPv4 peers as IPv4-mapped IPv6.
        if (bytes.size == 16 && isIpv4Mapped(bytes)) {
            return isAllowedIpv4(bytes.copyOfRange(12, 16))
        }

        // Tailscale IPv6 (fd7a:115c:a1e0::/48) or IPv6 Unique Local (fc00::/7) / Link-Local (fe80::/10)
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return true // fc00::/7 Unique Local
            if (first == 0xfe && (second and 0xc0) == 0x80) return true // fe80::/10 Link-Local
            return first == 0xfd &&
                second == 0x7a &&
                (bytes[2].toInt() and 0xff) == 0x11 &&
                (bytes[3].toInt() and 0xff) == 0x5c &&
                (bytes[4].toInt() and 0xff) == 0xa1 &&
                (bytes[5].toInt() and 0xff) == 0xe0
        }
        return false
    }

    private fun isAllowedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return when {
            first == 127 -> true // 127.0.0.0/8 Loopback
            first == 10 -> true // 10.0.0.0/8 Private LAN / Hotspot
            first == 172 && second in 16..31 -> true // 172.16.0.0/12 Private LAN
            first == 192 && second == 168 -> true // 192.168.0.0/16 Private Wi-Fi / LAN / Hotspot
            first == 169 && second == 254 -> true // 169.254.0.0/16 Link-Local
            first == 100 && second in 64..127 -> true // 100.64.0.0/10 Tailscale / CGNAT
            else -> false
        }
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff &&
            (bytes[11].toInt() and 0xff) == 0xff
}
