// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A finished request: its HTTP [status] (or [HttpBridge.STATUS_TIMEOUT], [HttpBridge.STATUS_ERROR]), its type, its body. */
class HttpResult(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
)

/**
 * The core's HTTP requests (its HTTPDownloader on Android), run with the platform's [HttpURLConnection]. C++ calls
 * [start] and [cancel] from the core's threads, and hears back through [NativeLibrary.httpDone]. Request bodies and
 * query strings hold RetroAchievements passwords and tokens, so only the method, host, path and status are logged.
 */
object HttpBridge {
    const val STATUS_TIMEOUT = -2
    const val STATUS_ERROR = -1

    /** A stream stopped by its progress callback (the core's HTTP_STATUS_CANCELLED). */
    const val STATUS_CANCELLED = -3

    /** A stream, or a body held in memory, past its cap (the core's ShaderPacks::STATUS_TOO_LARGE). */
    const val STATUS_TOO_LARGE = -100

    /** The most a body held in memory ([run]) may be: far past any cover or RetroAchievements answer. */
    const val MAX_BODY_BYTES = 32L * 1024 * 1024

    // How often a stream reports its progress.
    private const val PROGRESS_STEP = 64 * 1024

    private const val TAG = "GooseStationHttp"

    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "HTTP").apply { isDaemon = true } }

    // Each request queued or running, by downloader and request, with its connection once it has one. A request
    // cancel() takes out of here is never reported.
    private class Active {
        @Volatile var connection: HttpURLConnection? = null
    }

    private val active = ConcurrentHashMap<Pair<Long, Long>, Active>()

    /** Queues [url], a POST of [post] or a GET without, for [downloader]'s [request]. Never blocks. */
    @JvmStatic
    fun start(
        downloader: Long,
        request: Long,
        url: String,
        post: ByteArray?,
        userAgent: String,
        timeoutMs: Int,
    ) {
        val id = downloader to request
        val entry = Active()
        active[id] = entry
        pool.execute {
            if (active[id] !== entry) return@execute
            val result =
                run(url, post, userAgent, timeoutMs, onConnection = {
                    entry.connection = it
                    // Cancelled while connecting: the connection must not be left open.
                    if (active[id] !== entry) it.disconnect()
                }, isCancelled = { active[id] !== entry })
            if (active.remove(id, entry)) {
                NativeLibrary.httpDone(downloader, request, result.status, result.contentType, result.body)
            }
        }
    }

    /** Stops [downloader]'s [request] if it's still queued or running. Nothing is reported for it. */
    @JvmStatic
    fun cancel(
        downloader: Long,
        request: Long,
    ) {
        active.remove(downloader to request)?.connection?.disconnect()
    }

    /**
     * Runs one request on the calling thread. A body over [maxBytes] is refused ([STATUS_TOO_LARGE]) rather than held
     * in memory: these answers are whole in memory, unlike [download]'s. [isCancelled] is asked before it connects and
     * between reads, as Android's disconnect() does nothing before a connection exists: a cancelled request stops
     * there ([STATUS_CANCELLED]) rather than holding a pool thread to the end.
     */
    internal fun run(
        url: String,
        post: ByteArray?,
        userAgent: String,
        timeoutMs: Int,
        log: (String) -> Unit = ::logRequest,
        onConnection: (HttpURLConnection) -> Unit = {},
        maxBytes: Long = MAX_BODY_BYTES,
        isCancelled: () -> Boolean = { false },
    ): HttpResult {
        val method = if (post != null) "POST" else "GET"
        val parsed = runCatching { URL(url) }.getOrNull()
        val where = parsed?.let { "${it.host}${it.path}" } ?: "(bad URL)"
        val result =
            if (parsed == null) {
                HttpResult(STATUS_ERROR, "", ByteArray(0))
            } else {
                try {
                    val connection = parsed.openConnection() as HttpURLConnection
                    onConnection(connection)
                    try {
                        if (isCancelled()) throw CancelledException()
                        connection.connectTimeout = timeoutMs
                        connection.readTimeout = timeoutMs
                        connection.requestMethod = method
                        connection.setRequestProperty("User-Agent", userAgent)
                        if (post != null) {
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                            connection.setFixedLengthStreamingMode(post.size)
                            connection.outputStream.use { it.write(post) }
                        }
                        val status = connection.responseCode
                        val stream = if (status >= 400) connection.errorStream else connection.inputStream
                        val body =
                            if (connection.contentLengthLong > maxBytes) {
                                null
                            } else {
                                stream?.use { read(it, maxBytes, isCancelled) }
                            }
                        when {
                            stream != null && body == null -> HttpResult(STATUS_TOO_LARGE, "", ByteArray(0))
                            else -> HttpResult(status, connection.contentType.orEmpty(), body ?: ByteArray(0))
                        }
                    } finally {
                        connection.disconnect()
                    }
                } catch (_: CancelledException) {
                    HttpResult(STATUS_CANCELLED, "", ByteArray(0))
                } catch (_: SocketTimeoutException) {
                    HttpResult(STATUS_TIMEOUT, "", ByteArray(0))
                } catch (_: IOException) {
                    HttpResult(STATUS_ERROR, "", ByteArray(0))
                } catch (_: RuntimeException) {
                    HttpResult(STATUS_ERROR, "", ByteArray(0))
                }
            }
        log("$method $where -> ${result.status}")
        return result
    }

    /**
     * Streams [url] to the file at [path] on the calling thread (a shader pack's install), reporting each 64 KiB to
     * the core's progress callback at [progress], which can stop it. The status: 200, the HTTP error, or one of
     * [STATUS_TIMEOUT], [STATUS_ERROR], [STATUS_CANCELLED], [STATUS_TOO_LARGE]; the file is gone unless it's 200.
     */
    @JvmStatic
    fun download(
        url: String,
        path: String,
        userAgent: String,
        timeoutMs: Int,
        maxBytes: Long,
        progress: Long,
    ): Int =
        stream(url, File(path), userAgent, timeoutMs, maxBytes, { done, total ->
            NativeLibrary.downloadProgress(progress, done, total)
        })

    /**
     * A GET of [url] written to [file] as it arrives, never past [maxBytes]. [onProgress] hears the bytes so far and
     * the length (-1 when the server doesn't say), at the start, each 64 KiB and at the end; false stops it. Each
     * connect and read waits at most [timeoutMs]. Redirects are followed. The file is deleted unless the status is 200.
     */
    internal fun stream(
        url: String,
        file: File,
        userAgent: String,
        timeoutMs: Int,
        maxBytes: Long,
        onProgress: (done: Long, total: Long) -> Boolean,
        log: (String) -> Unit = ::logRequest,
    ): Int {
        val parsed = runCatching { URL(url) }.getOrNull()
        val where = parsed?.let { "${it.host}${it.path}" } ?: "(bad URL)"
        val status =
            when {
                parsed == null -> STATUS_ERROR
                !onProgress(0, -1) -> STATUS_CANCELLED
                else ->
                    try {
                        val connection = parsed.openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = timeoutMs
                            connection.readTimeout = timeoutMs
                            connection.instanceFollowRedirects = true
                            connection.setRequestProperty("User-Agent", userAgent)
                            val code = connection.responseCode
                            val total = connection.contentLengthLong
                            when {
                                code != 200 -> code
                                total > maxBytes -> STATUS_TOO_LARGE
                                else -> copy(connection.inputStream, file, total, maxBytes, onProgress)
                            }
                        } finally {
                            connection.disconnect()
                        }
                    } catch (_: SocketTimeoutException) {
                        STATUS_TIMEOUT
                    } catch (_: IOException) {
                        STATUS_ERROR
                    } catch (_: RuntimeException) {
                        STATUS_ERROR
                    }
            }
        if (status != 200) file.delete()
        log("GET $where -> $status")
        return status
    }

    // The whole of [input], or null once it's past [maxBytes].
    private fun read(
        input: InputStream,
        maxBytes: Long,
        isCancelled: () -> Boolean,
    ): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(PROGRESS_STEP)
        while (true) {
            if (isCancelled()) throw CancelledException()
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > maxBytes) return null
            out.write(buffer, 0, read)
        }
    }

    private fun copy(
        input: InputStream,
        file: File,
        total: Long,
        maxBytes: Long,
        onProgress: (Long, Long) -> Boolean,
    ): Int =
        input.use { stream ->
            file.outputStream().use { out ->
                val buffer = ByteArray(PROGRESS_STEP)
                var done = 0L
                var reported = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    done += read
                    if (done > maxBytes) return STATUS_TOO_LARGE
                    out.write(buffer, 0, read)
                    if (done - reported >= PROGRESS_STEP) {
                        reported = done
                        if (!onProgress(done, total)) return STATUS_CANCELLED
                    }
                }
                if (!onProgress(done, total)) STATUS_CANCELLED else 200
            }
        }

    private fun logRequest(line: String) {
        Log.i(TAG, line)
    }
}

// A request stopped by its isCancelled, as it connects or between reads.
private class CancelledException : IOException()
