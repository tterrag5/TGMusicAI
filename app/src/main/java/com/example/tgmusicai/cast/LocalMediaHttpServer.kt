package com.example.tgmusicai.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Serves local audio files over the local network, so a Cast device can play them.
 *
 * A Chromecast fetches media itself, by URL. It has no access to this phone's storage, so a
 * `file://` or `content://` track -- which is most of the library -- simply cannot be cast without
 * something on this side making the bytes reachable. That is all this is.
 *
 * Deliberately narrow, because it is a listening socket on the user's network:
 *
 * - It serves only files explicitly registered by [register], never an arbitrary path from the
 *   request. A path-based server would let anything on the network read any file the app can.
 * - Each registration is addressed by an unguessable random token, so being on the same network is
 *   not by itself enough to enumerate what is being served.
 * - It runs only while a Cast session is active, and every registration is dropped when it stops.
 *
 * Range requests are supported because Cast devices seek by issuing them, and a server that
 * ignores Range makes seeking within a track impossible.
 */
class LocalMediaHttpServer(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val served = ConcurrentHashMap<String, Uri>()
    private val workers = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** The port the server bound to, or null when it is not running. */
    val port: Int?
        get() = serverSocket?.localPort

    /** Starts listening, choosing a free port. Returns false if the socket could not be opened. */
    fun start(): Boolean {
        if (serverSocket != null) return true
        return try {
            // Port 0 asks the OS for any free port, which avoids both a hardcoded clash and the
            // need to retry a fixed one.
            val socket = ServerSocket(0)
            serverSocket = socket
            acceptThread = thread(name = "LocalMediaHttpServer", isDaemon = true) {
                acceptLoop(socket)
            }
            Log.d(TAG, "Serving local media on port ${socket.localPort}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Could not start the local media server", e)
            serverSocket = null
            false
        }
    }

    /**
     * Makes [uri] reachable and returns the URL a Cast device should fetch, or null if the server
     * is not running or this device has no usable network address.
     */
    fun register(uri: Uri): String? {
        val socket = serverSocket ?: return null
        val address = localAddress() ?: return null
        // One token per URI rather than per call, so re-casting the same track does not grow the
        // map without bound over a long session.
        val token = served.entries.firstOrNull { it.value == uri }?.key ?: newToken().also {
            served[it] = uri
        }
        return "http://$address:${socket.localPort}/media/$token"
    }

    /** Stops listening and forgets every registration. */
    override fun close() {
        served.clear()
        try {
            serverSocket?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing the local media server", e)
        }
        serverSocket = null
        acceptThread = null
        workers.shutdownNow()
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Throwable) {
                // A closed socket is the normal way this loop ends, not a failure worth logging
                // as one.
                if (!socket.isClosed) Log.w(TAG, "Accept failed", e)
                return
            }
            try {
                workers.execute { handle(client) }
            } catch (e: Throwable) {
                // The pool is shut down; drop the connection rather than leaking it.
                try { client.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                val input = socket.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                var rangeHeader: String? = null
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(':').trim()
                    }
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    respondError(socket.getOutputStream(), 400, "Bad Request")
                    return
                }
                val method = parts[0]
                val path = parts[1]

                val token = path.removePrefix("/media/").substringBefore('?')
                val uri = served[token]
                if (uri == null) {
                    respondError(socket.getOutputStream(), 404, "Not Found")
                    return
                }

                // A HEAD is how a Cast device checks size and type before committing to playing,
                // so it has to be answered with the same headers and no body.
                serveUri(socket.getOutputStream(), uri, rangeHeader, includeBody = method != "HEAD")
            } catch (e: Throwable) {
                Log.d(TAG, "Request failed", e)
            }
        }
    }

    private fun serveUri(output: OutputStream, uri: Uri, rangeHeader: String?, includeBody: Boolean) {
        val length = contentLength(uri)
        if (length <= 0) {
            respondError(output, 404, "Not Found")
            return
        }

        val (start, end) = parseRange(rangeHeader, length)
        val partial = rangeHeader != null
        val count = end - start + 1

        val headers = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${mimeType(uri)}\r\n")
            append("Content-Length: $count\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$length\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray())
        output.flush()
        if (!includeBody) return

        openStream(uri)?.use { stream ->
            // Skip rather than seek: a content:// stream is not seekable, and skipping is the only
            // operation both it and a plain file stream support.
            var toSkip = start
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = count
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            output.flush()
        }
    }

    /** Writes a bare status response, for a request naming something this server will not serve. */
    private fun respondError(output: OutputStream, code: Int, reason: String) {
        try {
            output.write(
                ("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.flush()
        } catch (e: Throwable) {
            Log.d(TAG, "Could not write an error response", e)
        }
    }

    /** Parses a `bytes=start-end` header into an inclusive range, clamped to the file. */
    private fun parseRange(header: String?, length: Long): Pair<Long, Long> {
        if (header == null || !header.startsWith("bytes=")) return 0L to (length - 1)
        val spec = header.removePrefix("bytes=").substringBefore(',')
        val start = spec.substringBefore('-').trim().toLongOrNull() ?: 0L
        val end = spec.substringAfter('-').trim().toLongOrNull() ?: (length - 1)
        val clampedStart = start.coerceIn(0L, length - 1)
        val clampedEnd = end.coerceIn(clampedStart, length - 1)
        return clampedStart to clampedEnd
    }

    private fun contentLength(uri: Uri): Long = try {
        when (uri.scheme) {
            "content" -> appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            "file" -> uri.path?.let { File(it).length() } ?: -1L
            else -> File(uri.toString()).length()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Could not determine the length of $uri", e)
        -1L
    }

    private fun openStream(uri: Uri): InputStream? = try {
        when (uri.scheme) {
            "content" -> appContext.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { File(it).inputStream() }
            else -> File(uri.toString()).inputStream()
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Could not open $uri", e)
        null
    }

    /**
     * Best guess at the content type. A Cast device refuses media whose type it does not
     * recognise, and `application/octet-stream` is one of the types it refuses -- so an unknown
     * extension falls back to generic audio rather than to a type that guarantees failure.
     */
    private fun mimeType(uri: Uri): String {
        appContext.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") }?.let { return it }
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val fromMap = extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        return fromMap?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
    }

    companion object {
        private const val TAG = "LocalMediaHttpServer"
        private const val BUFFER_SIZE = 64 * 1024
        private const val TOKEN_BYTES = 16

        /**
         * This device's address on the local network, which is what a Cast device has to connect
         * back to. Loopback is skipped because it is reachable only from this phone, which defeats
         * the whole point. IPv6 is skipped because Cast's media fetcher handles v4 far more
         * reliably in practice.
         */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (e: Throwable) {
            Log.w(TAG, "Could not determine this device's network address", e)
            null
        }
    }
}
