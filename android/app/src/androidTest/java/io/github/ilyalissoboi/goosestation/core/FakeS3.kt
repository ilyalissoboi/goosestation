// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A stand-in for an S3 bucket ([BUCKET]) on the device, as much of S3 as emusync uses: create-only PutObject
 * (`If-None-Match: *`), GetObject, HeadObject and ListObjectsV2, path-style. Signatures aren't checked; the access key
 * ID is read from the request, and one in [rejectedKeys] is refused as S3 refuses an unknown key. No test uses real
 * credentials: [ACCESS_KEY_ID] and [SECRET] are made up. Point sync at [uri].
 */
class FakeS3 : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val objects = ConcurrentHashMap<String, ByteArray>()

    /** Each request's method and object key (or `?list` for a listing), in order: never a header or a body. */
    val requests = CopyOnWriteArrayList<Pair<String, String>>()

    /** Access key IDs answered with 403, as S3 answers a key it doesn't know. */
    val rejectedKeys = CopyOnWriteArrayList<String>()

    /** Closes each connection without an answer, as storage that can't be reached looks. */
    @Volatile var unreachable = false

    /** How long each request waits before its answer, as slow storage takes. */
    @Volatile var delayMs = 0L

    /**
     * The storage's address. emusync keeps its data per address, so [otherDevice]'s spelling of the same storage
     * (`path_style=true` for `1`) is another device's.
     */
    fun uri(otherDevice: Boolean = false): String =
        "s3://$BUCKET/emusync/v1?endpoint=http://127.0.0.1:${server.localPort}" +
            "&path_style=${if (otherDevice) "true" else "1"}&region=us-east-1"

    /** The objects' keys, under the bucket. */
    fun keys(): Set<String> = objects.keys.toSet()

    /** How many requests were PUTs. */
    fun puts(): Int = requests.count { it.first == "PUT" }

    /** How many requests were listings. */
    fun listings(): Int = requests.count { it.second == LISTING }

    private val acceptor =
        thread(isDaemon = true, name = "FakeS3") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "FakeS3-connection") { serve(socket) }
            }
        }

    private fun serve(socket: Socket) =
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = it.getOutputStream()
            while (true) {
                val requestLine = readLine(input) ?: return
                if (requestLine.isEmpty()) continue
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon >
                        0
                    ) {
                        headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                    }
                }
                if (unreachable) return
                if (delayMs > 0) Thread.sleep(delayMs)
                if (headers["expect"].equals("100-continue", ignoreCase = true)) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    output.flush()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val (method, target) = requestLine.split(' ').let { parts -> parts[0] to parts[1] }
                answer(output, method, target, headers, body)
                if (headers["connection"].equals("close", ignoreCase = true)) return
            }
        }

    private fun answer(
        output: OutputStream,
        method: String,
        target: String,
        headers: Map<String, String>,
        body: ByteArray,
    ) {
        val path = target.substringBefore('?')
        val query =
            target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
        val key =
            path.removePrefix("/$BUCKET").removePrefix("/").split('/').joinToString("/") {
                URLDecoder.decode(it, "UTF-8")
            }
        requests += method to (if (key.isEmpty() && query["list-type"] == "2") LISTING else key)

        val accessKeyId = headers["authorization"]?.substringAfter("Credential=", "")?.substringBefore('/')
        if (accessKeyId in rejectedKeys) {
            return respond(output, method, 403, error("InvalidAccessKeyId", "The access key ID is not known."))
        }
        when {
            key.isEmpty() && method == "GET" -> respond(output, method, 200, list(query))
            method == "PUT" ->
                if (headers["if-none-match"] == "*" && objects.putIfAbsent(key, body) != null) {
                    respond(output, method, 412, error("PreconditionFailed", "At least one precondition failed."))
                } else {
                    if (headers["if-none-match"] != "*") objects[key] = body
                    respond(output, method, 200, ByteArray(0))
                }
            method == "GET" || method == "HEAD" ->
                objects[key]?.let { respond(output, method, 200, it) }
                    ?: respond(output, method, 404, error("NoSuchKey", "The key does not exist."))
            else -> respond(output, method, 405, error("MethodNotAllowed", method))
        }
    }

    // All matching keys at once, with the prefixes a delimiter folds them into.
    private fun list(query: Map<String, String>): ByteArray {
        val prefix = query["prefix"].orEmpty()
        val startAfter = query["start-after"]
        val delimiter = query["delimiter"]
        val matching = objects.keys.filter { it.startsWith(prefix) && (startAfter == null || it > startAfter) }.sorted()
        val contents = StringBuilder()
        val prefixes = sortedSetOf<String>()
        for (key in matching) {
            val rest = key.removePrefix(prefix)
            val cut = if (delimiter.isNullOrEmpty()) -1 else rest.indexOf(delimiter)
            if (cut >= 0) {
                prefixes += prefix + rest.substring(0, cut + delimiter!!.length)
            } else {
                contents.append("<Contents><Key>${escape(key)}</Key><Size>${objects[key]?.size ?: 0}</Size></Contents>")
            }
        }
        val common = prefixes.joinToString("") { "<CommonPrefixes><Prefix>${escape(it)}</Prefix></CommonPrefixes>" }
        return (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult><Name>$BUCKET</Name>" +
                "<Prefix>${escape(prefix)}</Prefix><IsTruncated>false</IsTruncated>$contents$common</ListBucketResult>"
        ).toByteArray()
    }

    private fun error(
        code: String,
        message: String,
    ) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>$code</Code><Message>$message</Message></Error>"
        .toByteArray()

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun respond(
        output: OutputStream,
        method: String,
        status: Int,
        body: ByteArray,
    ) {
        val reason =
            when (status) {
                200 -> "OK"
                403 -> "Forbidden"
                404 -> "Not Found"
                412 -> "Precondition Failed"
                else -> "Error"
            }
        output.write(
            "HTTP/1.1 $status $reason\r\nContent-Length: ${body.size}\r\nContent-Type: application/xml\r\n\r\n"
                .toByteArray(),
        )
        if (method != "HEAD") output.write(body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    override fun close() {
        server.close()
        acceptor.join(1000)
    }

    companion object {
        const val BUCKET = "goose-saves"
        const val ACCESS_KEY_ID = "GOOSEFAKEACCESSKEY"
        const val SECRET = "not-a-real-secret"
        private const val LISTING = "?list"
    }
}
