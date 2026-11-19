package io.github.augztn.pocketlink.ssdbridge

import java.net.InetAddress

/**
 * Cleartext HTTP is trusted only over loopback or the encrypted Tailscale tunnel.
 * LAN/public peers must use a TLS reverse proxy instead.
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

        if (address.isLoopbackAddress) return true

        val bytes = address.address
        if (bytes.size == 4) return isTailscaleIpv4(bytes)

        // Some Android/network stacks expose IPv4 peers as IPv4-mapped IPv6.
        // Normalize those before applying the Tailscale IPv4 range check.
        if (bytes.size == 16 && isIpv4Mapped(bytes)) {
            return isTailscaleIpv4(bytes.copyOfRange(12, 16))
        }

        // Tailscale IPv6 addresses use fd7a:115c:a1e0::/48.
        return bytes.size == 16 &&
            (bytes[0].toInt() and 0xff) == 0xfd &&
            (bytes[1].toInt() and 0xff) == 0x7a &&
            (bytes[2].toInt() and 0xff) == 0x11 &&
            (bytes[3].toInt() and 0xff) == 0x5c &&
            (bytes[4].toInt() and 0xff) == 0xa1 &&
            (bytes[5].toInt() and 0xff) == 0xe0
    }

    private fun isTailscaleIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 100 && second in 64..127
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.size == 16 &&
            (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xff) == 0xff &&
            (bytes[11].toInt() and 0xff) == 0xff
}
