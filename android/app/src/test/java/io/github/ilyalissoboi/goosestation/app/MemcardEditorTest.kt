// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardKind
import io.github.ilyalissoboi.goosestation.storage.DocumentRead
import io.github.ilyalissoboi.goosestation.storage.PickedDocument
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory card editor's controller: the cards in two panes, edits held in memory per card, the questions it asks
 * first, and what it says. Cards are [FakeCard]s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemcardEditorTest {
    private val api = FakeMemcardEditorApi()
    private val documents = FakeDocuments()
    private var syncOn = false

    private fun TestScope.editor(vararg files: Pair<MemcardFile, FakeCard?>): MemcardEditor {
        api.cards = files.map { it.first }
        for ((file, card) in files) if (card != null) api.files[file.path] = card.bytes()
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MemcardEditor(backgroundScope, dispatcher, dispatcher, api, documents, { syncOn }).also {
            it.open(emptyList())
            runCurrent()
        }
    }

    private fun MemcardEditor.pane(index: Int) = ui!!.panes[index]!!

    private fun EditorCard.save(name: String) = contents!!.saves.first { it.fileName == name }

    private fun EditorCard.fake() = FakeCard.of(current)!!

    private fun MemcardEditor.confirm() {
        assertNotNull("a question", ui!!.question)
        answer(true)
    }

    @Test
    fun opensWithTheFirstTwoCardsThatExist() =
        runTest {
            val editor = editor(SPYRO to null, CRASH to CRASH_CARD, SHARED to FakeCard(), BACKUP to FakeCard())

            assertEquals(listOf(CRASH, SHARED).map { it.path }, editor.ui!!.panes.map { it!!.file.path })
            assertEquals(4, editor.ui!!.cards.size)
            assertEquals(0, editor.ui!!.changes)
        }

    @Test
    fun withNoCardsTheFirstListedShow() =
        runTest {
            val editor = editor(SPYRO to null, CRASH2 to null)

            assertEquals(CardState.NoCardYet, editor.pane(0).state)
            assertEquals(CRASH2.path, editor.pane(1).file.path)
        }

    @Test
    fun pickingTheOtherPanesCardSwaps() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.pick(0, SHARED.path)
            runCurrent()

            assertEquals(SHARED.path, editor.pane(0).file.path)
            assertEquals(CRASH.path, editor.pane(1).file.path)
        }

    @Test
    fun editsStayWithTheirCard() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard(), BACKUP to FakeCard())
            editor.delete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            editor.confirm()
            runCurrent()

            editor.pick(0, BACKUP.path)
            runCurrent()
            editor.pick(0, CRASH.path)
            runCurrent()

            assertTrue(editor.pane(0).save("BASCUS-94900").deleted)
            assertEquals(1, editor.ui!!.changes)
        }

    @Test
    fun copyAddsTheSave() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals(
                listOf("BASCUS-94900"),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.fileName },
            )
            assertEquals(1, editor.ui!!.changes)
            assertTrue(editor.pane(1).edited)
        }

    @Test
    fun copyAsksBeforeReplacing() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard(listOf(FakeSave("BASCUS-94900", data = 9))))

            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals("Replace the save of the same name on Shared card 1?", editor.ui!!.question!!.text)
            assertEquals("Replace", editor.ui!!.question!!.confirm)
            assertFalse(editor.pane(1).edited)
            editor.confirm()
            runCurrent()
            assertEquals(
                listOf(0),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.data },
            )
            assertTrue(api.calls.any { it.startsWith("copySave") && it.endsWith("replace=true") })
        }

    @Test
    fun copyRefusesWithoutTheBlocks() =
        runTest {
            val full = FakeCard(listOf(FakeSave("BIG", blocks = 14)))
            val editor = editor(CRASH to FakeCard(listOf(FakeSave("BASCUS-94900", blocks = 3))), SHARED to full)

            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals(
                EditorMessage("Shared card 1 needs 3 free blocks; it has 1.", error = true),
                editor.ui!!.message,
            )
            assertFalse(editor.pane(1).edited)
            assertFalse(api.calls.any { it.startsWith("copySave") })
        }

    @Test
    fun copyCountsTheReplacedSavesBlocks() =
        runTest {
            val there = FakeCard(listOf(FakeSave("BIG", blocks = 12), FakeSave("BASCUS-94900", blocks = 2)))
            val editor = editor(CRASH to FakeCard(listOf(FakeSave("BASCUS-94900", blocks = 3))), SHARED to there)

            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            editor.confirm()
            runCurrent()

            assertNull(editor.ui!!.message)
            assertEquals(3, editor.pane(1).save("BASCUS-94900").blocks)
        }

    @Test
    fun copyOntoNoCardYetFormatsIt() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SPYRO to null)
            assertEquals(CardState.NoCardYet, editor.pane(1).state)

            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals(CardState.Ready, editor.pane(1).state)
            assertEquals(
                listOf("BASCUS-94900"),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.fileName },
            )
        }

    @Test
    fun deleteAsksThenMarksDeleted() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.delete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            val question = editor.ui!!.question!!
            assertEquals("Delete Crash Bandicoot? You can undelete it while its blocks aren't reused.", question.text)
            assertEquals("Delete", question.confirm)
            editor.confirm()
            runCurrent()
            assertTrue(editor.pane(0).save("BASCUS-94900").deleted)
            assertTrue(api.calls.contains("deleteSave 1 permanently=false"))
        }

    @Test
    fun cancelChangesNothing() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.delete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            editor.answer(false)
            runCurrent()

            assertNull(editor.ui!!.question)
            assertFalse(editor.pane(0).edited)
            assertEquals(0, editor.ui!!.changes)
        }

    @Test
    fun deletePermanentlyAsks() =
        runTest {
            val deleted = FakeCard(listOf(FakeSave("BASCUS-94900", "Crash Bandicoot", deleted = true)))
            val editor = editor(CRASH to deleted, SHARED to FakeCard())

            editor.deletePermanently(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals("Erase Crash Bandicoot for good? It can't be undeleted.", editor.ui!!.question!!.text)
            assertEquals("Erase", editor.ui!!.question!!.confirm)
            editor.confirm()
            runCurrent()
            assertTrue(
                editor
                    .pane(0)
                    .fake()
                    .saves
                    .isEmpty(),
            )
        }

    @Test
    fun formatAsks() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.format(0)
            runCurrent()

            assertEquals("Erase every save on Crash Bandicoot?", editor.ui!!.question!!.text)
            assertEquals("Erase", editor.ui!!.question!!.confirm)
            editor.confirm()
            runCurrent()
            assertTrue(
                editor
                    .pane(0)
                    .fake()
                    .saves
                    .isEmpty(),
            )
        }

    @Test
    fun theSyncLineIsAddedOnlyForAKnownCardWithSyncOn() =
        runTest {
            val line = "\n\nMemory card sync is on: your other devices will see these saves removed."
            api.known += CRASH.path
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.format(0)
            runCurrent()
            assertFalse(
                editor.ui!!
                    .question!!
                    .text
                    .endsWith(line),
            )
            editor.answer(false)

            syncOn = true
            editor.format(1)
            runCurrent()
            assertFalse(
                editor.ui!!
                    .question!!
                    .text
                    .endsWith(line),
            )
            editor.answer(false)

            editor.format(0)
            runCurrent()
            assertEquals("Erase every save on Crash Bandicoot?$line", editor.ui!!.question!!.text)
            editor.answer(false)
            editor.delete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            assertTrue(
                editor.ui!!
                    .question!!
                    .text
                    .endsWith(line),
            )
        }

    @Test
    fun undeleteRefusesADuplicateName() =
        runTest {
            val card =
                FakeCard(
                    listOf(
                        FakeSave("BASCUS-94900", "Crash Bandicoot", deleted = true),
                        FakeSave("BASCUS-94900", "Crash Bandicoot", data = 2),
                    ),
                )
            val editor = editor(CRASH to card, SHARED to FakeCard())
            val deleted =
                editor
                    .pane(0)
                    .contents!!
                    .saves
                    .first { it.deleted }

            editor.undelete(0, deleted)
            runCurrent()

            assertEquals(
                EditorMessage(
                    "Can't undelete Crash Bandicoot: this card already has a save named BASCUS-94900.",
                    error = true,
                ),
                editor.ui!!.message,
            )
            assertFalse(api.calls.any { it.startsWith("undeleteSave") })
        }

    @Test
    fun undeleteRefusedWhenReused() =
        runTest {
            val card = FakeCard(listOf(FakeSave("BASCUS-94900", "Crash Bandicoot", deleted = true)))
            val editor = editor(CRASH to card, SHARED to FakeCard())
            api.undeleteRefused = true

            editor.undelete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals(
                EditorMessage("Can't undelete Crash Bandicoot: another save has used its blocks.", error = true),
                editor.ui!!.message,
            )
            assertFalse(editor.pane(0).edited)
        }

    @Test
    fun importSaveCancelledChangesNothing() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.picked = null

            editor.importSave(1)
            runCurrent()

            assertNull(editor.ui!!.message)
            assertFalse(editor.pane(1).edited)
            assertFalse(api.calls.any { it.startsWith("importSave") })
        }

    @Test
    fun importSaveAddsTheSave() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.picked = PickedDocument("content://save", "BASLUS-00067.mcs")
            documents.reads["content://save"] =
                DocumentRead.Bytes(FakeMemcardEditorApi.saveFile(FakeSave("BASLUS-00067")))

            editor.importSave(1)
            runCurrent()

            assertEquals(
                listOf("BASLUS-00067"),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.fileName },
            )
            assertTrue(api.calls.contains("importSave BASLUS-00067.mcs"))
            assertEquals(256 * 1024, documents.lastLimit)
        }

    @Test
    fun aTooBigFileIsRefused() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.picked = PickedDocument("content://big", "movie.mp4")
            documents.reads["content://big"] = DocumentRead.TooBig

            editor.importSave(1)
            runCurrent()

            assertEquals(
                EditorMessage("movie.mp4 is too big to be a memory card or a save.", error = true),
                editor.ui!!.message,
            )
            assertFalse(editor.pane(1).edited)
        }

    @Test
    fun anUnreadableDocumentSaysWhy() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.picked = PickedDocument("content://gone", "save.mcs")
            documents.reads["content://gone"] = DocumentRead.Failed("The file was moved.")

            editor.importSave(1)
            runCurrent()

            assertEquals(
                EditorMessage("Couldn't read save.mcs: The file was moved.", error = true),
                editor.ui!!.message,
            )
        }

    @Test
    fun importSaveShowsTheCoresReason() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.picked = PickedDocument("content://save", "save.mcs")
            documents.reads["content://save"] = DocumentRead.Bytes(ByteArray(3))
            api.importError = "Invalid size for save file."

            editor.importSave(1)
            runCurrent()

            assertEquals(
                EditorMessage("Couldn't import save.mcs: Invalid size for save file.", error = true),
                editor.ui!!.message,
            )
        }

    @Test
    fun importSaveOntoNoCardYetFormatsIt() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SPYRO to null)
            documents.picked = PickedDocument("content://save", "BASLUS-00067.mcs")
            documents.reads["content://save"] =
                DocumentRead.Bytes(FakeMemcardEditorApi.saveFile(FakeSave("BASLUS-00067")))

            editor.importSave(1)
            runCurrent()

            assertEquals(CardState.Ready, editor.pane(1).state)
            assertEquals(
                listOf("BASLUS-00067"),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.fileName },
            )
        }

    @Test
    fun importCardAsksThenReplaces() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            val incoming = FakeCard(listOf(FakeSave("BESLES-01234")))
            documents.picked = PickedDocument("content://card", "backup.mcd")
            documents.reads["content://card"] = DocumentRead.Bytes(incoming.bytes())

            editor.importCard(0)
            runCurrent()

            assertEquals("Replace everything on Crash Bandicoot with backup.mcd?", editor.ui!!.question!!.text)
            assertEquals("Replace", editor.ui!!.question!!.confirm)
            assertFalse(editor.pane(0).edited)
            editor.confirm()
            runCurrent()
            assertEquals(incoming, editor.pane(0).fake())
        }

    @Test
    fun anUnreadableCardOffersOnlyImportAndFormat() =
        runTest {
            api.files[JUNK.path] = FakeCard.JUNK
            val editor = editor(CRASH to CRASH_CARD, JUNK to null)
            api.files[JUNK.path] = FakeCard.JUNK

            assertEquals(CardState.Unreadable, editor.pane(1).state)
            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            documents.picked = PickedDocument("content://save", "BASLUS-00067.mcs")
            documents.reads["content://save"] =
                DocumentRead.Bytes(FakeMemcardEditorApi.saveFile(FakeSave("BASLUS-00067")))
            editor.importSave(1)
            runCurrent()

            assertFalse(editor.pane(1).edited)
            assertNull(editor.ui!!.question)
            editor.format(1)
            runCurrent()
            editor.confirm()
            runCurrent()
            assertEquals(CardState.Ready, editor.pane(1).state)
        }

    @Test
    fun exportWritesTheMcs() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.created = "content://out"

            editor.export(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals("BASCUS-94900.mcs", documents.suggested)
            assertArrayEquals(
                FakeMemcardEditorApi.saveFile(CRASH_CARD.saves.single()),
                documents.written["content://out"],
            )
            assertEquals(EditorMessage("Exported Crash Bandicoot.", error = false), editor.ui!!.message)
            assertEquals(0, editor.ui!!.changes)
        }

    @Test
    fun exportSaysWhenItCantWrite() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            documents.created = "content://out"
            documents.writeError = "No space left."

            editor.export(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals(
                EditorMessage("Couldn't export Crash Bandicoot: No space left.", error = true),
                editor.ui!!.message,
            )
        }

    @Test
    fun changesCountEditsAndDropToZeroWhenUndone() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            editor.delete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            editor.confirm()
            runCurrent()
            assertEquals(2, editor.ui!!.changes)

            editor.undelete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()
            assertEquals(3, editor.ui!!.changes)
            editor.delete(1, editor.pane(1).save("BASCUS-94900"))
            runCurrent()
            editor.confirm()
            runCurrent()
            editor.deletePermanently(1, editor.pane(1).save("BASCUS-94900"))
            runCurrent()
            editor.confirm()
            runCurrent()

            assertFalse(editor.pane(0).edited)
            assertFalse(editor.pane(1).edited)
            assertEquals(0, editor.ui!!.changes)
        }

    @Test
    fun actionsAreIgnoredWhileWorking() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            assertTrue(editor.ui!!.working)
            editor.copy(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            assertEquals(1, api.calls.count { it.startsWith("copySave") })
            assertFalse(editor.ui!!.working)
        }

    @Test
    fun eachActionClearsTheMessage() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            api.undeleteRefused = true
            editor.undelete(0, editor.pane(0).save("BASCUS-94900"))
            runCurrent()

            editor.format(1)
            runCurrent()

            assertNull(editor.ui!!.message)
        }

    private companion object {
        val CRASH = MemcardFile("/data/memcards/Crash Bandicoot_1.mcd", "Crash Bandicoot", MemcardKind.Game, true)
        val CRASH2 = MemcardFile("/data/memcards/Crash 2_1.mcd", "Crash 2", MemcardKind.Game, false)
        val SPYRO = MemcardFile("/data/memcards/Spyro_1.mcd", "Spyro", MemcardKind.Game, false)
        val SHARED = MemcardFile("/data/memcards/shared_card_1.mcd", "Shared card 1", MemcardKind.Shared, true)
        val BACKUP = MemcardFile("/data/memcards/Backup.mcd", "Backup", MemcardKind.Other, true)
        val JUNK = MemcardFile("/data/memcards/junk.mcd", "junk", MemcardKind.Other, true)
        val CRASH_CARD = FakeCard(listOf(FakeSave("BASCUS-94900", "Crash Bandicoot")))
    }
}
