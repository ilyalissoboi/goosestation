// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
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

/** The Post-processing pages' chains, read and edited through a fake core. */
class PostProcessingPageTest {
    private val calls = mutableListOf<String>()
    private val api =
        FakeCoreApi(calls).apply {
            shaderCatalog =
                mapOf(
                    "crt-lottes" to ("GLSL" to listOf(warp)),
                    "simple-flip" to ("GLSL" to emptyList()),
                    "FilmGrain2" to ("ReShade" to emptyList()),
                )
        }
    private val global = api.globalLayer

    private val lastFolder = MemoryShaderFolder()

    private fun TestScope.page(
        key: String? = null,
        running: Boolean = false,
    ) = PostProcessingPage(backgroundScope, StandardTestDispatcher(testScheduler), api, global, lastFolder) {
        api.gameLayer(it)
    }.also {
        it.open(key, running)
        runCurrent()
        calls.clear()
    }

    private fun stages(chain: Chain = Chain.Display) =
        api.getPostProcessingStages(null, chain.section).map { it.shader }.also { calls.clear() }

    @Test
    fun openReadsBothChainsAndTheOfferedShaders() =
        runTest {
            global.setBool("PostProcessing", "Enabled", true)
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            api.addPostProcessingStage(null, "InternalPostProcessing", "simple-flip")

            val page = page()

            val display = page.chains.getValue(Chain.Display)
            assertTrue(display.enabled)
            assertEquals(listOf("crt-lottes"), display.stages.map { it.shader })
            assertEquals(ShaderKind.GLSL, display.stages[0].kind)
            assertEquals(
                listOf("simple-flip"),
                page.chains
                    .getValue(Chain.Internal)
                    .stages
                    .map { it.shader },
            )
            assertFalse(page.chains.getValue(Chain.Internal).enabled)
            assertEquals(
                listOf(
                    Shader("crt-lottes", ShaderKind.GLSL),
                    Shader("FilmGrain2", ShaderKind.ReShade),
                    Shader("simple-flip", ShaderKind.GLSL),
                ),
                page.shaders,
            )
        }

    @Test
    fun onOpenGLESNoReShadeIsOffered() =
        runTest {
            api.usesOpenGLES = true

            val page = page()

            assertTrue(page.gles)
            assertEquals(listOf("crt-lottes", "simple-flip"), page.shaders.map { it.name })
        }

    @Test
    fun aSlangPresetIsOfferedAndAddedWithItsKind() =
        runTest {
            api.shaderCatalog += "crt/crt-royale.slangp" to ("Slang" to listOf(warp))
            api.usesOpenGLES = true

            val page = page()
            assertTrue(Shader("crt/crt-royale.slangp", ShaderKind.Slang) in page.shaders)

            page.add(Chain.Display, Shader("crt/crt-royale.slangp", ShaderKind.Slang))
            runCurrent()
            assertEquals(
                ShaderKind.Slang,
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .single()
                    .kind,
            )
        }

    @Test
    fun theSwitchWritesEnabled() =
        runTest {
            val page = page()

            page.setEnabled(Chain.Internal, true)
            assertTrue(page.chains.getValue(Chain.Internal).enabled)
            runCurrent()

            assertEquals("true", global.values["InternalPostProcessing/Enabled"])
        }

    @Test
    fun addAppendsAndFocusesTheNewStage() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            val page = page()
            page.openAdd(Chain.Display)
            assertEquals(Chain.Display, page.adding)

            page.add(Chain.Display, Shader("simple-flip", ShaderKind.GLSL))
            runCurrent()

            assertNull(page.adding)
            assertEquals(listOf("crt-lottes", "simple-flip"), stages())
            assertEquals(
                listOf("crt-lottes", "simple-flip"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
            assertEquals(Chain.Display to 1, page.focus)
        }

    @Test
    fun closingAddFocusesItsRow() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            val page = page()
            page.openAdd(Chain.Display)

            page.closeAdd()

            assertNull(page.adding)
            // One past the last stage: the chain's "Add a shader".
            assertEquals(Chain.Display to 1, page.focus)
        }

    @Test
    fun moveFollowsTheStage() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            api.addPostProcessingStage(null, "PostProcessing", "simple-flip")
            val page = page()
            page.openStage(Chain.Display, 0)

            page.move(Chain.Display, 0, down = true)
            runCurrent()

            assertEquals(Chain.Display to 1, page.stage)
            assertEquals(listOf("simple-flip", "crt-lottes"), stages())
            assertEquals(
                listOf("simple-flip", "crt-lottes"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )

            page.closeStage()
            assertNull(page.stage)
            assertEquals(Chain.Display to 1, page.focus)
        }

    @Test
    fun removeGoesBackFocusingTheNext() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            api.addPostProcessingStage(null, "PostProcessing", "simple-flip")
            val page = page()
            page.openStage(Chain.Display, 0)

            page.remove(Chain.Display, 0)
            runCurrent()

            assertNull(page.stage)
            assertEquals(listOf("simple-flip"), stages())
            assertEquals(Chain.Display to 0, page.focus)
        }

    // Before the core answers (a busy dispatcher): the chain already shows the edit, and the focus has its row.
    @Test
    fun addRemoveAndMoveShowBeforeTheCoreAnswers() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            val page = page()

            page.add(Chain.Display, Shader("simple-flip", ShaderKind.GLSL))
            assertEquals(
                listOf("crt-lottes", "simple-flip"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
            assertEquals(Chain.Display to 1, page.focus)
            runCurrent()

            page.openStage(Chain.Display, 0)
            page.move(Chain.Display, 0, down = true)
            assertEquals(
                listOf("simple-flip", "crt-lottes"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
            assertEquals(Chain.Display to 1, page.stage)
            runCurrent()

            page.remove(Chain.Display, 1)
            assertEquals(
                listOf("simple-flip"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
            assertEquals(Chain.Display to 1, page.focus)
            runCurrent()
            assertEquals(listOf("simple-flip"), stages())
        }

    @Test
    fun anOptionChangeShowsAtOnceAndIsSaved() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            val page = page()
            val option =
                page.chains
                    .getValue(Chain.Display)
                    .stages[0]
                    .options
                    .single()

            page.setOption(Chain.Display, 0, option, listOf(0.05f))

            assertEquals(
                0.05f,
                page.chains
                    .getValue(Chain.Display)
                    .stages[0]
                    .options
                    .single()
                    .value[0],
                0f,
            )
            runCurrent()
            assertEquals("0.05", global.values["PostProcessing/Stage1/warpX"])
        }

    @Test
    fun anEditInARunningGameApplies() =
        runTest {
            api.runningKey = KEY
            api.gameLayer(KEY).setBool("PostProcessing", "Enabled", true)
            val page = page(KEY, running = true)

            page.add(Chain.Display, Shader("simple-flip", ShaderKind.GLSL))
            runCurrent()

            assertEquals(1, calls.count { it == "applyGameSettings $KEY" })
        }

    @Test
    fun noApplyOutsideAGame() =
        runTest {
            val page = page()

            page.add(Chain.Display, Shader("simple-flip", ShaderKind.GLSL))
            runCurrent()

            assertTrue(calls.none { it.startsWith("applyGameSettings") })
        }

    @Test
    fun reloadAsksTheCore() =
        runTest {
            api.runningKey = KEY
            val page = page(KEY, running = true)

            page.reload()
            runCurrent()

            assertTrue("reloadPostProcessingShaders" in calls)
        }

    @Test
    fun aPullReadsTheChainsAgain() =
        runTest {
            api.addPostProcessingStage(null, "PostProcessing", "crt-lottes")
            val page = page()
            page.openStage(Chain.Display, 0)
            api.addPostProcessingStage(null, "PostProcessing", "simple-flip")

            page.reread()
            runCurrent()

            assertEquals(
                listOf("crt-lottes", "simple-flip"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
            assertEquals(Chain.Display to 0, page.stage)
        }

    @Test
    fun aReadOvertakenByAnEditIsDropped() =
        runTest {
            val page = page()
            page.reread()
            // The edit comes after the read was asked for: only what it reads shows.
            page.add(Chain.Display, Shader("simple-flip", ShaderKind.GLSL))
            runCurrent()

            assertEquals(
                listOf("simple-flip"),
                page.chains
                    .getValue(Chain.Display)
                    .stages
                    .map { it.shader },
            )
        }

    private companion object {
        const val KEY = "SLUS-00001"

        val warp =
            ShaderOptionInfo(
                "warpX",
                "Screen Warp X",
                "",
                "",
                ShaderOptionInfo.FLOAT,
                1,
                floatArrayOf(0.031f),
                floatArrayOf(0f),
                floatArrayOf(0.125f),
                floatArrayOf(0.01f),
                floatArrayOf(0.031f),
                emptyArray(),
            )
    }

    private val browsable =
        mapOf(
            "crt-lottes" to ("GLSL" to emptyList<ShaderOptionInfo>()),
            "shaders_slang/crt/crt-royale.slangp" to ("Slang" to emptyList()),
            "shaders_slang/crt/ntsc/ntsc.slangp" to ("Slang" to emptyList()),
            "shaders_slang/handheld/lcd.slangp" to ("Slang" to emptyList()),
        )

    @Test
    fun theBrowserOpensOnTheLastFolder() =
        runTest {
            api.shaderCatalog = browsable
            lastFolder.set("shaders_slang/crt")
            val page = page()

            page.openAdd(Chain.Display)
            assertEquals("shaders_slang/crt", page.browseFolder)
            assertEquals("", page.browseSearch)

            // A folder that's gone opens its nearest one that's there.
            page.closeAdd()
            lastFolder.set("shaders_slang/gone/deeper")
            page.openAdd(Chain.Display)
            assertEquals("shaders_slang", page.browseFolder)
        }

    @Test
    fun backClearsTheSearchThenGoesUpThenCloses() =
        runTest {
            api.shaderCatalog = browsable
            val page = page()
            page.openAdd(Chain.Display)
            assertEquals("", page.browseFolder)
            page.browseInto("shaders_slang")
            page.browseInto("shaders_slang/crt")
            page.search("royale")
            assertEquals("royale", page.browseSearch)

            page.browseBack()
            assertEquals("", page.browseSearch)
            assertEquals("shaders_slang/crt", page.browseFolder)
            page.browseBack()
            assertEquals("shaders_slang", page.browseFolder)
            page.browseBack()
            assertEquals("", page.browseFolder)
            assertEquals(Chain.Display, page.adding)
            page.browseBack()
            assertNull(page.adding)
        }

    @Test
    fun addingRemembersTheFolder() =
        runTest {
            api.shaderCatalog = browsable
            val page = page()
            page.openAdd(Chain.Internal)
            page.add(Chain.Internal, Shader("shaders_slang/handheld/lcd.slangp", ShaderKind.Slang))
            runCurrent()

            assertEquals("shaders_slang/handheld", lastFolder.get())
            page.openAdd(Chain.Internal)
            assertEquals("shaders_slang/handheld", page.browseFolder)
        }
}
