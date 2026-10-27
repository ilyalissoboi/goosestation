// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.mirror

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.TestDocumentsProvider
import io.github.ilyalissoboi.goosestation.storage.TestTrees
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        workingCopy.mkdirs()
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.refused = emptySet()
        TestDocumentsProvider.loading = emptySet()
        TestDocumentsProvider.cantRename = emptySet()
        TestTrees.setProviderEnabled(context, true)
        TestTrees.deleteAll(context)
        scratch.deleteRecursively()
    }

    private fun createTree(files: Map<String, ByteArray>) {
        tree = TestTrees.create(context, files)
        folder = TestTrees.directory(context, tree)
    }

    private fun mirror(layout: DataLayout = LAYOUT) =
        Mirror(SafFolderStore(context.contentResolver, tree), workingCopy, manifestFile, System::currentTimeMillis) {
            layout
        }

    /** A mirror whose store runs [afterListing] once each listing is done, as if the folder changed right then. */
    private fun mirrorChangingAfterListing(afterListing: () -> Unit): Mirror {
        val store = SafFolderStore(context.contentResolver, tree)
        val changing =
            object : FolderStore by store {
                override fun listAll(layout: DataLayout) = store.listAll(layout).also { afterListing() }
            }
        return Mirror(changing, workingCopy, manifestFile, System::currentTimeMillis) { LAYOUT }
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

    private fun localNames() = workingCopy.walk().filter { it.isFile }.map { it.relativeTo(workingCopy).path }.toSet()

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

    @Test
    fun onlyTheLayoutIsPulled() {
        createTree(mapOf("games/big.chd" to CARD, "games/sub/b.bin" to CARD, "settings.ini" to INI))
        TestDocumentsProvider.listed.clear()

        val result = runBlocking { mirror().pull(never) }

        assertEquals(setOf("settings.ini"), localNames())
        assertEquals(setOf("settings.ini"), result.changed)
        // The folder's other things aren't even listed.
        val listed = TestDocumentsProvider.listed.toList()
        assertTrue(listed.toString(), listed.isNotEmpty() && listed.none { it.contains("/games") })
    }

    @Test
    fun theLayoutTheCoreReportsIsPulled() {
        createTree(mapOf("mycheats/a.cht" to INI, "settings.ini" to INI))

        runBlocking { mirror(DataLayout.withCorePaths(listOf("memcards/", "mycheats/"), "cache/")).pull(never) }

        assertEquals(setOf("mycheats/a.cht", "settings.ini"), localNames())
    }

    @Test
    fun otherThingsAreNeitherPushedNorDeletedInTheFolder() {
        createTree(mapOf("games/x.chd" to CARD, "notes.txt" to INI))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        writeLocal("games/y.chd", CARD2)
        runBlocking { mirror.push(true, never) }

        assertEquals(setOf("games/x.chd", "notes.txt"), folderNames())
    }

    /** The working copy holds [path] as an earlier build's pull left it: copied from the folder, in the manifest. */
    private fun strayFromAnEarlierBuild(path: String) {
        writeLocal(path, CARD)
        val local = local(path).let { FileState(it.length(), it.lastModified()) }
        val inFolder = File(folder, path).let { FileState(it.length(), it.lastModified()) }
        manifestFile.writeText(Manifest(mapOf(path to ManifestEntry(local, inFolder))).encode())
    }

    private fun manifestPaths() = Manifest.decode(manifestFile.readText())!!.entries.keys

    @Test
    fun straysLeaveTheWorkingCopyButNotTheFolder() {
        createTree(mapOf("games/x.chd" to CARD, "settings.ini" to INI))
        strayFromAnEarlierBuild("games/x.chd")
        writeLocal("cache/gamelist.cache", CARD)
        writeLocal("goosestation.log", INI)

        runBlocking { mirror().pull(never) }

        assertFalse(local("games/x.chd").exists())
        assertFalse(local("games").exists())
        assertEquals(setOf("settings.ini"), manifestPaths())
        assertArrayEquals(CARD, File(folder, "games/x.chd").readBytes())
        // The core's own files that are never mirrored stay.
        assertEquals(setOf("cache/gamelist.cache", "goosestation.log", "settings.ini"), localNames())
    }

    @Test
    fun straysLeaveOnAPushToo() {
        createTree(mapOf("games/x.chd" to CARD))
        strayFromAnEarlierBuild("games/x.chd")

        runBlocking { mirror().push(true, never) }

        assertFalse(local("games/x.chd").exists())
        assertEquals(emptySet<String>(), manifestPaths())
        assertArrayEquals(CARD, File(folder, "games/x.chd").readBytes())
    }

    @Test
    fun aStrayWithAnUnpushedChangeStays() {
        // A folder settings.ini no longer names (memory cards moved back to memcards/): its card changed since the
        // last run, and a card the manifest never saw, may not be in the data folder.
        createTree(mapOf("mycards/a.mcd" to CARD, "games/gone.chd" to CARD))
        strayFromAnEarlierBuild("mycards/a.mcd")
        val synced = Manifest.decode(manifestFile.readText())!!["mycards/a.mcd"]!!
        val gone = ManifestEntry(FileState(1, 1), FileState(1, 1))
        manifestFile.writeText(Manifest(mapOf("mycards/a.mcd" to synced, "games/gone.chd" to gone)).encode())
        writeLocal("mycards/a.mcd", CARD2)
        local("mycards/a.mcd").setLastModified(System.currentTimeMillis() - 10_000)
        writeLocal("mycards/b.mcd", CARD3)

        runBlocking { mirror().pull(never) }

        assertArrayEquals(CARD2, local("mycards/a.mcd").readBytes())
        assertArrayEquals(CARD3, local("mycards/b.mcd").readBytes())
        // Kept with its entry; the entry whose file is gone from the working copy goes.
        assertEquals(setOf("mycards/a.mcd"), manifestPaths())
        assertArrayEquals(CARD, File(folder, "mycards/a.mcd").readBytes())
    }

    @Test
    fun straysStayUntilTheCoreReportsItsLayout() {
        createTree(mapOf("mycards/a.mcd" to CARD))
        strayFromAnEarlierBuild("mycards/a.mcd")

        // Before the core starts, the folder its settings name may be outside the default layout.
        runBlocking { mirror(DataLayout.DEFAULT).pull(never) }

        assertTrue(local("mycards/a.mcd").exists())
        assertEquals(setOf("mycards/a.mcd"), manifestPaths())
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
        createTree(mapOf("memcards/x/inner.txt" to INI))
        val mirror = mirror()

        writeLocal("memcards/x", CARD)
        runBlocking { mirror.push(false, never) }

        assertArrayEquals(INI, File(folder, "memcards/x/inner.txt").readBytes())
        assertEquals(setOf("memcards/x/inner.txt"), folderNames())
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
        assertEquals(MirrorState.Incomplete(1), mirror.state.value)

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

    @Test
    fun aHeldMirrorRunsNothingUntilLetGo() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }
        val manifest = manifestFile.readText()

        runBlocking { mirror.hold("the SD card was removed") }
        assertEquals(MirrorState.Unreachable("the SD card was removed"), mirror.state.value)
        writeLocal("memcards/a.mcd", CARD2)
        File(folder, "memcards/b.mcd").writeBytes(CARD3)
        val pushed = runBlocking { mirror.push(true, never).also { mirror.pull(never) } }

        assertFalse(pushed.complete)
        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())
        assertFalse(local("memcards/b.mcd").exists())
        assertEquals(manifest, manifestFile.readText())
        assertEquals(MirrorState.Unreachable("the SD card was removed"), mirror.state.value)

        mirror.letGo()
        runBlocking { mirror.push(true, never).also { mirror.pull(never) } }

        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, local("memcards/b.mcd").readBytes())
        assertEquals(MirrorState.Idle, mirror.state.value)
    }

    @Test
    fun holdingStopsTheRunUnderWayBeforeItsNextAction() {
        createTree(mapOf("memcards/a.mcd" to CARD, "memcards/b.mcd" to CARD))
        runBlocking { mirror().pull(never) }
        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/b.mcd", CARD3)

        // The hold comes while the first file is being written, as an eject does: it waits for that run to stop.
        val store = SafFolderStore(context.contentResolver, tree)
        lateinit var mirror: Mirror
        var hold: Job? = null
        val holding =
            object : FolderStore by store {
                override fun replace(
                    path: String,
                    source: File,
                    expected: FileState?,
                ): FileState {
                    if (hold == null) {
                        hold =
                            CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                                mirror.hold("the SD card was removed")
                            }
                    }
                    return store.replace(path, source, expected)
                }
            }
        mirror = Mirror(holding, workingCopy, manifestFile, System::currentTimeMillis) { LAYOUT }
        val pushed = runBlocking { mirror.push(true, never) }
        runBlocking { hold!!.join() }

        assertFalse(pushed.complete)
        // One file went out, whole; the other wasn't started.
        val a = File(folder, "memcards/a.mcd").readBytes()
        val b = File(folder, "memcards/b.mcd").readBytes()
        assertTrue(
            (a.contentEquals(CARD2) && b.contentEquals(CARD)) || (a.contentEquals(CARD) && b.contentEquals(CARD3)),
        )
        assertNoTemporaries()
        assertEquals(MirrorState.Unreachable("the SD card was removed"), mirror.state.value)

        // The file that went out is recorded, so once let go, the next push sends only the other one.
        mirror.letGo()
        runBlocking { mirror.push(true, never) }
        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, File(folder, "memcards/b.mcd").readBytes())
        assertEquals(MirrorState.Idle, mirror.state.value)
    }

    @Test
    fun failedRunsCountUntilTheChangeGetsThrough() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }
        assertEquals(MirrorState.Idle, mirror.state.value)

        writeLocal("memcards/a.mcd", CARD2)
        TestDocumentsProvider.refused = setOf("a.mcd.goosetmp")
        runBlocking { mirror.push(true, never) }
        assertEquals(MirrorState.Incomplete(1), mirror.state.value)
        runBlocking { mirror.push(true, never) }
        assertEquals(MirrorState.Incomplete(2), mirror.state.value)
        // A pull saves nothing, so it leaves the count alone.
        runBlocking { mirror.pull(never) }
        assertEquals(MirrorState.Incomplete(2), mirror.state.value)

        TestDocumentsProvider.refused = emptySet()
        runBlocking { mirror.push(true, never) }
        assertEquals(MirrorState.Idle, mirror.state.value)
        assertArrayEquals(CARD2, File(folder, "memcards/a.mcd").readBytes())
    }

    // Written in place, a write cut short would leave a short card that the next pull takes as the folder's change.
    @Test
    fun aFolderThatCantRenameIsNeverWrittenInPlace() {
        createTree(mapOf("memcards/a.mcd" to CARD))
        TestDocumentsProvider.cantRename = setOf(folder.name)
        val mirror = mirror()
        runBlocking { mirror.pull(never) }
        val manifest = manifestFile.readBytes()

        writeLocal("memcards/a.mcd", CARD2)
        writeLocal("memcards/b.mcd", CARD3)
        val pushed = runBlocking { mirror.push(true, never) }

        val state = mirror.state.value
        assertTrue(state.toString(), state is MirrorState.Unreachable)
        assertEquals("its storage can't replace files safely", (state as MirrorState.Unreachable).reason)
        assertFalse(pushed.complete)
        assertArrayEquals(CARD, File(folder, "memcards/a.mcd").readBytes())
        assertFalse(File(folder, "memcards/b.mcd").exists())
        assertNoTemporaries()
        assertArrayEquals(manifest, manifestFile.readBytes())

        // The working copy keeps its changes: nothing in the folder reads as changed.
        runBlocking { mirror.pull(never) }
        assertArrayEquals(CARD2, local("memcards/a.mcd").readBytes())
        assertArrayEquals(CARD3, local("memcards/b.mcd").readBytes())
    }

    @Test
    fun aMissingWorkingCopyDrivesNoDeletions() {
        createTree(mapOf("memcards/a.mcd" to CARD, "settings.ini" to INI))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        assertTrue(workingCopy.deleteRecursively())
        val pushed = runBlocking { mirror.push(true, never) }

        assertFalse(pushed.complete)
        assertEquals(setOf("memcards/a.mcd", "settings.ini"), folderNames())
        assertReachable(mirror)
    }

    @Test
    fun anUnreadableWorkingCopyFolderDrivesNoDeletions() {
        createTree(mapOf("memcards/a.mcd" to CARD, "settings.ini" to INI))
        val mirror = mirror()
        runBlocking { mirror.pull(never) }

        val memcards = local("memcards")
        assertTrue(memcards.setReadable(false))
        val pushed =
            try {
                runBlocking { mirror.push(true, never) }
            } finally {
                memcards.setReadable(true)
            }

        assertFalse(pushed.complete)
        assertEquals(setOf("memcards/a.mcd", "settings.ini"), folderNames())
        assertReachable(mirror)
    }

    private companion object {
        /** As the core reports it once it runs, with its defaults. */
        val LAYOUT = DataLayout.withCorePaths(listOf("memcards/"), cacheFolder = "cache/")
        val INI = "[Main]\nSettingsVersion = 3\n".toByteArray()
        val CARD = ByteArray(128 * 1024) { it.toByte() }
        val CARD2 = ByteArray(128 * 1024) { (it * 7).toByte() }
        val CARD3 = ByteArray(128 * 1024) { (it * 13).toByte() }
    }
}
