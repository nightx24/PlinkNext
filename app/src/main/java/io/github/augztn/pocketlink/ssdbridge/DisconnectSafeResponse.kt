package io.github.augztn.pocketlink.ssdbridge

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A NanoHTTPD [NanoHTTPD.Response] subclass that gracefully handles client disconnects
 * (such as `SocketException: Broken pipe` or `Connection reset by peer` when ExoPlayer seeks,
 * skips tracks, or Coil cancels an in-flight image load).
 *
 * When a client disconnects mid-response, the underlying [InputStream] is immediately closed
 * and aborted so NanoHTTPD exits its send loop cleanly without logging spurious `SEVERE` errors.
 */
internal class DisconnectSafeResponse private constructor(
    status: IStatus,
    mimeType: String?,
    private val abortableStream: AbortableInputStream?,
    totalBytes: Long,
) : NanoHTTPD.Response(status, mimeType, abortableStream, totalBytes) {

    @Volatile
    private var clientDisconnected: Boolean = false

    public override fun send(outputStream: OutputStream) {
        val safeOut = ClientDisconnectSafeOutputStream(outputStream) {
            markClientDisconnected()
        }
        try {
            super.send(safeOut)
        } finally {
            if (safeOut.disconnected || clientDisconnected) {
                markClientDisconnected()
            }
        }
    }

    override fun isCloseConnection(): Boolean = clientDisconnected || super.isCloseConnection()

    private fun markClientDisconnected() {
        clientDisconnected = true
        closeConnection(true)
        setKeepAlive(false)
        abortableStream?.abort()
    }

    companion object {
        fun fixedLength(
            status: IStatus,
            mimeType: String?,
            data: InputStream?,
            totalBytes: Long,
        ): DisconnectSafeResponse = DisconnectSafeResponse(
            status = status,
            mimeType = mimeType,
            abortableStream = data?.let { AbortableInputStream(it) },
            totalBytes = totalBytes,
        )

        fun fixedLength(
            status: IStatus,
            mimeType: String?,
            text: String,
        ): DisconnectSafeResponse {
            val bytes = text.toByteArray(Charsets.UTF_8)
            return fixedLength(
                status = status,
                mimeType = mimeType,
                data = ByteArrayInputStream(bytes),
                totalBytes = bytes.size.toLong(),
            )
        }

        fun headOnly(
            status: IStatus,
            mimeType: String?,
            contentLength: Long,
        ): DisconnectSafeResponse = DisconnectSafeResponse(
            status = status,
            mimeType = mimeType,
            abortableStream = null,
            totalBytes = 0L,
        ).apply {
            addHeader("Content-Length", contentLength.coerceAtLeast(0L).toString())
        }

        fun chunked(
            status: IStatus,
            mimeType: String?,
            data: InputStream?,
        ): DisconnectSafeResponse = DisconnectSafeResponse(
            status = status,
            mimeType = mimeType,
            abortableStream = data?.let { AbortableInputStream(it) },
            totalBytes = -1L,
        )
    }
}

internal class AbortableInputStream(
    private val delegate: InputStream,
) : InputStream() {
    private val aborted = AtomicBoolean(false)

    val isAborted: Boolean
        get() = aborted.get()

    fun abort() {
        if (aborted.compareAndSet(false, true)) {
            runCatching { delegate.close() }
        }
    }

    override fun read(): Int {
        if (aborted.get()) return -1
        return try {
            delegate.read()
        } catch (error: IOException) {
            if (aborted.get()) -1 else throw error
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (aborted.get()) return -1
        return try {
            delegate.read(b, off, len)
        } catch (error: IOException) {
            if (aborted.get()) -1 else throw error
        }
    }

    override fun available(): Int =
        if (aborted.get()) 0 else runCatching { delegate.available() }.getOrDefault(0)

    override fun close() {
        aborted.set(true)
        runCatching { delegate.close() }
    }
}

internal class ClientDisconnectSafeOutputStream(
    private val delegate: OutputStream,
    private val onClientDisconnect: () -> Unit,
) : OutputStream() {
    @Volatile
    var disconnected: Boolean = false
        private set

    override fun write(b: Int) {
        if (disconnected) return
        try {
            delegate.write(b)
        } catch (error: IOException) {
            handleWriteException(error)
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (disconnected) return
        try {
            delegate.write(b, off, len)
        } catch (error: IOException) {
            handleWriteException(error)
        }
    }

    override fun flush() {
        if (disconnected) return
        try {
            delegate.flush()
        } catch (error: IOException) {
            handleWriteException(error)
        }
    }

    override fun close() {
        runCatching { delegate.close() }
    }

    private fun handleWriteException(error: IOException) {
        if (isClientDisconnect(error)) {
            if (!disconnected) {
                disconnected = true
                onClientDisconnect()
            }
        } else {
            throw error
        }
    }

    companion object {
        fun isClientDisconnect(error: Throwable?): Boolean {
            var current = error
            while (current != null) {
                if (current is SocketException) return true
                val message = current.message?.lowercase(Locale.ROOT).orEmpty()
                if (
                    message.contains("broken pipe") ||
                    message.contains("connection reset") ||
                    message.contains("socket closed") ||
                    message.contains("epipe") ||
                    message.contains("econnreset") ||
                    message.contains("software caused connection abort")
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
