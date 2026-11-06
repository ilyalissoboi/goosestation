// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.settings.Chain
import io.github.ilyalissoboi.goosestation.settings.Shader
import io.github.ilyalissoboi.goosestation.settings.ShaderKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A game's own chains: the global one until it takes its own, and Use global. */
class PostProcessingGameTest {
    private val calls = mutableListOf<String>()
    private val api =
        FakeCoreApi(calls).apply {
            shaderCatalog = mapOf("crt-lottes" to ("GLSL" to emptyList()), "simple-flip" to ("GLSL" to emptyList()))
        }
    private val global = api.globalLayer
    private val file get() = api.gameLayer(KEY)

    private fun TestScope.page() =
        PostProcessingPage(backgroundScope, StandardTestDispatcher(testScheduler), api, global) { api.gameLayer(it) }
            .also {
                it.open(KEY, running = false)
                runCurrent()
                calls.clear()
            }

    private fun TestScope.globalChain() {
        global.setBool("PostProcessing", "Enabled", true)
        api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
        calls.clear()
    }

    @Test
    fun aGameWithoutItsOwnChainShowsTheGlobalOne() =
        runTest {
            globalChain()

            val view = page().chains.getValue(Chain.Display)

            assertFalse(view.own)
            assertTrue(view.enabled)
            assertTrue(view.globalEnabled)
            assertEquals(listOf("crt-lottes"), view.stages.map { it.shader })
        }

    @Test
    fun onCopiesTheGlobalChain() =
        runTest {
            globalChain()
            val page = page()

            page.setEnabled(Chain.Display, false)
            runCurrent()

            assertTrue(calls.toString(), "copyPostProcessingChain $KEY PostProcessing" in calls)
            assertEquals("false", file.values["PostProcessing/Enabled"])
            assertEquals("crt-lottes", file.values["PostProcessing/Stage1/ShaderName"])
            val view = page.chains.getValue(Chain.Display)
            assertTrue(view.own)
            assertFalse(view.enabled)
        }

    @Test
    fun aGamesStagesWithoutEnabledAreKept() =
        runTest {
            globalChain()
            // As desktop's Qt can leave it: stages, but no Enabled, which the core ignores.
            api.addPostProcessingStage(KEY, "PostProcessing", "simple-flip")
            val page = page()
            assertFalse(page.chains.getValue(Chain.Display).own)

            page.setEnabled(Chain.Display, true)
            runCurrent()

            assertTrue(calls.none { it.startsWith("copyPostProcessingChain") })
            assertEquals(
                listOf("simple-flip"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
            assertTrue(page.chains.getValue(Chain.Display).own)
        }

    @Test
    fun useGlobalAsksWhenThereAreStages() =
        runTest {
            file.setBool("PostProcessing", "Enabled", true)
            api.addPostProcessingStage(KEY, "PostProcessing", "simple-flip")
            val page = page()

            page.setEnabled(Chain.Display, null)
            assertEquals(Chain.Display, page.asking)
            assertEquals(1, page.askingStages)
            page.cancelQuestion()
            runCurrent()
            assertNull(page.asking)
            assertTrue(calls.none { it.startsWith("clearPostProcessingChain") })

            page.setEnabled(Chain.Display, null)
            page.confirmUseGlobal()
            runCurrent()

            assertNull(page.asking)
            assertTrue("clearPostProcessingChain $KEY PostProcessing" in calls)
            assertFalse(page.chains.getValue(Chain.Display).own)
        }

    @Test
    fun useGlobalWithoutStagesDoesntAsk() =
        runTest {
            file.setBool("PostProcessing", "Enabled", true)
            val page = page()

            page.setEnabled(Chain.Display, null)
            runCurrent()

            assertNull(page.asking)
            assertTrue("clearPostProcessingChain $KEY PostProcessing" in calls)
        }

    @Test
    fun theGlobalChainIsReadOnlyForTheGame() =
        runTest {
            globalChain()
            val page = page()

            page.add(Chain.Display, Shader("simple-flip", ShaderKind.GLSL))
            page.remove(Chain.Display, 0)
            runCurrent()

            assertTrue(calls.toString(), calls.none { it.startsWith("add") || it.startsWith("remove") })
            assertEquals(listOf("crt-lottes"), api.getPostProcessingStages(null, "PostProcessing").map { it.shader })
        }

    @Test
    fun eachChainIsTakenOnItsOwn() =
        runTest {
            globalChain()
            val page = page()

            page.setEnabled(Chain.Internal, true)
            runCurrent()

            assertTrue(page.chains.getValue(Chain.Internal).own)
            assertFalse(page.chains.getValue(Chain.Display).own)
        }

    // A game whose own file picks OpenGL ES isn't offered ReShade, though settings.ini's renderer is Vulkan.
    @Test
    fun aGamesOwnRendererDecidesWhatsOffered() =
        runTest {
            api.shaderCatalog = api.shaderCatalog + ("FilmGrain2" to ("ReShade" to emptyList()))
            api.gamesOnOpenGLES = setOf(KEY)

            val page = page()

            assertTrue(page.gles)
            assertEquals(listOf("crt-lottes", "simple-flip"), page.shaders.map { it.name })
        }

    private companion object {
        const val KEY = "SLUS-00001"
    }
}
