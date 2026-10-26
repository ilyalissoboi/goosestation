// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import io.github.ilyalissoboi.goosestation.core.Resources
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import io.github.ilyalissoboi.goosestation.storage.TestDocumentsProvider
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The core's game list, scanning a tree of the test provider through the content:// file layer. Each test runs in
 * its own process, so it gets its own initialize() and data root, and so its own game list cache.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScanTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "library-scan")
    private val dataRoot = File(root, "data")
    private lateinit var tree: String
    private lateinit var treeId: String

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        TestTrees.deleteAll(context)
        tree = TestTrees.create(context, FILES)
        treeId = TestTrees.directory(context, tree).name
        ContentBridge.init(context)

        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(
                dataRoot.absolutePath,
                resources.absolutePath,
                File(root, "bios").absolutePath,
                "test-install",
            ),
        )
        NativeLibrary.setGameFolders(arrayOf(tree))
        NativeLibrary.refreshGameList(false)
    }

    @After
    fun tearDown() {
        clearRequests()
        TestTrees.deleteAll(context)
    }

    @Test
    fun listsGamesInTheTree() {
        val games = NativeLibrary.getGameList()

        val gameA = games.singleOrNull { it.path.endsWith("/Game A/Game A.cue") }
        assertNotNull(games.toList().toString(), gameA)
        assertEquals("Game A", gameA!!.title)
        assertTrue(gameA.path.startsWith("$tree/"))
        assertFalse(games.toList().toString(), games.any { it.path.endsWith(".bin", ignoreCase = true) })
        // The playlist could only be read through its sibling CUEs and their BINs.
        assertNotNull(games.toList().toString(), games.singleOrNull { it.path.endsWith("/Set/Game B.m3u") })
        assertFalse(games.any { it.path.endsWith("notes.txt") })
        val gameC = games.singleOrNull { it.path.endsWith("/GAME C.PSEXE") }
        assertNotNull(games.toList().toString(), gameC)
        assertEquals("GAME C", gameC!!.title)
        assertEquals(games.map { it.title }.sortedWith(String.CASE_INSENSITIVE_ORDER), games.map { it.title })
    }

    @Test
    fun secondRefreshUsesTheCache() {
        val first = NativeLibrary.getGameList().toList()
        clearRequests()

        NativeLibrary.refreshGameList(false)

        assertEquals(first, NativeLibrary.getGameList().toList())
        assertEquals(
            mapOf(treeId to 1, "$treeId/Game A" to 1, "$treeId/Set" to 1),
            TestDocumentsProvider.listed.groupingBy { it }.eachCount(),
        )
        assertEquals(emptyList<String>(), TestDocumentsProvider.opened.toList())
    }

    @Test
    fun changedFileIsScannedAgain() {
        val cue = File(TestTrees.directory(context, tree), "Game A/Game A.cue")
        val modified = cue.lastModified()
        cue.writeText("REM rewritten\n" + CUE_A)
        assertTrue(cue.setLastModified(modified + 60_000))
        clearRequests()

        NativeLibrary.refreshGameList(false)

        assertTrue(TestDocumentsProvider.opened.toString(), "$treeId/Game A/Game A.cue" in TestDocumentsProvider.opened)
        assertFalse(TestDocumentsProvider.opened.toString(), "$treeId/Set/Game B.m3u" in TestDocumentsProvider.opened)
        val gameA = NativeLibrary.getGameList().singleOrNull { it.path.endsWith("/Game A/Game A.cue") }
        assertEquals("Game A", gameA?.title)
    }

    private fun clearRequests() {
        TestDocumentsProvider.opened.clear()
        TestDocumentsProvider.listed.clear()
    }

    private companion object {
        const val SECTOR_SIZE = 2352

        fun cueText(bin: String) = "FILE \"$bin\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00\n"

        fun cue(bin: String) = cueText(bin).toByteArray()

        val CUE_A = cueText("Game A (Track 1).bin")

        fun bin() = ByteArray(300 * SECTOR_SIZE)

        /** A PS-X EXE with nothing but its 2 KiB header, as BIOS::IsValidPSExeHeader() accepts. */
        fun psExe() = ByteArray(2048).also { "PS-X EXE".toByteArray().copyInto(it) }

        val FILES: Map<String, ByteArray> =
            mapOf(
                "Game A/Game A.cue" to CUE_A.toByteArray(),
                "Game A/Game A (Track 1).bin" to bin(),
                "Set/Game B.m3u" to "Game B (Disc 1).cue\nGame B (Disc 2).cue\n".toByteArray(),
                "Set/Game B (Disc 1).cue" to cue("Game B (Disc 1) (Track 1).bin"),
                "Set/Game B (Disc 1) (Track 1).bin" to bin(),
                "Set/Game B (Disc 2).cue" to cue("Game B (Disc 2) (Track 1).bin"),
                "Set/Game B (Disc 2) (Track 1).bin" to bin(),
                "notes.txt" to "Not a game.\n".toByteArray(),
                "GAME C.PSEXE" to psExe(),
            )
    }
}
