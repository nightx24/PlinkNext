package io.github.augztn.pocketlink.ssdbridge

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class DisconnectSafeResponseTest {

    @Test
    fun brokenPipeDuringStreaming_abortsCleanlyWithoutSevereNanoHttpdLog() {
        val closed = AtomicBoolean(false)
        val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
        val trackedInput = object : InputStream() {
            private val delegate = ByteArrayInputStream(payload)
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
            override fun close() {
                closed.set(true)
                delegate.close()
            }
        }

        val response = DisconnectSafeResponse.fixedLength(
            status = NanoHTTPD.Response.Status.OK,
            mimeType = "video/mp4",
            data = trackedInput,
            totalBytes = payload.size.toLong(),
        )
        response.setGzipEncoding(false)

        val brokenPipeStream = object : OutputStream() {
            private var written = 0
            override fun write(b: Int) {
                written++
                if (written > 4096) throw SocketException("Broken pipe")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (written > 4096) throw SocketException("Broken pipe")
            }
        }

        val severeLogs = mutableListOf<LogRecord>()
        val nanoLogger = Logger.getLogger(NanoHTTPD::class.java.name)
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.SEVERE.intValue()) {
                    severeLogs.add(record)
                }
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        nanoLogger.addHandler(handler)
        try {
            response.send(brokenPipeStream)
        } finally {
            nanoLogger.removeHandler(handler)
        }

        assertTrue("Expected underlying stream to close on client disconnect", closed.get())
        assertTrue("Expected connection to be marked closed after broken pipe", response.isCloseConnection())
        assertTrue("Expected zero SEVERE NanoHTTPD logs, got: ${severeLogs.map { it.message }}", severeLogs.isEmpty())
    }

    @Test
    fun normalResponse_writesHeadersAndFullPayload() {
        val body = """{"status":"ok"}"""
        val response = DisconnectSafeResponse.fixedLength(
            status = NanoHTTPD.Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            text = body,
        )
        response.setGzipEncoding(false)

        val out = ByteArrayOutputStream()
        response.send(out)

        val raw = out.toString(Charsets.UTF_8.name())
        assertTrue(raw.startsWith("HTTP/1.1 200 OK"))
        assertEquals(body, raw.substringAfter("\r\n\r\n"))
    }
}
