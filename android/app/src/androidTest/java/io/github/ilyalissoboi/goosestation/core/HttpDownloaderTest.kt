// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The core's HTTPDownloader on Android, through HttpBridge, against a server on the device. */
@RunWith(AndroidJUnit4::class)
class HttpDownloaderTest {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${server.localPort}"

    @Volatile private var request = ""

    @After
    fun stop() = server.close()

    /** Answers one request with [reply] (a whole HTTP response), or never answers with null. */
    private fun answerOnce(reply: String?) {
        thread(isDaemon = true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.use {
                val input = BufferedInputStream(it.getInputStream())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) break
                    head.append(b.toChar())
                }
                val length =
                    Regex("(?i)content-length: *(\\d+)")
                        .find(head)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) read += input.read(body, read, length - read)
                request = head.toString() + body.decodeToString()
                if (reply == null) {
                    Thread.sleep(3000)
                    return@thread
                }
                it.getOutputStream().write(reply.encodeToByteArray())
                it.getOutputStream().flush()
            }
        }
    }

    private fun ok(
        type: String,
        body: String,
    ) = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    @Test
    fun getThroughTheCore() {
        answerOnce(ok("application/json", """{"ok":1}"""))
        val result = NativeLibrary.httpTestFetch("$base/ok", null, 10f)
        assertEquals(listOf("200", "application/json", """{"ok":1}"""), result.toList())
    }

    @Test
    fun postThroughTheCore() {
        answerOnce(ok("text/plain", "done"))
        val result = NativeLibrary.httpTestFetch("$base/dorequest.php", "r=login2&u=goose", 10f)
        assertEquals("200", result[0])
        assert(request.startsWith("POST /dorequest.php")) { request }
        assert(request.endsWith("r=login2&u=goose")) { request }
        assert(Regex("(?i)content-type: application/x-www-form-urlencoded").containsMatchIn(request)) { request }
    }

    @Test
    fun timeoutThroughTheCore() {
        answerOnce(null)
        val result = NativeLibrary.httpTestFetch("$base/slow", null, 1f)
        assertEquals("-2", result[0])
    }
}
