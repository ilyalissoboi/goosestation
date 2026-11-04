// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.MemcardFile
import io.github.ilyalissoboi.goosestation.core.MemcardKind
import io.github.ilyalissoboi.goosestation.core.MemcardWriteOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory card editor's controller, past the edits: a save's panel, renaming, Save (and sync's hold, through
 * [FakeMemcardEditorApi.writeCard]), Discard, leaving, and cards changed under it. Cards are [FakeCard]s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemcardEditorSaveTest {
    private val api = FakeMemcardEditorApi()
    private val documents = FakeDocuments()
    private var syncOn = false
    private var saved = 0
    private var left = 0

    private fun TestScope.editor(vararg files: Pair<MemcardFile, FakeCard?>): MemcardEditor {
        api.cards = files.map { it.first }
        for ((file, card) in files) if (card != null) api.files[file.path] = card.bytes()
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MemcardEditor(backgroundScope, dispatcher, dispatcher, api, documents, { syncOn }, { saved++ }).also {
            it.open(emptyList())
            runCurrent()
        }
    }

    private fun MemcardEditor.pane(index: Int) = ui!!.panes[index]!!

    private fun EditorCard.save(name: String) = contents!!.saves.first { it.fileName == name }

    private fun EditorCard.fake() = FakeCard.of(current)!!

    private fun TestScope.step(action: () -> Unit) {
        action()
        runCurrent()
    }

    // Deletes Crash's save, so there's an edit: one change.
    private fun TestScope.edit(editor: MemcardEditor) {
        step { editor.delete(0, editor.pane(0).save("BASCUS-94900")) }
        step { editor.answer(true) }
        assertEquals(1, editor.ui!!.changes)
    }

    private fun TestScope.renaming(editor: MemcardEditor) {
        step { editor.openSave(0, editor.pane(0).save("BASCUS-94900")) }
        step { editor.startRename() }
        assertTrue(editor.ui!!.panel!!.renaming)
    }

    @Test
    fun aPanelOpensAndCloses() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            step { editor.openSave(0, editor.pane(0).save("BASCUS-94900")) }
            assertEquals(SavePanel(0, 1, renaming = false), editor.ui!!.panel)

            step { editor.closePanel() }
            assertNull(editor.ui!!.panel)
        }

    @Test
    fun aQuestionFromThePanelTakesItsPlaceAndCancelGoesBackToIt() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            step { editor.openSave(0, editor.pane(0).save("BASCUS-94900")) }

            step { editor.delete(0, editor.pane(0).save("BASCUS-94900")) }
            assertNotNull(editor.ui!!.question)
            assertNotNull(editor.ui!!.panel)

            step { editor.answer(false) }
            assertNull(editor.ui!!.question)
            assertNotNull(editor.ui!!.panel)

            step { editor.delete(0, editor.pane(0).save("BASCUS-94900")) }
            step { editor.answer(true) }
            assertNull(editor.ui!!.panel)
        }

    @Test
    fun anActionFromThePanelClosesIt() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            step { editor.openSave(0, editor.pane(0).save("BASCUS-94900")) }

            step { editor.copy(0, editor.pane(0).save("BASCUS-94900")) }

            assertNull(editor.ui!!.panel)
        }

    @Test
    fun renameUnchangedJustCloses() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            renaming(editor)

            step { editor.rename("Crash Bandicoot", "BASCUS-94900") }

            assertNull(editor.ui!!.panel)
            assertFalse(api.calls.any { it.startsWith("checkRename") })
        }

    private fun renameError(
        title: String,
        fileName: String,
        expected: String,
        singleByte: Boolean = false,
    ) = runTest {
        api.singleByteTitle = singleByte
        val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
        renaming(editor)

        step { editor.rename(title, fileName) }

        assertEquals(expected, editor.ui!!.renameError)
        val panel = editor.ui!!.panel!!
        assertTrue(panel.renaming)
        assertEquals(title, panel.title)
        assertEquals(fileName, panel.fileName)
        assertFalse(editor.pane(0).edited)
    }

    @Test
    fun anUnencodableTitleIsAnError() =
        renameError("Snow ☃", "BASCUS-94900", "The title has characters a memory card can't store.")

    @Test
    fun aLongTitleIsAnError() =
        renameError("A".repeat(33), "BASCUS-94900", "The title is too long: it can be up to 32 full-width characters.")

    @Test
    fun aLongSingleByteTitleIsAnError() =
        renameError(
            "A".repeat(33),
            "BASCUS-94900",
            "The title is too long: it can be up to 64 characters, or 32 if any aren't ASCII.",
            singleByte = true,
        )

    @Test
    fun anEmptyNameIsAnError() = renameError("Crash Bandicoot", "", "Enter a file name.")

    @Test
    fun aLongNameIsAnError() = renameError("Crash Bandicoot", "B".repeat(21), "File names can be up to 20 characters.")

    @Test
    fun aNameWithAStarIsAnError() =
        renameError(
            "Crash Bandicoot",
            "B*",
            "File names can use only ASCII letters, numbers, spaces and punctuation, but not * or ?.",
        )

    @Test
    fun aTakenNameIsAnError() =
        runTest {
            val two = FakeCard(listOf(FakeSave("BASCUS-94900", "Crash Bandicoot"), FakeSave("BASLUS-00067")))
            val editor = editor(CRASH to two, SHARED to FakeCard())
            renaming(editor)

            step { editor.rename("Crash Bandicoot", "BASLUS-00067") }

            assertEquals("This card already has a save named \"BASLUS-00067\".", editor.ui!!.renameError)
        }

    @Test
    fun renameWarningsAskFirst() =
        runTest {
            api.titleNotShownExactly = true
            syncOn = true
            api.known += CRASH.path
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            renaming(editor)

            step { editor.rename("Crash", "MYSAVE") }

            val question = editor.ui!!.question!!
            assertEquals(
                listOf(
                    "Some characters in the current title, such as ー or ・, can't be shown exactly here. " +
                        "The new title is saved as you see it, so those characters may look different in the game.",
                    "\"MYSAVE\" doesn't follow the usual pattern for save names: B, a region letter, then the game's " +
                        "product code, such as BASLUS-00067. The game may no longer find or load this save.",
                    "Memory card sync is on: your other devices will see the save \"BASCUS-94900\" deleted and a new " +
                        "save \"MYSAVE\" added. The history of \"BASCUS-94900\" stays available under its old name.",
                    "Rename anyway?",
                ).joinToString("\n\n"),
                question.text,
            )
            assertEquals("Rename", question.confirm)
            assertFalse(editor.pane(0).edited)

            step { editor.answer(false) }
            val panel = editor.ui!!.panel!!
            assertTrue(panel.renaming)
            assertEquals("MYSAVE", panel.fileName)

            step { editor.rename("Crash", "MYSAVE") }
            step { editor.answer(true) }
            assertEquals(
                "MYSAVE",
                editor
                    .pane(0)
                    .fake()
                    .saves
                    .single()
                    .fileName,
            )
            assertNull(editor.ui!!.panel)
        }

    @Test
    fun theSyncWarningNeedsSyncOnAndAKnownCard() =
        runTest {
            syncOn = true
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            renaming(editor)

            step { editor.rename("Crash Bandicoot", "BASCUS-94901") }

            assertNull(editor.ui!!.question)
            assertEquals(
                "BASCUS-94901",
                editor
                    .pane(0)
                    .fake()
                    .saves
                    .single()
                    .fileName,
            )
        }

    @Test
    fun renameApplies() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            renaming(editor)

            step { editor.rename("Crash!", "BASCUS-94900") }

            assertEquals(
                "Crash!",
                editor
                    .pane(0)
                    .fake()
                    .saves
                    .single()
                    .title,
            )
            assertTrue(api.calls.contains("checkRename Crash! null"))
            assertEquals(1, editor.ui!!.changes)
            assertNull(editor.ui!!.panel)
        }

    @Test
    fun saveWritesEditedCardsOnly() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)

            step { editor.save() }

            assertEquals(listOf("writeCard ${CRASH.path} force=false"), api.calls.filter { it.startsWith("writeCard") })
            assertTrue(
                FakeCard
                    .of(api.files[CRASH.path])!!
                    .saves
                    .single()
                    .deleted,
            )
        }

    @Test
    fun saveSaysSavedAndCallsOnSaved() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SPYRO to null)
            step { editor.copy(0, editor.pane(0).save("BASCUS-94900")) }

            step { editor.save() }

            assertEquals(EditorMessage("Saved", error = false), editor.ui!!.message)
            assertEquals(0, editor.ui!!.changes)
            assertFalse(editor.pane(1).edited)
            assertEquals(1, saved)
            assertTrue(
                editor.ui!!
                    .cards
                    .first { it.path == SPYRO.path }
                    .exists,
            )
        }

    @Test
    fun saveAsksWhenTheFileChanged() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            api.files[CRASH.path] = FakeCard(listOf(FakeSave("BASLUS-00001"))).bytes()

            step { editor.save() }

            assertEquals(
                "Crash Bandicoot changed on this device since you opened it. Save over it?",
                editor.ui!!.question!!.text,
            )
            assertEquals("Save over it", editor.ui!!.question!!.confirm)
            assertEquals(
                "BASLUS-00001",
                FakeCard
                    .of(api.files[CRASH.path])!!
                    .saves
                    .single()
                    .fileName,
            )
            assertEquals(0, saved)
        }

    @Test
    fun saveOverItForces() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            api.files[CRASH.path] = FakeCard(listOf(FakeSave("BASLUS-00001"))).bytes()
            step { editor.save() }

            step { editor.answer(true) }

            assertTrue(api.calls.contains("writeCard ${CRASH.path} force=true"))
            assertTrue(
                FakeCard
                    .of(api.files[CRASH.path])!!
                    .saves
                    .single()
                    .deleted,
            )
            assertEquals(EditorMessage("Saved", error = false), editor.ui!!.message)
        }

    @Test
    fun twoChangedCardsAreAskedAboutTogether() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            step { editor.copy(0, editor.pane(0).save("BASCUS-94900")) }
            step { editor.delete(0, editor.pane(0).save("BASCUS-94900")) }
            step { editor.answer(true) }
            api.files[CRASH.path] = FakeCard().bytes()
            api.files[SHARED.path] = FakeCard(listOf(FakeSave("X"))).bytes()

            step { editor.save() }

            assertEquals(
                "Crash Bandicoot and Shared card 1 changed on this device since you opened them. Save over them?",
                editor.ui!!.question!!.text,
            )
            assertEquals("Save over them", editor.ui!!.question!!.confirm)
        }

    @Test
    fun aBusyCardKeepsItsEdits() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            api.writeOutcomes[CRASH.path] = MemcardWriteOutcome.Busy

            step { editor.save() }

            assertEquals(
                EditorMessage(
                    "Couldn't save Crash Bandicoot: memory card sync is busy with it. Try again in a moment.",
                    error = true,
                ),
                editor.ui!!.message,
            )
            assertTrue(editor.pane(0).edited)
            assertEquals(1, editor.ui!!.changes)
            assertEquals(0, saved)
        }

    @Test
    fun aFailedWriteSaysWhy() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            api.writeOutcomes[CRASH.path] = MemcardWriteOutcome.Failed

            step { editor.save() }

            assertEquals(EditorMessage("Couldn't save Crash Bandicoot: Disk full.", error = true), editor.ui!!.message)
            assertTrue(editor.pane(0).edited)
        }

    @Test
    fun discardAsksThenReadsTheFilesAgain() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)

            step { editor.discard() }
            val question = editor.ui!!.question!!
            assertEquals("Discard your changes?", question.text)
            assertEquals("Discard", question.confirm)
            assertEquals("Keep editing", question.safe)
            api.files[SHARED.path] = FakeCard(listOf(FakeSave("NEW"))).bytes()
            step { editor.answer(true) }

            assertEquals(0, editor.ui!!.changes)
            assertFalse(editor.pane(0).save("BASCUS-94900").deleted)
            assertEquals(
                listOf("NEW"),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.fileName },
            )
        }

    @Test
    fun backClosesThePanelFirst() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            renaming(editor)

            step { editor.back { left++ } }
            assertNull(editor.ui!!.panel)
            assertEquals(0, left)

            step { editor.back { left++ } }
            assertEquals(1, left)
        }

    @Test
    fun backDropsTheQuestionFirst() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            step { editor.format(0) }

            step { editor.back { left++ } }

            assertNull(editor.ui!!.question)
            assertEquals(0, left)
        }

    @Test
    fun backWithoutChangesLeaves() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())

            step { editor.back { left++ } }

            assertEquals(1, left)
        }

    @Test
    fun backWithChangesAsks() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)

            step { editor.back { left++ } }

            val question = editor.ui!!.question!!
            assertEquals("Save your changes?", question.text)
            assertEquals("Discard", question.confirm)
            assertEquals(listOf("Save"), question.choices.map { it.label })
            assertEquals("Keep editing", question.safe)
            assertEquals(0, left)
            step { editor.answer(false) }
            assertEquals(0, left)
            assertTrue(editor.pane(0).edited)
        }

    @Test
    fun backSaveLeavesOnceSaved() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            step { editor.back { left++ } }

            step { editor.choose(0) }

            assertEquals(1, left)
            assertTrue(
                FakeCard
                    .of(api.files[CRASH.path])!!
                    .saves
                    .single()
                    .deleted,
            )
        }

    @Test
    fun backSaveStaysWhenAWriteFails() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            api.writeOutcomes[CRASH.path] = MemcardWriteOutcome.Failed
            step { editor.back { left++ } }

            step { editor.choose(0) }

            assertEquals(0, left)
            assertTrue(editor.ui!!.message!!.error)
        }

    @Test
    fun backDiscardLeaves() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            step { editor.back { left++ } }

            step { editor.answer(true) }

            assertEquals(1, left)
            assertFalse(
                FakeCard
                    .of(api.files[CRASH.path])!!
                    .saves
                    .single()
                    .deleted,
            )
            assertEquals(0, editor.ui!!.changes)
        }

    @Test
    fun aChangedFileWithoutEditsReloads() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            api.files[SHARED.path] = FakeCard(listOf(FakeSave("PULLED"))).bytes()

            step { editor.refresh() }

            assertEquals(
                listOf("PULLED"),
                editor
                    .pane(1)
                    .fake()
                    .saves
                    .map { it.fileName },
            )
            assertFalse(editor.pane(1).edited)
        }

    @Test
    fun aDeletedFileWithoutEditsIsNoCardYet() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            api.files.remove(SHARED.path)

            step { editor.refresh() }

            assertEquals(CardState.NoCardYet, editor.pane(1).state)
            assertFalse(
                editor.ui!!
                    .cards
                    .first { it.path == SHARED.path }
                    .exists,
            )
        }

    @Test
    fun aChangedFileWithEditsIsLeftForSave() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            api.files[CRASH.path] = FakeCard().bytes()

            step { editor.refresh() }

            assertTrue(editor.pane(0).save("BASCUS-94900").deleted)
            assertEquals(1, editor.ui!!.changes)
        }

    @Test
    fun refreshKeepsTheMessage() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard())
            edit(editor)
            step { editor.save() }

            step { editor.refresh() }

            assertEquals(EditorMessage("Saved", error = false), editor.ui!!.message)
        }

    @Test
    fun cardCountCountsTheFilesThatExist() =
        runTest {
            val editor = editor(CRASH to CRASH_CARD, SHARED to FakeCard(), SPYRO to null)

            step { editor.loadCount(emptyList()) }

            assertEquals(2, editor.cardCount)
        }

    private companion object {
        val CRASH = MemcardFile("/data/memcards/Crash Bandicoot_1.mcd", "Crash Bandicoot", MemcardKind.Game, true)
        val SPYRO = MemcardFile("/data/memcards/Spyro_1.mcd", "Spyro", MemcardKind.Game, false)
        val SHARED = MemcardFile("/data/memcards/shared_card_1.mcd", "Shared card 1", MemcardKind.Shared, true)
        val CRASH_CARD = FakeCard(listOf(FakeSave("BASCUS-94900", "Crash Bandicoot")))
    }
}
