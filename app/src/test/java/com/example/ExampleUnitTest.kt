package com.example

import com.example.data.model.MediaExtensions
import com.example.data.model.NetworkRouteType
import com.example.data.model.classifyNetworkRoute
import com.example.data.model.formatBytes
import com.example.data.model.formatDurationMs
import com.example.data.model.parseServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun classifyNetworkRoute_detectsLoopbackTailscaleAndHttps() {
        assertEquals(NetworkRouteType.LOCALHOST, classifyNetworkRoute("http://127.0.0.1:8000"))
        assertEquals(NetworkRouteType.LOCALHOST, classifyNetworkRoute("http://localhost:8000"))
        assertEquals(NetworkRouteType.LOCALHOST, classifyNetworkRoute("http://[::1]:8000"))
        assertEquals(NetworkRouteType.TAILSCALE, classifyNetworkRoute("http://100.101.102.103:8000"))
        assertEquals(NetworkRouteType.TAILSCALE, classifyNetworkRoute("http://[fd7a:115c:a1e0::1234]:8000"))
        assertEquals(NetworkRouteType.TAILSCALE, classifyNetworkRoute("http://my-phone.tail1234.ts.net:8000"))
        assertEquals(NetworkRouteType.HTTPS, classifyNetworkRoute("https://files.example.com"))
        assertEquals(NetworkRouteType.PRIVATE_LAN, classifyNetworkRoute("http://192.168.1.50:8000"))
    }

    @Test
    fun parseServerEndpoint_handlesTailscaleIpWithEmbeddedPortAndIpv6() {
        val plain = parseServerEndpoint("100.101.102.10", "8000")
        assertEquals("100.101.102.10", plain.hostForProfile)
        assertEquals(8000, plain.port)
        assertEquals("http://100.101.102.10:8000", plain.baseUrl)

        val pastedHostPort = parseServerEndpoint("100.101.102.10:8000", "8000")
        assertEquals("100.101.102.10", pastedHostPort.hostForProfile)
        assertEquals(8000, pastedHostPort.port)
        assertEquals("http://100.101.102.10:8000", pastedHostPort.baseUrl)

        val fullHttpUrl = parseServerEndpoint("http://100.101.102.10:9000/", "8000")
        assertEquals("100.101.102.10", fullHttpUrl.hostForProfile)
        assertEquals(9000, fullHttpUrl.port)
        assertEquals("http://100.101.102.10:9000", fullHttpUrl.baseUrl)

        val rawIpv6 = parseServerEndpoint("fd7a:115c:a1e0::1234", "8000")
        assertEquals("fd7a:115c:a1e0::1234", rawIpv6.hostForProfile)
        assertEquals(8000, rawIpv6.port)
        assertEquals("http://[fd7a:115c:a1e0::1234]:8000", rawIpv6.baseUrl)

        val httpsMagicDns = parseServerEndpoint("https://my-phone.tail1234.ts.net", "8000")
        assertEquals("https://my-phone.tail1234.ts.net", httpsMagicDns.hostForProfile)
        assertEquals(443, httpsMagicDns.port)
        assertEquals("https://my-phone.tail1234.ts.net", httpsMagicDns.baseUrl)
    }

    @Test
    fun mediaExtensions_andFormatting_workAsExpected() {
        assertTrue(MediaExtensions.isAudio("track.flac"))
        assertTrue(MediaExtensions.isVideo("clip.mkv"))
        assertTrue(MediaExtensions.isPhoto("photo.heic"))
        assertTrue(MediaExtensions.isDocument("report.pdf"))
        assertFalse(MediaExtensions.isAudio("document.pdf"))
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1:05", formatDurationMs(65_000L))
    }
}
