// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.util.Log
import java.io.IOException
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
                })
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

    /** Runs one request on the calling thread. */
    internal fun run(
        url: String,
        post: ByteArray?,
        userAgent: String,
        timeoutMs: Int,
        log: (String) -> Unit = ::logRequest,
        onConnection: (HttpURLConnection) -> Unit = {},
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
                        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                        HttpResult(status, connection.contentType.orEmpty(), body)
                    } finally {
                        connection.disconnect()
                    }
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

    private fun logRequest(line: String) {
        Log.i(TAG, line)
    }
}
