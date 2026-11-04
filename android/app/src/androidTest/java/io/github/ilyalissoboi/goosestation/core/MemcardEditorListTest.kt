// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.app.MemcardGame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The memory card editor's card list, from the core: each library game's card at the path it boots with, then the
 * shared cards and the other card files. The games are made up (none is in the game database), and the cards are
 * written by the core.
 */
@RunWith(AndroidJUnit4::class)
class MemcardEditorListTest {
    private val core = SyncTestCore(InstrumentationRegistry.getInstrumentation().targetContext, "memcard-editor-list")
    private val memcards get() = File(core.dataRoot, "memcards")

    @Before
    fun setUp() = core.start()

    @After
    fun tearDown() = core.finish()

    private fun list(vararg games: MemcardGame): List<MemcardFile> =
        NativeLibrary
            .getMemcardFiles(
                games.map { it.title }.toTypedArray(),
                games.map { it.serial }.toTypedArray(),
                games.map { it.bootPath }.toTypedArray(),
                games.map { it.playlist }.toBooleanArray(),
            ).toList()

    private fun writeCard(file: File) {
        file.parentFile!!.mkdirs()
        assertTrue(NativeLibrary.writeMemoryCardSaveForTest(file.absolutePath, "BASLUS-00067", ByteArray(8192)))
    }

    private fun MemcardFile.at(file: File) = File(path).canonicalPath == file.canonicalPath

    private fun List<MemcardFile>.named(name: String) = firstOrNull { it.name == name }

    @Test
    fun aGameWithoutACardIsNoCardYet() {
        val cards = list(PADTEST)

        val card = cards.named("Pad Test")!!
        assertEquals(MemcardKind.Game, card.kind)
        assertTrue(card.path, card.at(File(memcards, "padtest_1.mcd")))
        assertFalse(card.exists)
    }

    @Test
    fun aWrittenCardExists() {
        writeCard(File(memcards, "padtest_1.mcd"))

        assertTrue(list(PADTEST).named("Pad Test")!!.exists)
    }

    @Test
    fun twoGamesOnOneCardAreOneEntry() {
        val again = PADTEST.copy(title = "Pad Test again", serial = "HASH-0000000000000002")

        val cards = list(PADTEST, again).filter { it.kind == MemcardKind.Game }

        assertEquals(listOf("Pad Test"), cards.map { it.name })
    }

    @Test
    fun aSharedTypeGameIsNotItsOwnCard() {
        File(core.dataRoot, "gamesettings").mkdirs()
        File(core.dataRoot, "gamesettings/${PADTEST.serial}.ini").writeText("[MemoryCards]\nCard1Type = Shared\n")

        val cards = list(PADTEST)

        assertNull(cards.firstOrNull { it.kind == MemcardKind.Game })
        val shared = cards.named("Shared card 1")!!
        assertEquals(MemcardKind.Shared, shared.kind)
        assertTrue(shared.at(File(memcards, "shared_card_1.mcd")))
    }

    @Test
    fun theSharedCardIsListedWhenItExists() {
        assertNull(list(PADTEST).named("Shared card 1"))

        writeCard(File(memcards, "shared_card_1.mcd"))

        val shared = list(PADTEST).named("Shared card 1")!!
        assertEquals(MemcardKind.Shared, shared.kind)
        assertTrue(shared.exists)
    }

    @Test
    fun aStrayCardIsListedByName() {
        writeCard(File(memcards, "Backup_1.mcd"))

        val card = list(PADTEST).named("Backup")!!
        assertEquals(MemcardKind.Other, card.kind)
        assertTrue(card.exists)
    }

    @Test
    fun aCardPathFromAGameIniIsListed() {
        val mine = File(core.dataRoot, "elsewhere/mine.mcd")
        writeCard(mine)
        File(core.dataRoot, "gamesettings").mkdirs()
        File(core.dataRoot, "gamesettings/SLUS-99999.ini").writeText("[MemoryCards]\nCard1Path = ../elsewhere/mine.mcd\n")

        val card = list(PADTEST).named("mine")!!
        assertEquals(MemcardKind.Other, card.kind)
        assertTrue(card.at(mine))
    }

    @Test
    fun anUnreadableCardIsListedAndNotLoaded() {
        memcards.mkdirs()
        File(memcards, "junk.mcd").writeBytes(ByteArray(10))

        val card = list(PADTEST).named("junk")!!
        assertEquals(MemcardKind.Other, card.kind)
        assertTrue(card.exists)
    }

    @Test
    fun gameCardsComeFirstThenSharedThenOthersByName() {
        writeCard(File(memcards, "shared_card_1.mcd"))
        writeCard(File(memcards, "b.mcd"))
        writeCard(File(memcards, "A.mcd"))

        assertEquals(listOf("Pad Test", "Shared card 1", "A", "b"), list(PADTEST).map { it.name })
    }

    private companion object {
        val PADTEST = MemcardGame("Pad Test", "HASH-0000000000000001", "/sdcard/GooseTest/games/padtest.psexe", false)
    }
}
