// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.NativeMemcardEditorApi
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The memory card editor's card operations, in the core: each takes a card's bytes and gives the new ones. The cards
 * are formatted by the core, and their saves imported as raw saves made here.
 */
@RunWith(AndroidJUnit4::class)
class MemcardEditorCoreTest {
    private val core = SyncTestCore(InstrumentationRegistry.getInstrumentation().targetContext, "memcard-editor-core")
    private val api = NativeMemcardEditorApi

    @Before
    fun setUp() = core.start()

    @After
    fun tearDown() = core.finish()

    // A raw save of [blocks] blocks, filled with [fill], with one icon frame and an empty title (so full-width).
    private fun save(
        blocks: Int,
        fill: Int,
    ) = ByteArray(blocks * BLOCK) { fill.toByte() }.also {
        it[0] = 'S'.code.toByte()
        it[1] = 'C'.code.toByte()
        it[2] = 0x11
        it.fill(0, TITLE_START, TITLE_START + 64)
    }

    private fun cardWith(vararg saves: Pair<String, ByteArray>): ByteArray =
        saves.fold(api.format()) { card, (name, data) -> api.importSave(card, "$name.bin", data).bytes!! }

    private fun ByteArray.saves() = api.contents(this)!!.saves

    private fun ByteArray.save(name: String) = saves().first { it.fileName == name }

    @Test
    fun contentsListsSavesAndFreeBlocks() {
        val contents = api.contents(cardWith("BASLUS-00001" to save(1, 1), "BASLUS-00002" to save(2, 2)))!!

        assertEquals(12, contents.freeBlocks)
        assertEquals(listOf("BASLUS-00001", "BASLUS-00002"), contents.saves.map { it.fileName })
        assertEquals(listOf(1, 2), contents.saves.map { it.blocks })
        assertFalse(contents.saves[0].deleted)
        val icon = contents.saves[0].icon!!
        assertEquals(16, icon.width)
        assertEquals(16, icon.height)
    }

    @Test
    fun contentsIsNullForNotACard() {
        assertNull(api.contents(ByteArray(10)))
        assertNull(api.contents(ByteArray(CARD)))
    }

    @Test
    fun copyCopiesAndReplaceReplaces() {
        val from = cardWith("BASLUS-00001" to save(1, 1))
        val to = api.copySave(from, from.save("BASLUS-00001").firstBlock, api.format(), replace = false).bytes!!
        assertEquals(listOf("BASLUS-00001"), to.saves().map { it.fileName })

        val other = cardWith("BASLUS-00001" to save(1, 7))
        val refused = api.copySave(other, other.save("BASLUS-00001").firstBlock, to, replace = false)
        assertNull(refused.bytes)
        assertTrue(refused.error.isNotEmpty())

        val replaced = api.copySave(other, other.save("BASLUS-00001").firstBlock, to, replace = true).bytes!!
        assertEquals(1, replaced.saves().count { it.fileName == "BASLUS-00001" && !it.deleted })
        val exported = api.exportSave(replaced, replaced.save("BASLUS-00001").firstBlock).bytes!!
        assertEquals(7.toByte(), exported[128 + 100])
    }

    @Test
    fun deleteThenUndelete() {
        val card = cardWith("BASLUS-00001" to save(1, 1), "BASLUS-00002" to save(2, 2))
        for (name in listOf("BASLUS-00001", "BASLUS-00002")) {
            val deleted = api.deleteSave(card, card.save(name).firstBlock, permanently = false)!!
            assertTrue(deleted.save(name).deleted)
            val back = api.undeleteSave(deleted, deleted.save(name).firstBlock)!!
            assertFalse(back.save(name).deleted)
            assertEquals(card.save(name).blocks, back.save(name).blocks)
            assertEquals(api.contents(card)!!.freeBlocks, api.contents(back)!!.freeBlocks)
        }
    }

    @Test
    fun deletePermanentlyDropsIt() {
        val card = cardWith("BASLUS-00001" to save(1, 1))
        val deleted = api.deleteSave(card, card.save("BASLUS-00001").firstBlock, permanently = false)!!

        val erased = api.deleteSave(deleted, deleted.save("BASLUS-00001").firstBlock, permanently = true)!!

        assertTrue(erased.saves().isEmpty())
    }

    @Test
    fun undeleteRefusedOnceReused() {
        // The core takes the lowest free block, a deleted save's first; another tool may take a later one, as done here
        // by hand: block 2 becomes another save's only block.
        val card = cardWith("BASLUS-00001" to save(2, 1))
        val deleted = api.deleteSave(card, card.save("BASLUS-00001").firstBlock, permanently = false)!!
        deleted[2 * 128] = 0x51

        assertNull(api.undeleteSave(deleted, deleted.save("BASLUS-00001").firstBlock))
    }

    @Test
    fun checkRenameSaysWhatsWrong() {
        val card = cardWith("BASLUS-00001" to save(1, 1), "BASLUS-00002" to save(1, 2))
        val first = card.save("BASLUS-00001").firstBlock

        assertEquals(1, api.checkRename(card, first, null, "").nameError)
        assertEquals(2, api.checkRename(card, first, null, "A".repeat(21)).nameError)
        assertEquals(3, api.checkRename(card, first, null, "A*B").nameError)
        assertEquals(4, api.checkRename(card, first, null, "BASLUS-00002").nameError)
        val nonStandard = api.checkRename(card, first, null, "MYSAVE")
        assertEquals(0, nonStandard.nameError)
        assertTrue(nonStandard.nameNotStandard)
        assertFalse(api.checkRename(card, first, null, "BASLUS-00003").nameNotStandard)

        val tooLong = api.checkRename(card, first, "Ａ".repeat(33), null)
        assertEquals(2, tooLong.titleError)
        assertFalse(tooLong.singleByteTitle)
        assertEquals(1, api.checkRename(card, first, "☃", null).titleError)
        assertEquals(0, api.checkRename(card, first, "Castlevania", null).titleError)
    }

    @Test
    fun renameRenames() {
        val card = cardWith("BASLUS-00001" to save(1, 1))

        val renamed = api.rename(card, card.save("BASLUS-00001").firstBlock, "Castlevania", "BASLUS-00067")!!

        val save = renamed.saves().single()
        assertEquals("BASLUS-00067", save.fileName)
        // Stored full-width, as most games write titles, which the core shows as plain letters.
        assertEquals("Castlevania", save.title)
        assertFalse(api.checkRename(renamed, save.firstBlock, null, null).singleByteTitle)
    }

    @Test
    fun exportThenImportRoundTrips() {
        val card = cardWith("BESLES-01234" to save(2, 5))
        val mcs = api.exportSave(card, card.save("BESLES-01234").firstBlock).bytes!!

        val other = api.importSave(api.format(), "x.mcs", mcs).bytes!!

        assertEquals("BESLES-01234", other.saves().single().fileName)
        assertArrayEquals(mcs, api.exportSave(other, other.save("BESLES-01234").firstBlock).bytes!!)
    }

    @Test
    fun importCardReplacesEverything() {
        val card = cardWith("BASLUS-00001" to save(1, 1))

        val imported = api.importCard("card.mcd", card).bytes!!
        assertEquals(listOf("BASLUS-00001"), imported.saves().map { it.fileName })

        val refused = api.importCard("card.txt", card)
        assertNull(refused.bytes)
        assertTrue(refused.error.isNotEmpty())
    }

    @Test
    fun readCardReadsTheFileOrNothing() {
        val file = File(core.dataRoot, "memcards/read.mcd")
        assertNull(api.readCard(file.absolutePath))

        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(10) { 3 })

        assertArrayEquals(ByteArray(10) { 3 }, api.readCard(file.absolutePath))
    }

    @Test
    fun writeCardCreatesThenSaysChanged() {
        val path = File(core.dataRoot, "memcards/write_1.mcd").absolutePath
        val card = cardWith("BASLUS-00001" to save(1, 1))

        assertEquals(MemcardWriteOutcome.Saved, api.writeCard(path, card, expected = null, force = false).outcome)
        assertArrayEquals(card, File(path).readBytes())

        val other = api.format()
        assertEquals(MemcardWriteOutcome.Changed, api.writeCard(path, other, expected = null, force = false).outcome)
        assertArrayEquals(card, File(path).readBytes())

        assertEquals(MemcardWriteOutcome.Saved, api.writeCard(path, other, expected = card, force = false).outcome)
        assertArrayEquals(other, File(path).readBytes())

        assertEquals(MemcardWriteOutcome.Saved, api.writeCard(path, card, expected = null, force = true).outcome)
        assertArrayEquals(card, File(path).readBytes())
    }

    @Test
    fun aCardIsUnknownToSyncWhileItIsOff() {
        assertFalse(api.isKnownToSync(File(core.dataRoot, "memcards/write_1.mcd").absolutePath))
    }

    private companion object {
        const val BLOCK = 8192
        const val TITLE_START = 4
        const val CARD = 128 * 1024
    }
}
