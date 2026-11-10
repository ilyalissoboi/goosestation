// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class UpdateSourceTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val host = "http://127.0.0.1:${server.address.port}"
    private val api = "$host/repos/x"
    private val paths = CopyOnWriteArrayList<String>()
    private var userAgent: String? = null

    @get:Rule
    val temp = TemporaryFolder()

    @After
    fun stop() = server.stop(0)

    private fun source(base: String = api) = UpdateSource(base, "GooseStation for Android (arm64) 0.1", 5000, log = {})

    private fun serve(
        path: String,
        status: Int,
        reply: String,
    ) {
        server.createContext(path) { exchange: HttpExchange ->
            paths += exchange.requestURI.path
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            val bytes = reply.encodeToByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun serveRelease(json: String = ReleaseFixtures.release()) =
        serve("/repos/x/releases/tags/preview", 200, json)

    // 0.1-512 is 100512.
    private fun check(
        ownCommit: String = "ffffffffffffffffffffffffffffffffffffffff",
        ownCode: Int = 100_498,
    ) = source().check("preview", ownCommit, ownCode)

    @Test
    fun theSameCommitIsUpToDate() {
        serveRelease()
        assertEquals(CheckResult.UpToDate, check(ownCommit = ReleaseFixtures.COMMIT, ownCode = 100_512))
    }

    @Test
    fun aHigherCodeIsNewer() {
        serveRelease()
        val result = check()
        assertTrue(result.toString(), result is CheckResult.Newer)
        assertEquals(100_512, (result as CheckResult.Newer).release.versionCode)
    }

    @Test
    fun anOlderCodeIsOlder() {
        serveRelease()
        assertTrue(check(ownCode = 100_600) is CheckResult.Older)
    }

    @Test
    fun anEqualCodeOtherCommitIsOlder() {
        serveRelease()
        assertTrue(check(ownCode = 100_512) is CheckResult.Older)
    }

    @Test
    fun aReleaseWithoutTheApkIsNoAndroidBuild() {
        serveRelease(ReleaseFixtures.release(apk = false))
        assertEquals(CheckResult.NoAndroidBuild, check())
    }

    @Test
    fun a404IsNoRelease() {
        serve("/repos/x/releases/tags/preview", 404, """{"message": "Not Found"}""")
        assertEquals(CheckResult.NoRelease, check())
    }

    @Test
    fun a403IsUnreadableWithItsStatus() {
        serve("/repos/x/releases/tags/preview", 403, """{"message": "API rate limit exceeded"}""")
        assertEquals(CheckResult.Unreadable(403), check())
    }

    @Test
    fun a200ThatIsntAReleaseIsUnreadable() {
        serveRelease("<html>Sign in to the Wi-Fi</html>")
        assertEquals(CheckResult.Unreadable(null), check())
    }

    @Test
    fun aClosedPortIsUnreachable() {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(
            CheckResult.Unreachable,
            source("http://127.0.0.1:$closed/repos/x").check("preview", "f".repeat(40), 1),
        )
    }

    @Test
    fun itAsksForTheTagsRelease() {
        serve("/repos/x/releases/tags/latest", 404, "")
        source().check("latest", "f".repeat(40), 1)
        assertEquals(listOf("/repos/x/releases/tags/latest"), paths.toList())
        assertTrue(userAgent.orEmpty(), userAgent.orEmpty().startsWith("GooseStation for Android"))
    }

    @Test
    fun changesAskTheCompare() {
        serve("/repos/x/compare/aaa...bbb", 200, """{"total_commits": 0, "commits": []}""")
        assertEquals(Changes(emptyList(), 0), source().changes("aaa", "bbb"))
        assertEquals(listOf("/repos/x/compare/aaa...bbb"), paths.toList())
    }

    @Test
    fun changesThatFailAreNull() {
        serve("/repos/x/compare/aaa...bbb", 500, "")
        assertEquals(null, source().changes("aaa", "bbb"))
    }

    private fun release(
        path: String,
        size: Long,
    ) = ChannelRelease("preview", "0.1-512-g1a2b3c4", 100_512, ReleaseFixtures.COMMIT, "$host$path", size)

    // Serves size bytes at path, claiming claimed of them.
    private fun serveApk(
        path: String,
        size: Int,
        claimed: Int = size,
    ) {
        server.createContext(path) { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, claimed.toLong())
            exchange.responseBody.use { it.write(ByteArray(size) { i -> i.toByte() }) }
        }
    }

    @Test
    fun downloadWritesTheApk() {
        serveApk("/apk", 300_000)
        val file = temp.root.resolve("update.apk")
        var last = 0L
        val result = source().download(release("/apk", 300_000), file) { done, _ -> true.also { last = done } }
        assertEquals(DownloadResult.Done, result)
        assertEquals(300_000L, file.length())
        assertEquals(300_000L, last)
    }

    @Test
    fun aShortDownloadFails() {
        serveApk("/apk", 1000)
        val file = temp.root.resolve("update.apk")
        assertEquals(DownloadResult.Failed, source().download(release("/apk", 5000), file) { _, _ -> true })
        assertFalse(file.exists())
    }

    @Test
    fun aMissingApkFails() {
        val file = temp.root.resolve("update.apk")
        assertEquals(DownloadResult.Failed, source().download(release("/gone", 5000), file) { _, _ -> true })
        assertFalse(file.exists())
    }

    @Test
    fun cancellingStops() {
        serveApk("/apk", 300_000)
        val file = temp.root.resolve("update.apk")
        val result = source().download(release("/apk", 300_000), file) { done, _ -> done < 64 * 1024 }
        assertEquals(DownloadResult.Cancelled, result)
        assertFalse(file.exists())
    }
}
