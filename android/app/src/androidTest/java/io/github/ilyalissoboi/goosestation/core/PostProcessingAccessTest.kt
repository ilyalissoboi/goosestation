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
    private val packsRoot = File(root, "shaderpacks")

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
        assertEquals("dolphinfx", shaders["dolphinfx/edge-smoothing/XBR"])
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

    // A pack has thousands of presets: they're listed by name, never loaded, so one that doesn't parse is listed too.
    @Test
    fun aSlangPresetIsListedWithoutLoading() {
        File(dataRoot, "shaders/p").mkdirs()
        File(dataRoot, "shaders/p/junk.slangp").writeText("this isn't a preset\n")
        initialize()

        assertEquals("Slang", shaders()["p/junk.slangp"])
        assertNotNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "p/junk.slangp"))
    }

    @Test
    fun aSlangStagesOptionsAreItsParameters() {
        writeTestPreset()
        initialize()

        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "p/test.slangp"))

        val stage = NativeLibrary.getPostProcessingStages(null, DISPLAY)!!.single()
        assertEquals("Slang", stage.kind)
        val gain = stage.options.first()
        assertEquals("TEST_GAIN", gain.name)
        assertEquals("Test gain", gain.uiName)
        assertEquals(ShaderOptionInfo.FLOAT, gain.type)
        assertEquals(0f, gain.minValue[0], 0f)
        assertEquals(2f, gain.maxValue[0], 0f)
        assertEquals(0.1f, gain.stepValue[0], 1e-6f)
    }

    @Test
    fun aSlangParameterIsWrittenInTheCoresFormat() {
        writeTestPreset()
        initialize()
        NativeLibrary.addPostProcessingStage(null, DISPLAY, "p/test.slangp")

        NativeLibrary.setPostProcessingOption(null, DISPLAY, 0, "TEST_GAIN", floatArrayOf(1.5f))

        assertEquals("1.5", settings().value("$DISPLAY/Stage1", "TEST_GAIN"))
        assertEquals(1.5f, NativeLibrary.getPostProcessingStages(null, DISPLAY)!![0].options[0].value[0], 0f)
    }

    // A chain from another install names a preset this one lacks: the stage says so, in the core's words.
    @Test
    fun aMissingSlangStageSaysWhy() {
        initialize()
        NativeLibrary.setStringSetting("$DISPLAY/Stage1", "ShaderName", "shaders_slang/gone.slangp")
        NativeLibrary.setStringSetting(DISPLAY, "StageCount", "1")

        val stage = NativeLibrary.getPostProcessingStages(null, DISPLAY)!!.single()
        assertEquals("", stage.kind)
        assertEquals("Slang preset shaders_slang/gone.slangp isn't in the shaders folder.", stage.error)
    }

    // Packs live in the app's own storage, apart from the data folder: listed and loaded from there.
    @Test
    fun presetsInTheAppsPacksAreListedAndLoad() {
        writeTestPreset(File(packsRoot, "packs/p"))
        NativeLibrary.setShaderPacksFolder(packsRoot.absolutePath)
        initialize()

        assertEquals("Slang", shaders()["packs/p/test.slangp"])
        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "packs/p/test.slangp"))
        val stage = NativeLibrary.getPostProcessingStages(null, DISPLAY)!!.single()
        assertEquals("Slang", stage.kind)
        assertEquals("", stage.error)
    }

    @Test
    fun aGlslShaderInTheAppsPacksLoads() {
        val resources = Resources.extract(context)
        packsRoot.mkdirs()
        File(resources, "shaders/simple-flip.glsl").copyTo(File(packsRoot, "packonly.glsl"))
        NativeLibrary.setShaderPacksFolder(packsRoot.absolutePath)
        initialize()

        assertEquals("GLSL", shaders()["packonly"])
        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "packonly"))
        assertEquals("GLSL", NativeLibrary.getPostProcessingStages(null, DISPLAY)!![0].kind)
    }

    // A stage's name is looked up in the data folder's shaders/ first: its preset loads, the pack's broken one isn't read.
    @Test
    fun theDataFoldersPresetWinsOverThePacks() {
        writeTestPreset()
        File(packsRoot, "p").mkdirs()
        File(packsRoot, "p/test.slangp").writeText("this isn't a preset\n")
        NativeLibrary.setShaderPacksFolder(packsRoot.absolutePath)
        initialize()

        assertEquals(
            1,
            NativeLibrary
                .getPostProcessingShaders()
                .toList()
                .chunked(2)
                .count { it[0] == "p/test.slangp" },
        )
        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "p/test.slangp"))
    }

    // The PCSX2 fork's two-parameter preset, whose shader is valid GLSL.
    private fun writeTestPreset(dir: File = File(dataRoot, "shaders/p")) {
        dir.mkdirs()
        File(dir, "test.slangp").writeText("shaders = 1\nshader0 = params.slang\n")
        File(dir, "params.slang").writeText(
            """
            #version 450
            #pragma parameter TEST_GAIN "Test gain" 1.0 0.0 2.0 0.1
            #pragma parameter TEST_FLAG "Test flag" 0.0 0.0 1.0 1.0
            layout(push_constant) uniform Push { float TEST_GAIN; float TEST_FLAG; } params;
            layout(std140, set = 0, binding = 0) uniform UBO { mat4 MVP; } global;
            #pragma stage vertex
            layout(location = 0) in vec4 Position;
            layout(location = 1) in vec2 TexCoord;
            layout(location = 0) out vec2 vTexCoord;
            void main() { gl_Position = global.MVP * Position; vTexCoord = TexCoord; }
            #pragma stage fragment
            layout(location = 0) in vec2 vTexCoord;
            layout(location = 0) out vec4 FragColor;
            layout(set = 0, binding = 2) uniform sampler2D Source;
            void main() { FragColor = texture(Source, vTexCoord) * params.TEST_GAIN; }
            """.trimIndent() + "\n",
        )
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

    // Favourites: saved display chains, one file each in the data folder's postprocessing/, in settings.ini's order.
    @Test
    fun favouritesRoundTrip() {
        initialize()
        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes"))

        assertNull(NativeLibrary.savePostProcessingFavourite(null, "A", false))
        assertEquals("A favourite named a already exists.", NativeLibrary.savePostProcessingFavourite(null, "a", false))
        assertNull(NativeLibrary.savePostProcessingFavourite(null, "B", false))
        NativeLibrary.movePostProcessingFavourite("B", false)
        assertNull(NativeLibrary.renamePostProcessingFavourite("A", "C"))

        val favourites = NativeLibrary.getPostProcessingFavourites()!!
        assertEquals(listOf("B", "C"), favourites.map { it.name })
        assertEquals(listOf("crt-lottes"), favourites[1].shaders.toList())
        assertEquals(emptyList<String>(), favourites[1].missing.toList())
        assertEquals(listOf("B", "C"), settings().values("PostProcessingFavourites", "Order"))
        assertTrue(File(dataRoot, "postprocessing/C.ini").isFile)

        NativeLibrary.removePostProcessingStage(null, DISPLAY, 0)
        assertNull(NativeLibrary.applyPostProcessingFavourite(null, "c"))
        assertEquals("crt-lottes", settings().value("$DISPLAY/Stage1", "ShaderName"))
        assertEquals("true", settings().value(DISPLAY, "Enabled"))

        // A game with no file of its own gets the favourite as its own chain.
        assertNull(NativeLibrary.applyPostProcessingFavourite(KEY, "B"))
        assertEquals("true", gameFile().value(DISPLAY, "Enabled"))
        assertEquals("crt-lottes", gameFile().value("$DISPLAY/Stage1", "ShaderName"))

        assertNull(NativeLibrary.deletePostProcessingFavourite("B"))
        assertEquals(listOf("C"), NativeLibrary.getPostProcessingFavourites()!!.map { it.name })
        assertEquals("There's no favourite named B.", NativeLibrary.applyPostProcessingFavourite(null, "B"))
    }

    // Saving from a game's page takes the chain the game runs: settings.ini's until it has its own.
    @Test
    fun aGameSavesTheChainItRuns() {
        initialize()
        assertNull(NativeLibrary.addPostProcessingStage(null, DISPLAY, "crt-lottes"))

        assertNull(NativeLibrary.savePostProcessingFavourite(KEY, "Global", false))
        NativeLibrary.setGameBoolSetting(KEY, DISPLAY, "Enabled", true)
        assertNull(NativeLibrary.savePostProcessingFavourite(KEY, "Own", false))

        val favourites = NativeLibrary.getPostProcessingFavourites()!!.associateBy { it.name }
        assertEquals(listOf("crt-lottes"), favourites.getValue("Global").shaders.toList())
        assertEquals(emptyList<String>(), favourites.getValue("Own").shaders.toList())
    }

    @Test
    fun aMissingShaderIsNamed() {
        initialize()
        File(dataRoot, "postprocessing").mkdirs()
        File(dataRoot, "postprocessing/CRT.ini").writeText(
            "[PostProcessing]\nEnabled = true\nStageCount = 1\n\n[PostProcessing/Stage1]\nShaderName = gone\n",
        )

        val favourite = NativeLibrary.getPostProcessingFavourites()!!.single()
        assertEquals(listOf("gone"), favourite.missing.toList())
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
