// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A picked data folder looked at on a real provider: the probe that it can replace files, and the overlap check. */
@RunWith(AndroidJUnit4::class)
class DataFolderCheckTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    @After
    fun tearDown() {
        TestDocumentsProvider.cantRename = emptySet()
        TestTrees.deleteAll(context)
    }

    private fun names(tree: String) = TestTrees.directory(context, tree).list()!!.toSet()

    @Test
    fun aFolderThatCanRenameIsUsable() {
        val tree = TestTrees.create(context, mapOf("settings.ini" to ByteArray(3)))

        val inspection = DataFolderCheck.inspect(resolver, tree, games = emptyList())

        assertEquals(DataFolderInspection.Usable(DataFolderContents(hasSettings = true, memoryCards = 0)), inspection)
        assertEquals(setOf("settings.ini"), names(tree))
    }

    @Test
    fun aFolderThatCantRenameIsRefused() {
        val tree = TestTrees.create(context, emptyMap())
        TestDocumentsProvider.cantRename = setOf(TestTrees.directory(context, tree).name)

        val inspection = DataFolderCheck.inspect(resolver, tree, games = emptyList())

        assertEquals(
            DataFolderInspection.Refused("This folder's storage can't replace files safely. Pick another."),
            inspection,
        )
        assertEquals(emptySet<String>(), names(tree))
    }

    @Test
    fun aFolderInsideAGamesFolderIsRefusedUntouched() {
        val games = TestTrees.create(context, mapOf("data/settings.ini" to ByteArray(3)))
        val data = "$games%2Fdata"

        val inspection = DataFolderCheck.inspect(resolver, data, games = listOf(games))

        assertEquals(DataFolderInspection.Refused(DataFolderCheck.DATA_OVERLAPS_GAMES), inspection)
        assertEquals(
            setOf("settings.ini"),
            TestTrees
                .directory(context, games)
                .resolve("data")
                .list()!!
                .toSet(),
        )
    }

    @Test
    fun anUnreadableFolderIsRefused() {
        val tree = TestTrees.create(context, emptyMap())
        TestTrees.directory(context, tree).deleteRecursively()

        val inspection = DataFolderCheck.inspect(resolver, tree, games = emptyList())

        assertEquals(DataFolderInspection.Refused(DataFolderCheck.UNREADABLE), inspection)
    }
}
