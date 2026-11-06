// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The post-processing chains, edited on settings.ini or a game's file, and the shaders offered. */
@RunWith(AndroidJUnit4::class)
class PostProcessingAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "post-processing-access")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun theShippedShadersAreOfferedButTheBrokenOne() {
        initialize()

        val shaders = shaders()

        assertEquals("GLSL", shaders["crt-lottes"])
        assertEquals("GLSL", shaders["dolphinfx/edge-smoothing/XBR"])
        assertEquals("ReShade", shaders["FilmGrain2"])
        // Its includes aren't in the resources: its metadata doesn't load.
        assertFalse(shaders.toString(), "crt/crt-hyllian-sinc" in shaders)
    }

    @Test
    fun aShaderInTheShadersFolderIsOffered() {
        val resources = Resources.extract(context)
        File(dataRoot, "shaders").mkdirs()
        File(resources, "shaders/simple-flip.glsl").copyTo(File(dataRoot, "shaders/mine.glsl"))
        initialize()

        assertEquals("GLSL", shaders()["mine"])
    }

    // As TryLoadingShader() looks: the data folder's .glsl before the resources' .fx, so it's GLSL.
    @Test
    fun aUserGlslShadowsAShippedReShadeShader() {
        val resources = Resources.extract(context)
        File(dataRoot, "shaders").mkdirs()
        File(resources, "shaders/simple-flip.glsl").copyTo(File(dataRoot, "shaders/FilmGrain2.glsl"))
        initialize()

        assertEquals("GLSL", shaders()["FilmGrain2"])
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "FilmGrain2")
        assertEquals("GLSL", NativeLibrary.getPostProcessingStages(null, DISPLAY)!![0].kind)
    }

    @Test
    fun addWritesDesktopsKeys() {
        initialize()

        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes"))

        val ini = settings()
        assertEquals("1", ini.value(DISPLAY, "StageCount"))
        assertEquals("crt-lottes", ini.value("$DISPLAY/Stage1", "ShaderName"))
    }

    @Test
    fun addingAnUnknownShaderSaysWhy() {
        initialize()

        assertNotNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "nothing-here"))

        assertNull(settings().value(DISPLAY, "StageCount"))
    }

    @Test
    fun theStagesReadWithTheirOptions() {
        initialize()
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes")

        val stages = NativeLibrary.getPostProcessingStages(null, DISPLAY)!!

        assertEquals(1, stages.size)
        assertEquals("crt-lottes", stages[0].shader)
        assertEquals("GLSL", stages[0].kind)
        val warp = stages[0].options.single { it.name == "warpX" }
        assertEquals("Screen Warp X", warp.uiName)
        assertEquals(2, warp.type)
        assertEquals(1, warp.vectorSize)
        assertEquals(0.031f, warp.value[0], 0f)
        assertEquals(0.031f, warp.defaultValue[0], 0f)
        assertEquals(0.125f, warp.maxValue[0], 0f)
        assertEquals(0.01f, warp.stepValue[0], 0f)
    }

    @Test
    fun aFloatOptionIsWrittenInTheCoresFormat() {
        initialize()
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes")

        NativeLibrary.setPostProcessingOption(null, DISPLAY, 0, "warpX", floatArrayOf(0.0001f))

        assertEquals("0.0001", settings().value("$DISPLAY/Stage1", "warpX"))
        val warp = NativeLibrary.getPostProcessingStages(null, DISPLAY)!![0].options.single { it.name == "warpX" }
        assertEquals(0.0001f, warp.value[0], 0f)
    }

    @Test
    fun moveAndRemoveKeepEachStagesOptions() {
        initialize()
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes")
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "simple-brightness")
        NativeLibrary.setPostProcessingOption(null, DISPLAY, 0, "warpX", floatArrayOf(0.1f))
        NativeLibrary.setPostProcessingOption(null, DISPLAY, 1, "BRIGHTNESS_SCALE", floatArrayOf(2f))

        NativeLibrary.movePostProcessingStage(null, DISPLAY, 0, true)

        var ini = settings()
        assertEquals("simple-brightness", ini.value("$DISPLAY/Stage1", "ShaderName"))
        assertEquals("2", ini.value("$DISPLAY/Stage1", "BRIGHTNESS_SCALE"))
        assertEquals("crt-lottes", ini.value("$DISPLAY/Stage2", "ShaderName"))
        assertEquals("0.1", ini.value("$DISPLAY/Stage2", "warpX"))

        NativeLibrary.removePostProcessingStage(null, DISPLAY, 0)

        ini = settings()
        assertEquals("1", ini.value(DISPLAY, "StageCount"))
        assertEquals("crt-lottes", ini.value("$DISPLAY/Stage1", "ShaderName"))
        assertEquals("0.1", ini.value("$DISPLAY/Stage1", "warpX"))
        assertEquals(emptyList<String>(), ini.values("$DISPLAY/Stage2", "ShaderName"))
    }

    @Test
    fun aMissingShaderReadsAsMissing() {
        initialize()
        NativeLibrary.setIntSetting(DISPLAY, "StageCount", 1)
        NativeLibrary.setStringSetting("$DISPLAY/Stage1", "ShaderName", "nothing-here")

        val stages = NativeLibrary.getPostProcessingStages(null, DISPLAY)!!

        assertEquals(1, stages.size)
        assertEquals("nothing-here", stages[0].shader)
        assertEquals("", stages[0].kind)
        assertEquals(0, stages[0].options.size)
    }

    @Test
    fun theInternalChainIsItsOwn() {
        initialize()

        NativeLibrary.addPostProcessingStage(null, INTERNAL, "simple-gamma")

        assertEquals("simple-gamma", settings().value("$INTERNAL/Stage1", "ShaderName"))
        assertEquals(0, NativeLibrary.getPostProcessingStages(null, DISPLAY)!!.size)
    }

    @Test
    fun aGamesChainIsItsFile() {
        initialize()

        NativeLibrary.addPostProcessingStage(KEY, DISPLAY, "crt-lottes")
        NativeLibrary.setPostProcessingOption(KEY, DISPLAY, 0, "warpX", floatArrayOf(0.05f))

        val game = gameFile()
        assertEquals("crt-lottes", game.value("$DISPLAY/Stage1", "ShaderName"))
        assertEquals("0.05", game.value("$DISPLAY/Stage1", "warpX"))
        assertNull(settings().value(DISPLAY, "StageCount"))
        assertEquals("crt-lottes", NativeLibrary.getPostProcessingStages(KEY, DISPLAY)!![0].shader)
    }

    @Test
    fun copyAndClearTheGamesChain() {
        initialize()
        NativeLibrary.setBoolSetting(DISPLAY, "Enabled", true)
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes")
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "simple-flip")
        NativeLibrary.setPostProcessingOption(null, DISPLAY, 0, "warpX", floatArrayOf(0.05f))

        NativeLibrary.copyPostProcessingChain(KEY, DISPLAY)

        val game = gameFile()
        assertEquals("true", game.value(DISPLAY, "Enabled"))
        assertEquals("2", game.value(DISPLAY, "StageCount"))
        assertEquals("crt-lottes", game.value("$DISPLAY/Stage1", "ShaderName"))
        assertEquals("0.05", game.value("$DISPLAY/Stage1", "warpX"))
        assertEquals("simple-flip", game.value("$DISPLAY/Stage2", "ShaderName"))

        NativeLibrary.clearPostProcessingChain(KEY, DISPLAY)

        assertFalse(File(dataRoot, "gamesettings/$KEY.ini").exists())
        assertEquals("2", settings().value(DISPLAY, "StageCount"))
    }

    @Test
    fun clearKeepsTheGamesOtherSettings() {
        initialize()
        NativeLibrary.setGameIntSetting(KEY, "GPU", "ResolutionScale", 4)
        NativeLibrary.setGameBoolSetting(KEY, DISPLAY, "Enabled", true)
        NativeLibrary.addPostProcessingStage(KEY, DISPLAY, "simple-flip")

        NativeLibrary.clearPostProcessingChain(KEY, DISPLAY)

        val game = gameFile()
        assertEquals("4", game.value("GPU", "ResolutionScale"))
        assertEquals(emptyList<String>(), game.values(DISPLAY, "Enabled"))
        assertEquals(emptyList<String>(), game.values(DISPLAY, "StageCount"))
        assertEquals(emptyList<String>(), game.values("$DISPLAY/Stage1", "ShaderName"))
    }

    @Test
    fun withoutAGameOpenGLFollowsTheRenderer() {
        initialize()

        NativeLibrary.setStringSetting("GPU", "Renderer", "OpenGL")
        assertTrue(NativeLibrary.postProcessingUsesOpenGLES(null))
        NativeLibrary.setStringSetting("GPU", "Renderer", "Vulkan")
        assertFalse(NativeLibrary.postProcessingUsesOpenGLES(null))
    }

    // A game's own renderer is the one its chain runs on, over settings.ini's.
    @Test
    fun aGamesRendererIsItsOwn() {
        initialize()
        NativeLibrary.setStringSetting("GPU", "Renderer", "Vulkan")

        NativeLibrary.setGameStringSetting(KEY, "GPU", "Renderer", "OpenGL")
        assertTrue(NativeLibrary.postProcessingUsesOpenGLES(KEY))
        assertFalse(NativeLibrary.postProcessingUsesOpenGLES(null))
        assertFalse(NativeLibrary.postProcessingUsesOpenGLES("SLUS-00002"))

        NativeLibrary.setStringSetting("GPU", "Renderer", "OpenGL")
        NativeLibrary.setGameStringSetting(KEY, "GPU", "Renderer", "Vulkan")
        assertFalse(NativeLibrary.postProcessingUsesOpenGLES(KEY))
    }

    private fun shaders(): Map<String, String> =
        NativeLibrary
            .getPostProcessingShaders()
            .toList()
            .chunked(2)
            .associate { (name, kind) -> name to kind }

    private fun settings() = Ini.parse(File(dataRoot, "settings.ini").readText())

    private fun gameFile() = Ini.parse(File(dataRoot, "gamesettings/$KEY.ini").readText())

    private fun initialize() {
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(
                dataRoot.absolutePath,
                resources.absolutePath,
                biosDir.absolutePath,
                "test-install",
                null,
                File(root, "offline").absolutePath,
                "Goose Test Device",
            ),
        )
    }

    private companion object {
        const val DISPLAY = "PostProcessing"
        const val INTERNAL = "InternalPostProcessing"
        const val KEY = "SLUS-00001"
    }
}
