// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random

class HttpBridgeTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    private var method = ""
    private var contentType: String? = null
    private var userAgent: String? = null
    private var body = ""

    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun stop() = server.stop(0)

    // Serves bytes at path, with their length unless chunked; stalls for stallMs after the first half.
    private fun serveBytes(
        path: String,
        bytes: ByteArray,
        chunked: Boolean = false,
        stallMs: Long = 0,
    ) {
        server.createContext(path) { exchange: HttpExchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, if (chunked) 0 else bytes.size.toLong())
            exchange.responseBody.use {
                it.write(bytes, 0, bytes.size / 2)
                it.flush()
                if (stallMs > 0) Thread.sleep(stallMs)
                it.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
            }
        }
    }

    private fun stream(
        path: String,
        file: File,
        maxBytes: Long = Long.MAX_VALUE,
        timeoutMs: Int = 5000,
        onProgress: (Long, Long) -> Boolean = { _, _ -> true },
    ) = HttpBridge.stream("$base$path", file, "GooseStation test", timeoutMs, maxBytes, onProgress, log = {})

    private fun serve(
        path: String,
        status: Int,
        type: String,
        reply: String,
        delayMs: Long = 0,
    ) {
        server.createContext(path) { exchange: HttpExchange ->
            method = exchange.requestMethod
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            body = exchange.requestBody.readBytes().decodeToString()
            if (delayMs > 0) Thread.sleep(delayMs)
            val bytes = reply.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun getReturnsStatusTypeAndBody() {
        serve("/ok", 200, "application/json", """{"ok":1}""")
        val result = HttpBridge.run("$base/ok", null, "GooseStation test", 5000, log = {})
        assertEquals(200, result.status)
        assertEquals("application/json", result.contentType)
        assertEquals("""{"ok":1}""", result.body.decodeToString())
        assertEquals("GET", method)
    }

    @Test
    fun postSendsFormBodyAndUserAgent() {
        serve("/dorequest.php", 200, "application/json", "{}")
        val post = "r=login2&u=goose&p=not-a-real-password".encodeToByteArray()
        val result = HttpBridge.run("$base/dorequest.php", post, "GooseStation test", 5000, log = {})
        assertEquals(200, result.status)
        assertEquals("POST", method)
        assertEquals("application/x-www-form-urlencoded", contentType)
        assertEquals("r=login2&u=goose&p=not-a-real-password", body)
        assertEquals("GooseStation test", userAgent)
    }

    @Test
    fun errorStatusKeepsItsBody() {
        serve("/missing", 404, "text/plain", "nope")
        val result = HttpBridge.run("$base/missing", null, "GooseStation test", 5000, log = {})
        assertEquals(404, result.status)
        assertEquals("nope", result.body.decodeToString())
    }

    @Test
    fun slowServerTimesOut() {
        serve("/slow", 200, "text/plain", "late", delayMs = 2000)
        val result = HttpBridge.run("$base/slow", null, "GooseStation test", 300, log = {})
        assertEquals(HttpBridge.STATUS_TIMEOUT, result.status)
    }

    // From review: Android's disconnect() before a connection exists does nothing, so a request cancelled while it
    // waited for a pool thread went on to the server. Cancelled before it connects, it never reaches it.
    @Test
    fun aRequestCancelledBeforeItConnectsNeverReachesTheServer() {
        var reached = false
        server.createContext("/never") { exchange: HttpExchange ->
            reached = true
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }

        val result = HttpBridge.run("$base/never", null, "GooseStation test", 2000, log = {}, isCancelled = { true })

        assertEquals(HttpBridge.STATUS_CANCELLED, result.status)
        assertFalse(reached)
    }

    // Cancelled while its body comes, it stops reading rather than taking the rest.
    @Test
    fun aRequestCancelledWhileReadingStops() {
        server.createContext("/trickle") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                // Three seconds of body, a chunk at a time.
                repeat(60) {
                    out.write(ByteArray(1024))
                    out.flush()
                    Thread.sleep(50)
                }
            }
        }

        val started = System.nanoTime()
        val result =
            HttpBridge.run(
                "$base/trickle",
                null,
                "GooseStation test",
                5000,
                log = {},
                isCancelled = { System.nanoTime() - started > 300_000_000L },
            )

        assertEquals(HttpBridge.STATUS_CANCELLED, result.status)
        assertTrue("stopped well before the body ended", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun unreachableIsAnError() {
        val port = ServerSocket(0).use { it.localPort }
        val result = HttpBridge.run("http://127.0.0.1:$port/", null, "GooseStation test", 2000, log = {})
        assertEquals(HttpBridge.STATUS_ERROR, result.status)
        assertArrayEquals(ByteArray(0), result.body)
    }

    // Review Focus 3: the token and password ride in query strings and bodies, so neither is ever logged.
    @Test
    fun logsNoBodyOrQuery() {
        serve("/dorequest.php", 200, "application/json", "{}")
        val lines = mutableListOf<String>()
        HttpBridge.run(
            "$base/dorequest.php?r=login2&t=fake-token-1",
            "u=goose&p=not-a-real-password".encodeToByteArray(),
            "GooseStation test",
            5000,
            log = { lines += it },
        )
        assertEquals(listOf("POST 127.0.0.1/dorequest.php -> 200"), lines)
        for (secret in listOf("fake-token-1", "not-a-real-password", "r=")) {
            assertFalse(lines.any { secret in it })
        }
    }

    @Test
    fun aStreamWritesTheBodyToTheFile() {
        val bytes = Random(17).nextBytes(1024 * 1024)
        serveBytes("/pack.zip", bytes)
        val file = File(temp.root, "pack.zip.part")
        val progress = mutableListOf<Pair<Long, Long>>()

        val status =
            stream("/pack.zip", file) { done, total ->
                progress += done to total
                true
            }

        assertEquals(200, status)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1024L * 1024 to 1024L * 1024, progress.last())
        // At most one report each 64 KiB, and one at the end.
        assertTrue(progress.toString(), progress.size <= 1024 / 64 + 2)
        assertEquals("GooseStation test", userAgent)
    }

    @Test
    fun aStreamOfUnknownLengthSaysSo() {
        serveBytes("/chunked.zip", ByteArray(200_000) { 7 }, chunked = true)
        val file = File(temp.root, "chunked.zip.part")
        var last = 0L to 0L

        assertEquals(
            200,
            stream("/chunked.zip", file) { done, total ->
                last = done to total
                true
            },
        )
        assertEquals(200_000L to -1L, last)
        assertEquals(200_000L, file.length())
    }

    // GitHub's release downloads answer with a redirect to where the file is.
    @Test
    fun aStreamFollowsARedirect() {
        serveBytes("/real.zip", "zip".encodeToByteArray())
        server.createContext("/moved.zip") { exchange: HttpExchange ->
            exchange.responseHeaders.add("Location", "$base/real.zip")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val file = File(temp.root, "moved.zip.part")

        assertEquals(200, stream("/moved.zip", file))
        assertEquals("zip", file.readText())
    }

    @Test
    fun aCancelledStreamDeletesTheFile() {
        serveBytes("/pack.zip", ByteArray(1024 * 1024))
        val file = File(temp.root, "pack.zip.part")

        val status = stream("/pack.zip", file) { done, _ -> done == 0L }

        assertEquals(HttpBridge.STATUS_CANCELLED, status)
        assertFalse(file.exists())
    }

    // From review: a body held in memory had no cap, so a source serving huge files could run the app out of memory.
    @Test
    fun aBodyPastTheCapIsRefused() {
        serveBytes("/big.jpg", ByteArray(1024 * 1024))
        serveBytes("/big-chunked.jpg", ByteArray(1024 * 1024), chunked = true)
        serveBytes("/small.jpg", ByteArray(100_000))
        for (path in listOf("/big.jpg", "/big-chunked.jpg")) {
            val result = HttpBridge.run("$base$path", null, "Goose", 5_000, log = {}, maxBytes = 100_000)
            assertEquals(path, HttpBridge.STATUS_TOO_LARGE, result.status)
            assertEquals(path, 0, result.body.size)
        }
        assertEquals(200, HttpBridge.run("$base/small.jpg", null, "Goose", 5_000, log = {}, maxBytes = 100_000).status)
        assertEquals(32L * 1024 * 1024, HttpBridge.MAX_BODY_BYTES)
    }

    @Test
    fun aStreamPastTheCapStopsAndDeletesTheFile() {
        serveBytes("/big.zip", ByteArray(1024 * 1024))
        serveBytes("/big-chunked.zip", ByteArray(1024 * 1024), chunked = true)
        for (path in listOf("/big.zip", "/big-chunked.zip")) {
            val file = File(temp.root, "big.zip.part")
            assertEquals(path, HttpBridge.STATUS_TOO_LARGE, stream(path, file, maxBytes = 100_000))
            assertFalse(path, file.exists())
        }
    }

    @Test
    fun aStreamOfAnErrorStatusWritesNothing() {
        serve("/missing.zip", 404, "text/plain", "nope")
        val file = File(temp.root, "missing.zip.part")

        assertEquals(404, stream("/missing.zip", file))
        assertFalse(file.exists())
    }

    @Test
    fun aStalledStreamTimesOut() {
        serveBytes("/stall.zip", ByteArray(64 * 1024), stallMs = 2000)
        val file = File(temp.root, "stall.zip.part")

        assertEquals(HttpBridge.STATUS_TIMEOUT, stream("/stall.zip", file, timeoutMs = 300))
        assertFalse(file.exists())
    }
}
