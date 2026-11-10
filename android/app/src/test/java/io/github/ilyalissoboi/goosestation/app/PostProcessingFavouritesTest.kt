// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.settings.Chain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Post-processing page's favourites (saved display chains), read and edited through a fake core. */
class PostProcessingFavouritesTest {
    private val calls = mutableListOf<String>()
    private val api =
        FakeCoreApi(calls).apply {
            shaderCatalog =
                mapOf(
                    "crt-lottes" to ("GLSL" to emptyList()),
                    "simple-flip" to ("GLSL" to emptyList()),
                )
        }
    private val global = api.globalLayer

    private fun TestScope.page(
        key: String? = null,
        running: Boolean = false,
    ) = PostProcessingPage(backgroundScope, StandardTestDispatcher(testScheduler), api, global) {
        api.gameLayer(it)
    }.also {
        it.open(key, running)
        runCurrent()
        calls.clear()
    }

    // Favourites of each name, each a chain of one stage of shader.
    private fun favourites(
        vararg names: String,
        shader: String = "simple-flip",
    ) {
        names.forEach { api.favourites += it to listOf(listOf("ShaderName" to shader)) }
    }

    private fun globalStages(vararg shaders: String) {
        global.setBool("PostProcessing", "Enabled", true)
        shaders.forEach { api.addPostProcessingStage(null, "PostProcessing", it) }
    }

    private fun PostProcessingPage.names() = favourites.map { it.name }

    private fun PostProcessingPage.displayStages() = chains.getValue(Chain.Display).stages.map { it.shader }

    @Test
    fun favouritesReadWithTheChains() =
        runTest {
            favourites("CRT", "Sharp")

            val page = page()

            assertEquals(listOf("CRT", "Sharp"), page.names())
            assertEquals(listOf("simple-flip"), page.favourites[0].shaders.toList())
        }

    @Test
    fun applyOverStagesAsksFirst() =
        runTest {
            globalStages("crt-lottes", "crt-lottes")
            favourites("CRT", "Sharp")
            val page = page()
            page.openFavourite("Sharp")

            page.apply("Sharp")
            runCurrent()

            assertEquals(FavouriteQuestion.Apply("Sharp", 2), page.favouriteQuestion)
            assertTrue(calls.none { it.startsWith("applyPostProcessingFavourite") })

            page.confirmFavourite()
            runCurrent()

            assertTrue(calls.toString(), "applyPostProcessingFavourite null Sharp" in calls)
            assertEquals(listOf("simple-flip"), page.displayStages())
            assertNull(page.favouriteQuestion)
            assertNull(page.favourite)
            assertEquals(1, page.focusFavourite)
        }

    @Test
    fun applyOverAnEmptyChainDoesntAsk() =
        runTest {
            favourites("Sharp")
            val page = page()
            page.openFavourite("Sharp")

            page.apply("Sharp")
            runCurrent()

            assertNull(page.favouriteQuestion)
            assertTrue("applyPostProcessingFavourite null Sharp" in calls)
            assertEquals(listOf("simple-flip"), page.displayStages())
        }

    @Test
    fun cancellingAQuestionGoesBackToThePanel() =
        runTest {
            globalStages("crt-lottes")
            favourites("Sharp")
            val page = page()
            page.openFavourite("Sharp")
            page.apply("Sharp")

            page.cancelFavourite()

            assertNull(page.favouriteQuestion)
            assertEquals("Sharp", page.favourite)
            assertTrue(calls.none { it.startsWith("applyPostProcessingFavourite") })
        }

    @Test
    fun aFailedApplyShowsInThePanel() =
        runTest {
            favourites("Sharp")
            val page = page()
            page.openFavourite("Sharp")
            api.favouriteRefusal = "There's no favourite named Sharp."

            page.apply("Sharp")
            runCurrent()

            assertEquals("Sharp", page.favourite)
            assertEquals("There's no favourite named Sharp.", page.favouriteError)
        }

    @Test
    fun savingAddsItLastAndFocusesIt() =
        runTest {
            globalStages("crt-lottes")
            favourites("CRT")
            val page = page()

            page.startSaving()
            assertEquals(Naming(renaming = null, error = ""), page.naming)
            page.submitName("Lottes")
            runCurrent()

            assertTrue("savePostProcessingFavourite null Lottes false" in calls)
            assertEquals(listOf("CRT", "Lottes"), page.names())
            assertNull(page.naming)
            assertEquals(1, page.focusFavourite)
        }

    // From review: a read asked for meanwhile (a pack installed, a pull) dropped the save's end, and the form stayed.
    @Test
    fun aSaveOvertakenByAReadStillClosesTheForm() =
        runTest {
            globalStages("crt-lottes")
            favourites("CRT")
            val page = page()

            page.startSaving()
            page.submitName("Lottes")
            page.reread()
            runCurrent()

            assertTrue("savePostProcessingFavourite null Lottes false" in calls)
            assertNull(page.naming)
            assertEquals(listOf("CRT", "Lottes"), page.names())
        }

    @Test
    fun savingATakenNameAsksToReplace() =
        runTest {
            favourites("CRT")
            val page = page()
            page.startSaving()

            page.submitName("crt")
            runCurrent()

            assertEquals(FavouriteQuestion.Replace("CRT", "crt"), page.favouriteQuestion)
            assertTrue(calls.none { it.startsWith("savePostProcessingFavourite") })

            page.confirmFavourite()
            runCurrent()

            assertTrue("savePostProcessingFavourite null crt true" in calls)
            assertEquals(listOf("CRT"), page.names())
            assertNull(page.naming)
            assertNull(page.favouriteQuestion)
            assertEquals(0, page.focusFavourite)
        }

    @Test
    fun cancellingReplaceGoesBackToTheForm() =
        runTest {
            favourites("CRT")
            val page = page()
            page.startSaving()
            page.submitName("crt")

            page.cancelFavourite()

            assertNull(page.favouriteQuestion)
            assertEquals(Naming(renaming = null, error = ""), page.naming)
        }

    @Test
    fun aRefusedNameStaysInTheForm() =
        runTest {
            val page = page()
            page.startSaving()

            page.submitName("  ")
            runCurrent()

            assertEquals(Naming(renaming = null, error = "A favourite needs a name."), page.naming)
        }

    @Test
    fun renameMovesTheFocusWithIt() =
        runTest {
            favourites("A", "B")
            val page = page()
            page.openFavourite("B")

            page.startRenaming("B")
            assertEquals(Naming(renaming = "B", error = ""), page.naming)
            page.submitName("Sharp LCD")
            runCurrent()

            assertTrue("renamePostProcessingFavourite B Sharp LCD" in calls)
            assertEquals(listOf("A", "Sharp LCD"), page.names())
            assertNull(page.naming)
            assertEquals("Sharp LCD", page.favourite)

            page.closeFavourite()
            assertNull(page.favourite)
            assertEquals(1, page.focusFavourite)
        }

    // As Big Picture's: cancelling a rename goes back to the favourite's panel; cancelling a save, to its row.
    @Test
    fun cancellingARenameGoesBackToThePanel() =
        runTest {
            favourites("A", "B")
            val page = page()
            page.openFavourite("B")
            page.startRenaming("B")

            page.cancelNaming()

            assertNull(page.naming)
            assertEquals("B", page.favourite)
            assertTrue(calls.none { it.startsWith("renamePostProcessingFavourite") })
        }

    @Test
    fun cancellingASaveGoesBackToItsRow() =
        runTest {
            favourites("A")
            val page = page()
            page.startSaving()

            page.cancelNaming()

            assertNull(page.naming)
            assertNull(page.favourite)
            assertEquals(1, page.focusFavourite)
        }

    @Test
    fun aRefusedRenameStaysInTheForm() =
        runTest {
            favourites("A", "B")
            val page = page()
            page.openFavourite("B")
            page.startRenaming("B")

            page.submitName("a")
            runCurrent()

            assertEquals(Naming(renaming = "B", error = "A favourite named a already exists."), page.naming)
            assertEquals(listOf("A", "B"), page.names())
        }

    @Test
    fun deleteAsksThenFocusesTheNextRow() =
        runTest {
            favourites("A", "B", "C")
            val page = page()
            page.openFavourite("B")

            page.deleteFavourite("B")
            assertEquals(FavouriteQuestion.Delete("B"), page.favouriteQuestion)
            assertTrue(calls.none { it.startsWith("deletePostProcessingFavourite") })

            page.confirmFavourite()
            runCurrent()

            assertTrue("deletePostProcessingFavourite B" in calls)
            assertEquals(listOf("A", "C"), page.names())
            assertNull(page.favourite)
            assertNull(page.favouriteQuestion)
            assertEquals(1, page.focusFavourite)
        }

    @Test
    fun moveKeepsThePanelOnIt() =
        runTest {
            favourites("A", "B")
            val page = page()
            page.openFavourite("B")

            page.moveFavourite("B", down = false)
            assertEquals(listOf("B", "A"), page.names())
            runCurrent()

            assertTrue("movePostProcessingFavourite B false" in calls)
            assertEquals(listOf("B", "A"), page.names())
            assertEquals("B", page.favourite)
        }

    @Test
    fun aGamePageAppliesToTheGame() =
        runTest {
            api.runningKey = KEY
            favourites("Sharp")
            val page = page(KEY, running = true)
            page.openFavourite("Sharp")

            page.apply("Sharp")
            runCurrent()

            assertTrue(calls.toString(), "applyPostProcessingFavourite $KEY Sharp" in calls)
            assertTrue("applyGameSettings $KEY" in calls)
            assertTrue(page.chains.getValue(Chain.Display).own)
        }

    @Test
    fun aGamePageSavesWithItsKey() =
        runTest {
            val page = page(KEY)
            page.startSaving()

            page.submitName("Mine")
            runCurrent()

            assertTrue("savePostProcessingFavourite $KEY Mine false" in calls)
        }

    @Test
    fun reopeningForgetsThePanel() =
        runTest {
            favourites("A")
            val page = page()
            page.openFavourite("A")
            page.startRenaming("A")

            page.open(null, running = false)
            runCurrent()

            assertNull(page.favourite)
            assertNull(page.naming)
            assertNull(page.favouriteQuestion)
            assertNull(page.focusFavourite)
        }

    private companion object {
        const val KEY = "SLUS-00001"
    }
}
