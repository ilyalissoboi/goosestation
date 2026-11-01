// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

class HttpBridgeTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    private var method = ""
    private var contentType: String? = null
    private var userAgent: String? = null
    private var body = ""

    @After
    fun stop() = server.stop(0)

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
}
