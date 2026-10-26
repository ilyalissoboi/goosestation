// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentBridgeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var tree: String

    @Before
    fun setUp() {
        tree =
            TestTrees.create(
                context,
                mapOf(
                    "FF7/disc 1.cue" to "FILE \"disc 1 (Track 1).bin\" BINARY\n".toByteArray(),
                    "FF7/disc 1 (Track 1).bin" to ByteArray(4096) { it.toByte() },
                    "Disc?/Final Fantasy #1 (100%).cue" to "FILE \"x.bin\" BINARY\n".toByteArray(),
                ),
            )
        ContentBridge.init(context)
    }

    @After
    fun tearDown() {
        TestDocumentsProvider.loading = emptySet()
        TestTrees.deleteAll(context)
    }

    @Test
    fun opensFileForReading() {
        val fd = ContentBridge.openForReading("$tree/FF7/disc 1 (Track 1).bin")

        assertTrue("fd $fd", fd >= 0)
        ParcelFileDescriptor.adoptFd(fd).use { assertEquals(4096L, Os.fstat(it.fileDescriptor).st_size) }
    }

    @Test
    fun readsThroughAFolderStillLoading() {
        // The tree's root is still being fetched: what it lists so far (FF7) is used. Only the mirror, which
        // would read missing files as deletions, refuses such a listing.
        TestDocumentsProvider.loading = setOf(TestTrees.directory(context, tree).name)

        val fd = ContentBridge.openForReading("$tree/FF7/disc 1 (Track 1).bin")

        assertTrue("fd $fd", fd >= 0)
        ParcelFileDescriptor.adoptFd(fd).close()
        assertNotNull(ContentBridge.stat("$tree/FF7"))
        assertEquals(setOf("FF7", "Disc?"), ContentBridge.listDirectory(tree)!!.map { it.name }.toSet())
    }

    @Test
    fun opensNameHoldingUriSyntax() {
        val fd = ContentBridge.openForReading("$tree/Disc?/Final Fantasy #1 (100%).cue")

        assertTrue("fd $fd", fd >= 0)
        ParcelFileDescriptor.adoptFd(fd).close()
    }

    @Test
    fun statsDirectoryAndFile() {
        val dir = ContentBridge.stat("$tree/FF7")
        val file = ContentBridge.stat("$tree/FF7/disc 1 (Track 1).bin")

        assertNotNull(dir)
        assertEquals(1L, dir!![2])
        assertNotNull(file)
        assertEquals(4096L, file!![0])
        assertTrue(file[1] > 0)
        assertEquals(0L, file[2])
        assertNull(ContentBridge.stat("$tree/FF7/missing.bin"))
    }

    @Test
    fun listsTreeRoot() {
        val entries = ContentBridge.listDirectory(tree)

        assertNotNull(entries)
        assertEquals(setOf("FF7", "Disc?"), entries!!.map { it.name }.toSet())
        assertTrue(entries.all { it.isDirectory })
    }

    @Test
    fun missingNameIsNotFound() {
        assertEquals(-2, ContentBridge.openForReading("$tree/FF7/disc 2.cue"))
    }

    @Test
    fun authorityWithoutProviderFailsWithoutThrowing() {
        val path = "content://io.github.ilyalissoboi.goosestation.nosuchprovider/tree/x/a.bin"

        assertTrue(ContentBridge.openForReading(path) < 0)
        assertNull(ContentBridge.stat(path))
        assertNull(ContentBridge.listDirectory(path))
    }

    @Test
    fun nativeLibraryFindsTheBridge() {
        // JNI_OnLoad looks up ContentBridge's methods and NativeDocument's fields, and fails the load if one is
        // missing.
        NativeLibrary.setCallbacks(null)
    }
}
