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

/** Changing the data folder, from one test tree (the old folder, already pulled) to another. */
@RunWith(AndroidJUnit4::class)
class DataFolderChangeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "data-change-test-${UUID.randomUUID()}")
    private val workingCopy = File(scratch, "data")
    private val manifestFile = File(scratch, "mirror-manifest.txt")
    private val never: (String) -> Boolean = { false }

    /** The old folder, once [pulledFrom] made it. */
    private lateinit var oldTree: String

    @Before
    fun setUp() {
        // As the app makes it before its first pull: the mirror doesn't run without one.
        workingCopy.mkdirs()
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.refused = emptySet()
        TestTrees.setProviderEnabled(context, true)
        TestTrees.deleteAll(context)
        scratch.deleteRecursively()
    }

    private fun store(tree: String) = SafFolderStore(context.contentResolver, tree)

    private fun mirror(tree: String) =
        Mirror(store(tree), workingCopy, manifestFile, System::currentTimeMillis) { LAYOUT }

    /**
     * The old folder ([oldTree]) holds [files], pulled into the working copy as the app does at its start; returns
     * the folder's mirror.
     */
    private fun pulledFrom(files: Map<String, ByteArray>): Mirror {
        oldTree = TestTrees.create(context, files)
        return mirror(oldTree).also { runBlocking { it.pull(never) } }
    }

    /** Edits a working-copy file as the core would, settled. */
    private fun writeLocal(
        path: String,
        bytes: ByteArray,
    ) {
        local(path).apply {
            writeBytes(bytes)
            setLastModified(System.currentTimeMillis() - 3_000)
        }
    }

    private fun switchFrom(
        old: Mirror,
        tree: String,
        choice: DataFolderChoice,
        lossAccepted: Boolean = false,
        beforeApply: suspend () -> Unit = {},
    ) = runBlocking {
        DataFolderChange(workingCopy, manifestFile, LAYOUT)
            .switchFrom(old, pushOld = true, lossAccepted, tree, choice, store(tree), beforeApply)
    }

    private fun local(path: String) = File(workingCopy, path)

    private fun change(
        tree: String,
        choice: DataFolderChoice,
    ) = runBlocking { DataFolderChange(workingCopy, manifestFile, LAYOUT).apply(tree, choice, store(tree)) }

    @Test
    fun copyThisDevicesData() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, mapOf("memcards/b.mcd" to CARD_B))
        val newFolder = TestTrees.directory(context, new)

        change(new, DataFolderChoice.CopyThisDevicesData)

        assertArrayEquals(CARD_A, File(newFolder, "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD_B, File(newFolder, "memcards/b.mcd").readBytes())
        val pulled = runBlocking { mirror(new).pull(never) }
        assertEquals(setOf("memcards/b.mcd"), pulled.changed)
        assertArrayEquals(CARD_A, local("memcards/a.mcd").readBytes())
        assertArrayEquals(CARD_B, local("memcards/b.mcd").readBytes())
    }

    @Test
    fun copyThisDevicesDataReplacesTheFoldersFileOfTheSameName() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, mapOf("memcards/a.mcd" to CARD_B))

        change(new, DataFolderChoice.CopyThisDevicesData)

        assertArrayEquals(CARD_A, File(TestTrees.directory(context, new), "memcards/a.mcd").readBytes())
        // The manifest knows both sides now: nothing to do in either direction.
        val mirror = mirror(new)
        assertEquals(emptySet<String>(), runBlocking { mirror.pull(never) }.changed)
        assertEquals(emptySet<String>(), runBlocking { mirror.push(true, never) }.changed)
        assertArrayEquals(CARD_A, local("memcards/a.mcd").readBytes())
    }

    @Test
    fun copyThisDevicesDataLeavesTheCacheOut() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        local("cache/gamelist.cache").apply {
            parentFile!!.mkdirs()
            writeBytes(CARD_B)
        }
        val new = TestTrees.create(context, emptyMap())

        change(new, DataFolderChoice.CopyThisDevicesData)

        assertEquals(setOf("memcards/a.mcd"), files(TestTrees.directory(context, new)))
    }

    @Test
    fun copyThisDevicesDataLeavesOtherThingsOut() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        local("games/x.chd").apply {
            parentFile!!.mkdirs()
            writeBytes(CARD_B)
        }
        val new = TestTrees.create(context, mapOf("notes.txt" to INI))

        change(new, DataFolderChoice.CopyThisDevicesData)

        assertEquals(setOf("memcards/a.mcd", "notes.txt"), files(TestTrees.directory(context, new)))
    }

    @Test
    fun useFolderDataTakesOnlyTheLayout() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, mapOf("memcards/b.mcd" to CARD_B, "games/x.chd" to CARD_A))

        change(new, DataFolderChoice.UseFolderData)

        assertEquals(setOf("memcards/b.mcd"), files(workingCopy))
        assertEquals(setOf("memcards/b.mcd", "games/x.chd"), files(TestTrees.directory(context, new)))
    }

    @Test
    fun useFolderData() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A, "settings.ini" to INI))
        local("cache/gamelist.cache").apply {
            parentFile!!.mkdirs()
            writeBytes(CARD_B)
        }
        val new = TestTrees.create(context, mapOf("memcards/b.mcd" to CARD_B, "savestates/x.sav" to CARD_A))

        change(new, DataFolderChoice.UseFolderData)

        assertEquals(
            setOf("memcards/b.mcd", "savestates/x.sav", "cache/gamelist.cache"),
            files(workingCopy),
        )
        assertArrayEquals(CARD_B, local("memcards/b.mcd").readBytes())
        assertArrayEquals(CARD_A, local("savestates/x.sav").readBytes())
        assertArrayEquals(CARD_B, local("cache/gamelist.cache").readBytes())
        // The manifest matches the new folder: a push deletes nothing from it, and a pull brings nothing.
        val mirror = mirror(new)
        runBlocking { mirror.push(true, never) }
        assertEquals(setOf("memcards/b.mcd", "savestates/x.sav"), files(TestTrees.directory(context, new)))
        assertEquals(emptySet<String>(), runBlocking { mirror.pull(never) }.changed)
    }

    @Test
    fun useFolderDataThatCantBeListedKeepsTheWorkingCopy() {
        pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val manifest = manifestFile.readText()
        val missing = TestTrees.create(context, emptyMap()).replace("tree-", "gone-")

        val failure = runCatching { change(missing, DataFolderChoice.UseFolderData) }.exceptionOrNull()

        assertTrue(failure.toString(), failure is java.io.IOException)
        assertArrayEquals(CARD_A, local("memcards/a.mcd").readBytes())
        assertEquals(manifest, manifestFile.readText())
    }

    @Test
    fun useFolderDataWaitsWhenTheOldFolderIsUnreachable() {
        val old = pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, mapOf("memcards/b.mcd" to CARD_B))
        writeLocal("memcards/a.mcd", CARD_B)
        val manifest = manifestFile.readText()

        TestTrees.setProviderEnabled(context, false)
        val applied = switchFrom(old, new, DataFolderChoice.UseFolderData)

        assertFalse(applied)
        assertArrayEquals(CARD_B, local("memcards/a.mcd").readBytes())
        assertEquals(setOf("memcards/a.mcd"), files(workingCopy))
        assertEquals(manifest, manifestFile.readText())
    }

    @Test
    fun useFolderDataWaitsWhenAChangeDidntReachTheOldFolder() {
        val old = pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, mapOf("memcards/b.mcd" to CARD_B))
        writeLocal("memcards/a.mcd", CARD_B)

        TestDocumentsProvider.refused = setOf("a.mcd.goosetmp")
        assertFalse(switchFrom(old, new, DataFolderChoice.UseFolderData))
        assertArrayEquals(CARD_B, local("memcards/a.mcd").readBytes())

        // The old folder's mirror still runs, and gets the change once it can.
        TestDocumentsProvider.refused = emptySet()
        assertTrue(runBlocking { old.push(true, never) }.complete)
        assertArrayEquals(CARD_B, File(TestTrees.directory(context, oldTree), "memcards/a.mcd").readBytes())
    }

    @Test
    fun useFolderDataGoesOnOnceTheLossIsAccepted() {
        val old = pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, mapOf("memcards/b.mcd" to CARD_B))
        writeLocal("memcards/a.mcd", CARD_B)
        TestDocumentsProvider.refused = setOf("a.mcd.goosetmp")

        val applied = switchFrom(old, new, DataFolderChoice.UseFolderData, lossAccepted = true)

        assertTrue(applied)
        assertEquals(setOf("memcards/b.mcd"), files(workingCopy))
        // Closed: nothing more goes to the old folder.
        assertFalse(runBlocking { old.push(true, never) }.complete)
    }

    @Test
    fun copyThisDevicesDataGoesOnAfterAnIncompletePush() {
        val old = pulledFrom(mapOf("memcards/a.mcd" to CARD_A))
        val new = TestTrees.create(context, emptyMap())
        writeLocal("memcards/a.mcd", CARD_B)
        // The old folder refuses the card; the new one, after the old mirror is closed, takes it.
        TestDocumentsProvider.refused = setOf("a.mcd.goosetmp")

        val applied =
            switchFrom(old, new, DataFolderChoice.CopyThisDevicesData) { TestDocumentsProvider.refused = emptySet() }

        assertTrue(applied)
        assertArrayEquals(CARD_B, File(TestTrees.directory(context, new), "memcards/a.mcd").readBytes())
        assertArrayEquals(CARD_B, local("memcards/a.mcd").readBytes())
    }

    private fun files(root: File) =
        root
            .walk()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()

    private companion object {
        val LAYOUT = DataLayout.withCorePaths(listOf("memcards/"), cacheFolder = "cache/")
        val CARD_A = ByteArray(128) { 0xA1.toByte() }
        val CARD_B = ByteArray(128) { 0xB2.toByte() }
        val INI = "[Main]\nSettingsVersion = 3\n".toByteArray()
    }
}
