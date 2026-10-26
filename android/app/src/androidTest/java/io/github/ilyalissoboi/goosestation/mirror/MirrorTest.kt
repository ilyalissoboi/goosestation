// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.TestDocumentsProvider
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MirrorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "mirror-test-${UUID.randomUUID()}")
    private val workingCopy = File(scratch, "data")
    private val manifestFile = File(scratch, "mirror-manifest.txt")
    private val never: (String) -> Boolean = { false }

    private lateinit var tree: String
    private lateinit var folder: File

    @Before
    fun setUp() {
        scratch.mkdirs()
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.refused = emptySet()
        TestDocumentsProvider.loading = emptySet()
        TestTrees.setProviderEnabled(context, true)
        TestTrees.deleteAll(context)
        scratch.deleteRecursively()
    }

    private fun createTree(files: Map<String, ByteArray>) {
        tree = TestTrees.create(context, files)
        folder = TestTrees.directory(context, tree)
    }

    private fun mirror() =
        Mirror(SafFolderStore(context.contentResolver, tree), workingCopy, manifestFile, System::currentTimeMillis)

    /** A mirror whose store runs [afterListing] once each listing is done, as if the folder changed right then. */
    private fun mirrorChangingAfterListing(afterListing: () -> Unit): Mirror {
        val store = SafFolderStore(context.contentResolver, tree)
        val changing =
            object : FolderStore by store {
                override fun listAll() = store.listAll().also { afterListing() }
            }
        return Mirror(changing, workingCopy, manifestFile, System::currentTimeMillis)
    }

    private fun assertReachable(mirror: Mirror) {
        assertFalse(mirror.state.value.toString(), mirror.state.value is MirrorState.Unreachable)
    }

    private fun local(path: String) = File(workingCopy, path)

    /** Writes a working-copy file as the core would, settled: last changed 3 s ago. */
    private fun writeLocal(
        path: String,
        bytes: ByteArray,
    ) {
        local(path).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
            setLastModified(System.currentTimeMillis() - 3_000)
        }
    }

    /** Every name in the folder's tree, relative, for checking that no temporary document is left. */
    private fun folderNames() = folder.walk().filter { it.isFile }.map { it.relativeTo(folder).path }.toSet()

    private fun assertNoTemporaries() {
        val names = folderNames()
        assertTrue(names.toString(), names.none { it.endsWith(".goosetmp") || it.endsWith(".goosebak") })
    }

    @Test
    fun firstPullFillsEmptyWorkingCopy() {
        createTree(mapOf("settings.ini" to INI, "memcards/a.mcd" to CARD))

        val result = runBlocking { mirror().pull(never) }

        assertArrayEquals(INI, local("settings.ini").readBytes())
        assertArrayEquals(CARD, local("memcards/a.mcd").readBytes())
        assertEquals(setOf("settings.ini", "memcards/a.mcd"), result.changed)
    }

    @Test
    fun pushSendsLocalChange() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertNoTemporaries()
        assertEquals(MirrorState.Idle, mirror.state.value)
    }

    @Test
    fun pushCreatesNewFoldersAndFiles() {
        createTree(emptyMap())
        val mirror = mirror()

        writeLocal("savestates/SLUS-00001_1.sav", CARD)
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD, File(folder, "savestates/SLUS-00001_1.sav").readBytes())
        assertNoTemporaries()
    }

    @Test
    fun folderEditWins() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        File(folder, "memcards/a.mcd").apply {
            writeBytes(CARD3)
            setLastModified(System.currentTimeMillis() + 5_000)
        }
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD3, local("memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, File(folder, "memcards/a.mcd").readBytes())
    }

    @Test
    fun pushReportsWhatTheFolderReplaced() {
        createTree(mapOf("settings.ini" to INI, "memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        // The core saved its settings while the folder's copy was edited: the folder's wins, in the working copy.
        writeLocal("settings.ini", CARD2)
        File(folder, "settings.ini").apply {
            writeBytes(CARD3)
            setLastModified(System.currentTimeMillis() + 5_000)
        }
        writeLocal("memcards/a.mcd", CARD2)
        val result = runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD3, local("settings.ini").readBytes())
        assertEquals(setOf("settings.ini"), result.changed)
    }

    @Test
    fun deferralIsCheckedAgainBeforeEachAction() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }
        File(folder, "memcards/a.mcd").apply {
            writeBytes(CARD3)
            setLastModified(System.currentTimeMillis() + 5_000)
        }

        // Not deferred while the run plans, deferred by the time it acts: a game started in between.
        val asked = HashSet<String>()
        val result = runBlocking { mirror.pull { path -> !asked.add(path) } }

        assertArrayEquals(CARD, local("memcards/a.mcd").readBytes())
        assertEquals(emptySet<String>(), result.changed)
        // Left for a later run.
        runBlocking { mirror.pull(never) }
        assertArrayEquals(CARD3, local("memcards/a.mcd").readBytes())
    }

    @Test
    fun deletionsFollow() {
        createTree(mapOf("memcards/a.mcd" to CARD, "memcards/b.mcd" to CARD2))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        assertTrue(File(folder, "memcards/a.mcd").delete())
        runBlocking { mirror.pull(never) }
        assertFalse(local("memcards/a.mcd").exists())

        assertTrue(local("memcards/b.mcd").delete())
        runBlocking { mirror.push(false, never) }
        assertFalse(File(folder, "memcards/b.mcd").exists())
    }

    @Test
    fun cacheNotMirrored() {
        createTree(emptyMap())
        val mirror = mirror()

        writeLocal("cache/gamelist.cache", CARD)
        runBlocking { mirror.push(true, never) }

        assertEquals(emptySet<String>(), folderNames())
    }

    /** A replace that stopped after moving the old file aside: only the `.goosebak` holds it. */
    private fun interruptReplace(path: String) {
        val file = File(folder, path)
        assertTrue(file.renameTo(File(file.path + ".goosebak")))
        File(file.path + ".goosetmp").writeBytes(CARD2.copyOf(100))
    }

    @Test
    fun recoversInterruptedReplace() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        interruptReplace("memcards/a.mcd")
        runBlocking { mirror.pull(never) }

        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD, local("memcards/a.mcd").readBytes())
        assertNoTemporaries()
    }

    @Test
    fun recoversInterruptedReplaceBeforePush() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        interruptReplace("memcards/a.mcd")
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD2, local("memcards/a.mcd").readBytes())
        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertNoTemporaries()
    }

    @Test
    fun manifestSurvivesRestart() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        runBlocking { mirror().pull(never) }

        // A new process: a fresh Mirror reads the manifest, so the local file isn't taken for a new one, which
        // the folder's copy would win over.
        writeLocal("memcards/a.mcd", CARD2)
        runBlocking { mirror().push(false, never) }

        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
    }

    @Test
    fun unreachableKeepsWorkingCopy() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }
        val manifest = manifestFile.readBytes()

        TestTrees.setProviderEnabled(context, false)
        writeLocal("memcards/a.mcd", CARD2)
        runBlocking { mirror.push(false, never) }

        assertTrue(mirror.state.value.toString(), mirror.state.value is MirrorState.Unreachable)
        assertArrayEquals(CARD2, local("memcards/a.mcd").readBytes())
        assertArrayEquals(manifest, manifestFile.readBytes())
        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())

        TestTrees.setProviderEnabled(context, true)
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertEquals(MirrorState.Idle, mirror.state.value)
        assertNoTemporaries()
    }

    @Test
    fun unreachableMidRunStopsCleanly() {
        createTree(mapOf("memcards/a.mcd" to CARD, "memcards/b.mcd" to CARD))
        runBlocking { mirror().pull(never) }
        val manifest = manifestFile.readBytes()

        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/b.mcd", CARD3)
        // The folder goes away between the listing and the first copy.
        val mirror = mirrorChangingAfterListing { TestTrees.setProviderEnabled(context, false) }
        runBlocking { mirror.push(false, never) }

        assertTrue(mirror.state.value.toString(), mirror.state.value is MirrorState.Unreachable)
        assertArrayEquals(manifest, manifestFile.readBytes())
        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD, File(folder, "memcards/b.mcd").readBytes())
        assertNoTemporaries()

        TestTrees.setProviderEnabled(context, true)
        runBlocking { mirror().push(false, never) }

        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, File(folder, "memcards/b.mcd").readBytes())
    }

    @Test
    fun pushDoesNotReplaceAFolderWithAFile() {
        createTree(mapOf("x/inner.txt" to INI))
        val mirror = mirror()

        writeLocal("x", CARD)
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(INI, File(folder, "x/inner.txt").readBytes())
        assertEquals(setOf("x/inner.txt"), folderNames())
        assertReachable(mirror)
    }

    @Test
    fun refusedFileDoesNotStopThePush() {
        createTree(mapOf("memcards/a.mcd" to CARD, "memcards/b.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/b.mcd", CARD3)
        TestDocumentsProvider.refused = setOf("a.mcd", "a.mcd.goosetmp")
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, File(folder, "memcards/b.mcd").readBytes())
        assertEquals(MirrorState.Idle, mirror.state.value)

        TestDocumentsProvider.refused = emptySet()
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertNoTemporaries()
    }

    @Test
    fun refusedFileDoesNotStopThePull() {
        createTree(mapOf("memcards/a.mcd" to CARD, "memcards/b.mcd" to CARD2))
        val mirror = mirror()

        TestDocumentsProvider.refused = setOf("a.mcd")
        val result = runBlocking { mirror.pull(never) }

        assertFalse(local("memcards/a.mcd").exists())
        assertArrayEquals(CARD2, local("memcards/b.mcd").readBytes())
        assertEquals(setOf("memcards/b.mcd"), result.changed)
        assertEquals(MirrorState.Idle, mirror.state.value)
    }

    @Test
    fun folderChangeDuringPushWins() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        runBlocking { mirror().pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        // The user copies a card in from a PC after the listing, before the push's write.
        val mirror =
            mirrorChangingAfterListing {
                File(folder, "memcards/a.mcd").apply {
                    writeBytes(CARD3)
                    setLastModified(System.currentTimeMillis() + 5_000)
                }
            }
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD3, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD2, local("memcards/a.mcd").readBytes())
        assertReachable(mirror)
        assertNoTemporaries()

        runBlocking { mirror().push(false, never) }

        assertArrayEquals(CARD3, local("memcards/a.mcd").readBytes())
    }

    @Test
    fun fileAppearingDuringPushWins() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        runBlocking { mirror().pull(never) }

        writeLocal("memcards/c.mcd", CARD2)
        val mirror = mirrorChangingAfterListing { File(folder, "memcards/c.mcd").writeBytes(CARD3) }
        runBlocking { mirror.push(false, never) }

        // The provider renamed the written temporary file to "c (1).mcd"; it's deleted, and theirs is kept.
        assertEquals(setOf("memcards/a.mcd", "memcards/c.mcd"), folderNames())
        assertArrayEquals(CARD3, File(folder, "memcards/c.mcd").readBytes())
        assertReachable(mirror)

        runBlocking { mirror().push(false, never) }

        assertArrayEquals(CARD3, local("memcards/c.mcd").readBytes())
    }

    /** Writes [bytes] to the folder's [path] behind the provider's back, with a time ahead of the listing's. */
    private fun changeInFolder(
        path: String,
        bytes: ByteArray,
    ) {
        File(folder, path).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
            setLastModified(System.currentTimeMillis() + 5_000)
        }
    }

    @Test
    fun folderChangesDuringPushAllWin() {
        createTree(mapOf("memcards/a.mcd" to CARD, "memcards/b.mcd" to CARD))
        runBlocking { mirror().pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/b.mcd", CARD2)
        // Both cards copied in from a PC after the listing: a.mcd's failed check must not unguard b.mcd.
        val mirror =
            mirrorChangingAfterListing {
                changeInFolder("memcards/a.mcd", CARD3)
                changeInFolder("memcards/b.mcd", CARD3)
            }
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD3, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, File(folder, "memcards/b.mcd").readBytes())
        assertNoTemporaries()

        runBlocking { mirror().push(false, never) }

        assertArrayEquals(CARD3, local("memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, local("memcards/b.mcd").readBytes())
    }

    @Test
    fun fileAppearingAfterAFailureWins() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        runBlocking { mirror().pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/c.mcd", CARD2)
        // a.mcd fails its check first, so c.mcd is looked up afresh, and found.
        val mirror =
            mirrorChangingAfterListing {
                changeInFolder("memcards/a.mcd", CARD3)
                changeInFolder("memcards/c.mcd", CARD3)
            }
        runBlocking { mirror.push(false, never) }

        assertEquals(setOf("memcards/a.mcd", "memcards/c.mcd"), folderNames())
        assertArrayEquals(CARD3, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, File(folder, "memcards/c.mcd").readBytes())
    }

    @Test
    fun deleteSparesAFileChangedAfterListing() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        runBlocking { mirror().pull(never) }

        assertTrue(local("memcards/a.mcd").delete())
        val mirror = mirrorChangingAfterListing { changeInFolder("memcards/a.mcd", CARD3) }
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD3, File(folder, "memcards/a.mcd").readBytes())
        assertReachable(mirror)

        runBlocking { mirror().pull(never) }

        assertArrayEquals(CARD3, local("memcards/a.mcd").readBytes())
    }

    @Test
    fun foldersNamedLikeTemporariesSurvive() {
        createTree(
            mapOf(
                "memcards/a.mcd" to CARD,
                "memcards/a.mcd.goosetmp/keep.txt" to INI,
                "memcards/b.mcd" to CARD,
                "memcards/b.mcd.goosebak/keep.txt" to INI,
            ),
        )
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/b.mcd", CARD2)
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(INI, File(folder, "memcards/a.mcd.goosetmp/keep.txt").readBytes())
        assertArrayEquals(INI, File(folder, "memcards/b.mcd.goosebak/keep.txt").readBytes())
        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        // The folder in the way keeps b.mcd from being moved aside: it isn't written, but it isn't lost either.
        assertArrayEquals(CARD, File(folder, "memcards/b.mcd").readBytes())
        assertReachable(mirror)
    }

    @Test
    fun loadingListingDrivesNoDeletions() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        // A cloud provider still fetching the folder lists none of its files yet, marked as loading.
        TestDocumentsProvider.loading = setOf("memcards")
        runBlocking { mirror.pull(never) }

        assertArrayEquals(CARD, local("memcards/a.mcd").readBytes())
        assertReachable(mirror)

        writeLocal("memcards/b.mcd", CARD2)
        runBlocking { mirror.push(false, never) }
        assertFalse(File(folder, "memcards/b.mcd").exists())

        TestDocumentsProvider.loading = emptySet()
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(CARD, local("memcards/a.mcd").readBytes())
        assertArrayEquals(CARD2, File(folder, "memcards/b.mcd").readBytes())
    }

    @Test
    fun pushReportsWhetherItsPlanWasComplete() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }
        writeLocal("memcards/a.mcd", CARD2)

        // One file fails: the folder is reachable, but the card isn't in it.
        TestDocumentsProvider.refused = setOf("a.mcd.goosetmp")
        assertFalse(runBlocking { mirror.push(true, never) }.complete)
        assertReachable(mirror)
        TestDocumentsProvider.refused = emptySet()
        assertTrue(runBlocking { mirror.push(true, never) }.complete)
        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())

        // The folder is gone.
        writeLocal("memcards/a.mcd", CARD3)
        TestTrees.setProviderEnabled(context, false)
        assertFalse(runBlocking { mirror.push(true, never) }.complete)
    }

    @Test
    fun aClosedMirrorRunsNoMore() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        runBlocking { mirror.close() }
        writeLocal("memcards/a.mcd", CARD2)
        File(folder, "memcards/b.mcd").writeBytes(CARD3)
        val manifest = manifestFile.readText()
        val pushed =
            runBlocking {
                mirror.push(true, never).also { mirror.pull(never) }
            }

        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())
        assertFalse(local("memcards/b.mcd").exists())
        assertEquals(manifest, manifestFile.readText())
        assertFalse(pushed.complete)
    }

    private companion object {
        val INI = "[Main]\nSettingsVersion = 3\n".toByteArray()
        val CARD = ByteArray(128 * 1024) { it.toByte() }
        val CARD2 = ByteArray(128 * 1024) { (it * 7).toByte() }
        val CARD3 = ByteArray(128 * 1024) { (it * 13).toByte() }
    }
}
