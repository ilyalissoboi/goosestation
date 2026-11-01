// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A stand-in for RetroAchievements' server on the device, for the sign-in tests: it answers `login2` with a password
 * or token, and nothing else. Its user, password and token are made up ([USER], [PASSWORD], [TOKEN]); no test uses
 * real credentials. Point the core at [url] with [NativeLibrary.setAchievementsServerForTest].
 */
class FakeRetroAchievements : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val url = "http://127.0.0.1:${server.localPort}"

    /** Each request's form fields, in order. */
    val requests = CopyOnWriteArrayList<Map<String, String>>()

    /** Closes each connection without an answer, as an unreachable server would look. */
    @Volatile var unreachable = false

    /** Answers a token sign-in as the server does for an expired or revoked token. */
    @Volatile var rejectTokens = false

    private val acceptor =
        thread(isDaemon = true, name = "FakeRetroAchievements") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(::answer) }
            }
        }

    override fun close() {
        server.close()
        acceptor.join(1000)
    }

    private fun answer(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) return
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
        while (read < length) read += input.read(body, read, length - read).also { if (it < 0) return }
        val query =
            head
                .lineSequence()
                .first()
                .split(' ')
                .getOrNull(1)
                ?.substringAfter('?', "")
                .orEmpty()
        val fields = form(query) + form(body.decodeToString())
        requests += fields
        if (unreachable) return

        val (status, json) = reply(fields)
        val bytes = json.encodeToByteArray()
        val out = socket.getOutputStream()
        out.write(
            (
                "HTTP/1.1 $status ${if (status == 200) "OK" else "Unauthorized"}\r\n" +
                    "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            ).encodeToByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun reply(fields: Map<String, String>): Pair<Int, String> {
        val user = fields["u"]
        val signedIn =
            fields["r"] == "login2" && user == USER &&
                (fields["p"] == PASSWORD || (fields["t"] == TOKEN && !rejectTokens))
        if (signedIn) {
            return 200 to
                """{"Success":true,"User":"$USER","DisplayName":"Goose","Token":"$TOKEN","Score":300,""" +
                """"SoftcoreScore":940,"Messages":0}"""
        }
        val code = if (fields["t"] != null) "expired_token" else "invalid_credentials"
        val error = if (fields["t"] != null) "The access token has expired." else "Invalid username/password."
        return 401 to """{"Success":false,"Error":"$error","Code":"$code"}"""
    }

    private fun form(text: String): Map<String, String> =
        text
            .split('&')
            .filter { '=' in it }
            .associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }

    companion object {
        const val USER = "goose"
        const val PASSWORD = "not-a-real-password"
        const val TOKEN = "fake-token-1"
    }
}
