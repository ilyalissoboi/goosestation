// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Cover downloads through the core's HTTPDownloader, for the library's tiles, against a server on the device (the
 * emulators can't reach GitHub over HTTPS). Each test runs in its own process, with its own data root.
 */
@RunWith(AndroidJUnit4::class)
class CoverDownloadsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "cover-downloads")
    private val dataRoot = File(root, "data")
    private val covers = File(dataRoot, "covers")
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val base = "http://127.0.0.1:${server.localPort}"

    // The paths asked for, decoded, in order.
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    // What the server answers for a path: a whole HTTP response, or null to hold the connection without answering.
    @Volatile private var answer: (String) -> ByteArray? = { notFound() }

    private lateinit var games: List<GameEntry>

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        TestTrees.deleteAll(context)
        val tree = TestTrees.create(context, FILES)
        ContentBridge.init(context)
        initializeCore(context, dataRoot, File(root, "bios"), File(root, "offline"))
        NativeLibrary.setGameFolders(arrayOf(tree))
        NativeLibrary.refreshGameList(false)
        // Lists the tiles, which the download goes through.
        games = NativeLibrary.getGameList().toList()
        serve()
    }

    @After
    fun tearDown() {
        server.close()
        TestTrees.deleteAll(context)
    }

    @Test
    fun savesTheFirstSourceThatAnswers() {
        answer = { path -> if (path == "/b/GAME C.png") image(PNG) else notFound() }
        val progress = Recorder()

        val summary = download(listOf("$base/a/\${title}.png", "$base/b/\${title}.png"), progress)

        assertEquals(2, summary.total)
        assertEquals(1, summary.found)
        assertEquals(1, summary.missing)
        assertFalse(summary.cancelled)
        val saved = File(covers, "GAME C.png")
        val gameC = games.single { it.title == "GAME C" }
        // The core has the data root canonicalized.
        val found = progress.found.map { (game, cover) -> game to File(cover).canonicalPath }
        assertEquals(listOf(gameC.path to saved.canonicalPath), found)
        assertArrayEquals(PNG, saved.readBytes())
        assertEquals(listOf("/a/GAME C.png", "/b/GAME C.png", "/a/GAME D.png", "/b/GAME D.png"), asked.toList())
        assertEquals(listOf(1 to 2, 2 to 2), progress.games.map { it.first to it.second })
        val listed = NativeLibrary.getGameList().single { it.title == "GAME C" }.coverPath
        assertEquals(saved.canonicalPath, listed?.let { File(it).canonicalPath })
    }

    @Test
    fun keepsACoverThatIsAlreadyThere() {
        covers.mkdirs()
        val existing = File(covers, "GAME C.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        // The library reads its covers again.
        games = NativeLibrary.getGameList().toList()
        answer = { image(PNG) }

        val summary = download(listOf("$base/\${title}.png"), Recorder())

        assertEquals(1, summary.total)
        assertEquals(1, summary.found)
        assertEquals(listOf("/GAME D.png"), asked.toList())
        assertArrayEquals(byteArrayOf(1, 2, 3), existing.readBytes())
        assertFalse(File(covers, "GAME C.png").exists())
    }

    @Test
    fun cancelStopsTheRequestInFlight() {
        answer = { null }
        val progress = Recorder(cancelAfterMs = 500)
        val started = System.nanoTime()

        val summary = download(listOf("$base/\${title}.png"), progress)

        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds < 5)
        assertTrue(summary.cancelled)
        assertEquals(0, summary.found)
        assertEquals(1, progress.games.size)
        assertFalse(covers.exists() && covers.list()!!.isNotEmpty())
    }

    @Test
    fun aClosedPortIsUnreachable() {
        val closed = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val summary = download(listOf("http://127.0.0.1:$closed/\${title}.png"), Recorder())

        assertEquals(2, summary.total)
        assertEquals(2, summary.unreachable)
        assertEquals(0, summary.found)
    }

    private fun download(
        sources: List<String>,
        progress: CoverProgress,
    ) = NativeCoverApi.downloadCovers(sources, useSerial = true, progress = progress)

    /** Records what the download reports; with [cancelAfterMs], cancels that long after the first game starts. */
    private class Recorder(
        private val cancelAfterMs: Long = -1,
    ) : CoverProgress {
        val games = Collections.synchronizedList(mutableListOf<Triple<Int, Int, String>>())
        val found = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

        @Volatile private var firstGame = 0L

        override fun game(
            position: Int,
            total: Int,
            title: String,
        ) {
            if (firstGame == 0L) firstGame = System.nanoTime()
            games += Triple(position, total, title)
        }

        override fun found(
            gamePath: String,
            coverPath: String,
        ) {
            found += gamePath to coverPath
        }

        override fun cancelled() =
            cancelAfterMs >= 0 && firstGame != 0L && (System.nanoTime() - firstGame) / 1_000_000 >= cancelAfterMs
    }

    // Answers each connection on its own thread, by [answer].
    private fun serve() {
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) {
                    socket.use {
                        val input = BufferedInputStream(it.getInputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) return@thread
                            head.append(b.toChar())
                        }
                        val path = URLDecoder.decode(head.split(' ')[1], "UTF-8")
                        asked += path
                        val reply = answer(path)
                        if (reply == null) {
                            Thread.sleep(20_000)
                            return@thread
                        }
                        it.getOutputStream().write(reply)
                        it.getOutputStream().flush()
                    }
                }
            }
        }
    }

    private companion object {
        // A PNG's signature (which is how a cover is told from a web page), then a few bytes.
        val PNG =
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)

        fun psExe() = ByteArray(2048).also { "PS-X EXE".toByteArray().copyInto(it) }

        val FILES: Map<String, ByteArray> = mapOf("GAME C.PSEXE" to psExe(), "GAME D.PSEXE" to psExe())

        fun image(body: ByteArray) =
            "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                .encodeToByteArray() + body

        fun notFound() =
            "HTTP/1.1 404 Not Found\r\nContent-Type: text/html\r\nContent-Length: 2\r\nConnection: close\r\n\r\nno"
                .encodeToByteArray()
    }
}
