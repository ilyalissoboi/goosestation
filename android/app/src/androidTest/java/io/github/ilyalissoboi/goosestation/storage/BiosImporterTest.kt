// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BiosImporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val biosDir = File(context.filesDir, "bios")

    @Before
    fun setUp() {
        biosDir.deleteRecursively()
        ContentBridge.init(context)
    }

    @After
    fun tearDown() {
        TestTrees.deleteAll(context)
        biosDir.deleteRecursively()
    }

    @Test
    fun junkIsSkipped() {
        val tree =
            TestTrees.create(
                context,
                mapOf("zeros.bin" to ByteArray(512 * 1024), "readme.txt" to "hi".toByteArray()),
            )

        val result = runBlocking { BiosImporter(context).import(tree) }

        assertNull(result.error)
        assertEquals(emptyList<String>(), result.images)
        assertEquals(emptyList<String>(), biosDir.list()?.toList().orEmpty())
    }

    @Test
    fun staleCopiesAreRemoved() {
        val bytes = realBios()
        biosDir.mkdirs()
        val old = File(biosDir, "old.bin").apply { writeBytes(ByteArray(16)) }
        val tree = TestTrees.create(context, mapOf("ps-30j.bin" to bytes))

        val result = runBlocking { BiosImporter(context).import(tree) }

        assertNull(result.error)
        assertEquals(1, result.images.size)
        assertFalse(old.exists())
    }

    @Test
    fun emptyFolderKeepsCopies() {
        biosDir.mkdirs()
        val old = File(biosDir, "old.bin").apply { writeBytes(ByteArray(16)) }
        val tree = TestTrees.create(context, mapOf("readme.txt" to "hi".toByteArray()))

        val result = runBlocking { BiosImporter(context).import(tree) }

        assertNull(result.error)
        assertEquals(emptyList<String>(), result.images)
        assertTrue(old.exists())
    }

    @Test
    fun unreadableFolderKeepsCopies() {
        biosDir.mkdirs()
        val old = File(biosDir, "old.bin").apply { writeBytes(ByteArray(16)) }

        val result =
            runBlocking {
                BiosImporter(context).import("content://io.github.ilyalissoboi.goosestation.nosuchprovider/tree/x")
            }

        assertNotNull(result.error)
        assertEquals(emptyList<String>(), result.images)
        assertTrue(old.exists())
    }

    @Test
    fun realBiosIsCopied() {
        val bytes = realBios()
        val tree = TestTrees.create(context, mapOf("ps-30j.bin" to bytes))

        val result = runBlocking { BiosImporter(context).import(tree) }

        assertNull(result.error)
        assertEquals(1, result.images.size)
        assertTrue(result.images[0], result.images[0].contains("SCPH-5500"))
        assertArrayEquals(bytes, File(biosDir, "ps-30j.bin").readBytes())
    }

    /** The real BIOS's bytes; skips the test if it wasn't pushed. */
    private fun realBios(): ByteArray {
        val source = File(REAL_BIOS)
        assumeTrue("Push ps-30j.bin to $REAL_BIOS first", source.canRead())
        return source.readBytes()
    }

    private companion object {
        const val REAL_BIOS = "/data/local/tmp/goose-test/ps-30j.bin"
    }
}
