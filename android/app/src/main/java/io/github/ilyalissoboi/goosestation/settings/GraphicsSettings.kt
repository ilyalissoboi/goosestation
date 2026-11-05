// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/** The Graphics page's settings, by settings.ini's names, each with the core's Android default, and its rows' choices. */
object GraphicsSettings {
    // Rendering.

    /** `Automatic`, `Vulkan`, `OpenGL` (OpenGL ES here) or `Software`, as the core names them. */
    val RENDERER = NameSetting("GPU", "Renderer", "Automatic", NameList.GPURenderer)

    /** 0 matches the screen, 1 is native. */
    val RESOLUTION_SCALE = IntSetting("GPU", "ResolutionScale", 1)
    val DOWNSAMPLE_MODE = NameSetting("GPU", "DownsampleMode", "Disabled", NameList.DownsampleMode)
    val DOWNSAMPLE_SCALE = IntSetting("GPU", "DownsampleScale", 1)
    val MULTISAMPLES = IntSetting("GPU", "Multisamples", 1)
    val PER_SAMPLE_SHADING = BoolSetting("GPU", "PerSampleShading", false)

    /** On by default on Android (`Settings::DEFAULT_THREADED_PRESENTATION`). Vulkan only. */
    val THREADED_PRESENTATION = BoolSetting("GPU", "ThreadedPresentation", true)

    /** The software renderer's own thread. */
    val THREADED_RENDERING = BoolSetting("GPU", "UseThread", true)

    // Enhancements.
    val TEXTURE_FILTER = NameSetting("GPU", "TextureFilter", "Nearest", NameList.TextureFilter)

    /** Empty when unset: the core then uses the texture filter ([spriteFilter]). */
    val SPRITE_TEXTURE_FILTER = NameSetting("GPU", "SpriteTextureFilter", "", NameList.TextureFilter)
    val TRUE_COLOR = BoolSetting("GPU", "TrueColor", true)
    val DEBANDING = BoolSetting("GPU", "Debanding", false)
    val SCALED_DITHERING = BoolSetting("GPU", "ScaledDithering", true)
    val ACCURATE_BLENDING = BoolSetting("GPU", "AccurateBlending", false)
    val WIDESCREEN = BoolSetting("GPU", "WidescreenHack", false)
    val ROUND_TEXTURE_COORDINATES = BoolSetting("GPU", "ForceRoundTextureCoordinates", false)
    val LINE_DETECTION = NameSetting("GPU", "LineDetectMode", "Disabled", NameList.LineDetectMode)
    val FORCE_NTSC_TIMINGS = BoolSetting("GPU", "ForceNTSCTimings", false)
    val DISABLE_INTERLACING = BoolSetting("GPU", "DisableInterlacing", true)
    val CHROMA_SMOOTHING = BoolSetting("GPU", "ChromaSmoothing24Bit", false)
    val SOFTWARE_READBACKS = BoolSetting("GPU", "UseSoftwareRendererForReadbacks", false)
    val OLD_MDEC = BoolSetting("Hacks", "UseOldMDECRoutines", false)

    // PGXP.
    val PGXP = BoolSetting("GPU", "PGXPEnable", false)
    val PGXP_TEXTURE_CORRECTION = BoolSetting("GPU", "PGXPTextureCorrection", true)
    val PGXP_COLOR_CORRECTION = BoolSetting("GPU", "PGXPColorCorrection", false)
    val PGXP_CULLING = BoolSetting("GPU", "PGXPCulling", true)
    val PGXP_PRESERVE_PROJECTION = BoolSetting("GPU", "PGXPPreserveProjFP", false)
    val PGXP_DEPTH_BUFFER = BoolSetting("GPU", "PGXPDepthBuffer", false)

    /** Its own key: Big Picture's row writes PGXPDepthBuffer instead. */
    val DEPTH_CLEAR_THRESHOLD = FloatSetting("GPU", "PGXPDepthClearThreshold", 300f)
    val PGXP_CPU = BoolSetting("GPU", "PGXPCPU", false)

    /** Off by default, as the core has it (Big Picture's row shows PGXP's state instead). */
    val PGXP_VERTEX_CACHE = BoolSetting("GPU", "PGXPVertexCache", false)
    val PGXP_DISABLE_2D = BoolSetting("GPU", "PGXPDisableOn2DPolygons", false)

    /** In pixels; below 0 is off. */
    val PGXP_TOLERANCE = FloatSetting("GPU", "PGXPTolerance", -1f)

    // Display.
    val ASPECT_RATIO = NameSetting("Display", "AspectRatio", "Auto (Game Native)", NameList.AspectRatio)

    /** The ratio's terms with `Custom`. */
    val CUSTOM_ASPECT_NUMERATOR = IntSetting("Display", "CustomAspectRatioNumerator", 4)
    val CUSTOM_ASPECT_DENOMINATOR = IntSetting("Display", "CustomAspectRatioDenominator", 3)
    val DEINTERLACING = NameSetting("Display", "DeinterlacingMode", "Adaptive", NameList.DeinterlacingMode)
    val CROP = NameSetting("Display", "CropMode", "Overscan", NameList.CropMode)
    val SCALING = NameSetting("Display", "Scaling", "BilinearSmooth", NameList.Scaling)
    val ALIGNMENT = NameSetting("Display", "Alignment", "Center", NameList.Alignment)
    val ROTATION = NameSetting("Display", "Rotation", "Normal", NameList.Rotation)
    val STRETCH_VERTICALLY = BoolSetting("Display", "StretchVertically", false)
    val FORCE_4_3_FOR_FMVS = BoolSetting("Display", "Force4_3For24Bit", false)

    // On-screen display: the core's messages (which reach the app's message layer) and statistics (drawn by the core).
    val SHOW_MESSAGES = BoolSetting("Display", "ShowOSDMessages", true)
    val SHOW_FPS = BoolSetting("Display", "ShowFPS", false)
    val SHOW_SPEED = BoolSetting("Display", "ShowSpeed", false)
    val SHOW_RESOLUTION = BoolSetting("Display", "ShowResolution", false)
    val SHOW_CPU = BoolSetting("Display", "ShowCPU", false)
    val SHOW_GPU = BoolSetting("Display", "ShowGPU", false)
    val SHOW_GPU_STATISTICS = BoolSetting("Display", "ShowGPUStatistics", false)
    val SHOW_LATENCY = BoolSetting("Display", "ShowLatencyStatistics", false)
    val SHOW_FRAME_TIMES = BoolSetting("Display", "ShowFrameTimes", false)
    val SHOW_INPUTS = BoolSetting("Display", "ShowInputs", false)

    /** In percent: the statistics' size. The app's message layer doesn't follow it. */
    val OSD_SCALE = FloatSetting("Display", "OSDScale", 100f)

    // Screenshots.
    val SCREENSHOT_MODE = NameSetting("Display", "ScreenshotMode", "ScreenResolution", NameList.ScreenshotMode)
    val SCREENSHOT_FORMAT = NameSetting("Display", "ScreenshotFormat", "PNG", NameList.ScreenshotFormat)
    val SCREENSHOT_QUALITY = IntSetting("Display", "ScreenshotQuality", 85)

    // Texture replacement.
    val VRAM_REPLACEMENTS = BoolSetting("TextureReplacements", "EnableVRAMWriteReplacements", false)
    val PRELOAD_TEXTURES = BoolSetting("TextureReplacements", "PreloadTextures", false)

    val all: List<Setting<*>> =
        listOf(
            RENDERER,
            RESOLUTION_SCALE,
            DOWNSAMPLE_MODE,
            DOWNSAMPLE_SCALE,
            MULTISAMPLES,
            PER_SAMPLE_SHADING,
            THREADED_PRESENTATION,
            THREADED_RENDERING,
            TEXTURE_FILTER,
            SPRITE_TEXTURE_FILTER,
            TRUE_COLOR,
            DEBANDING,
            SCALED_DITHERING,
            ACCURATE_BLENDING,
            WIDESCREEN,
            ROUND_TEXTURE_COORDINATES,
            LINE_DETECTION,
            FORCE_NTSC_TIMINGS,
            DISABLE_INTERLACING,
            CHROMA_SMOOTHING,
            SOFTWARE_READBACKS,
            OLD_MDEC,
            PGXP,
            PGXP_TEXTURE_CORRECTION,
            PGXP_COLOR_CORRECTION,
            PGXP_CULLING,
            PGXP_PRESERVE_PROJECTION,
            PGXP_DEPTH_BUFFER,
            DEPTH_CLEAR_THRESHOLD,
            PGXP_CPU,
            PGXP_VERTEX_CACHE,
            PGXP_DISABLE_2D,
            PGXP_TOLERANCE,
            ASPECT_RATIO,
            CUSTOM_ASPECT_NUMERATOR,
            CUSTOM_ASPECT_DENOMINATOR,
            DEINTERLACING,
            CROP,
            SCALING,
            ALIGNMENT,
            ROTATION,
            STRETCH_VERTICALLY,
            FORCE_4_3_FOR_FMVS,
            SHOW_MESSAGES,
            SHOW_FPS,
            SHOW_SPEED,
            SHOW_RESOLUTION,
            SHOW_CPU,
            SHOW_GPU,
            SHOW_GPU_STATISTICS,
            SHOW_LATENCY,
            SHOW_FRAME_TIMES,
            SHOW_INPUTS,
            OSD_SCALE,
            SCREENSHOT_MODE,
            SCREENSHOT_FORMAT,
            SCREENSHOT_QUALITY,
            VRAM_REPLACEMENTS,
            PRELOAD_TEXTURES,
        )

    val RESOLUTIONS =
        NumberScale.Range(0, 16, 1, presets = listOf(0, 1, 2, 3, 4, 5, 6, 8)) {
            when (it) {
                0 -> "Match the screen"
                1 -> "1x (native)"
                else -> "${it}x"
            }
        }

    val ASPECT_TERMS = NumberScale.Range(1, 64, 1, presets = listOf(1, 3, 4, 9, 16, 21)) { "$it" }

    val OSD_SCALES = NumberScale.Range(25, 500, 5, presets = listOf(50, 75, 100, 150, 200)) { "$it%" }

    val SCREENSHOT_QUALITIES = NumberScale.Range(1, 100, 1, presets = listOf(50, 75, 85, 90, 95, 100)) { "$it%" }

    val DOWNSAMPLE_SCALES = NumberScale.Range(1, 16, 1, presets = listOf(1, 2, 3, 4)) { "${it}x" }

    /** In units of 1. */
    val DEPTH_CLEAR_THRESHOLDS = NumberScale.Range(0, 4096, 10, presets = listOf(0, 100, 300, 1000, 4096)) { "$it" }

    /** In hundredths of a pixel: -100 (any value below 0) is off. */
    val TOLERANCES =
        NumberScale.Steps(listOf(-100) + (0..10000 step 25), presets = listOf(-100, 0, 50, 100, 200, 500, 1000)) {
            if (it <
                0
            ) {
                "Off"
            } else {
                "${java.math.BigDecimal(it).movePointLeft(2).stripTrailingZeros().toPlainString()} px"
            }
        }

    /** The sprite filter the core uses: its own when set, else the texture filter. */
    fun spriteFilter(values: SettingValues): String = values[SPRITE_TEXTURE_FILTER].ifEmpty { values[TEXTURE_FILTER] }

    /** The core's [names] for the renderers, with what each means on Android; a [current] one it doesn't name kept. */
    fun renderers(
        names: List<Choice<String>>,
        current: String,
    ): List<Choice<String>> = offered(names.map { it.withNote(RENDERER_NOTES[it.value]) }, current)

    /** The core's aspect ratio [names], with notes for the ones whose name says little; a [current] one kept. */
    fun aspectRatios(
        names: List<Choice<String>>,
        current: String,
    ): List<Choice<String>> = offered(names.map { it.withNote(ASPECT_NOTES[it.value]) }, current)

    /**
     * Multisampling as one row over `Multisamples` and `PerSampleShading`, as Qt offers it without an adapter: off,
     * then 2x, 4x and 8x multisampling, then the same supersampled. The device's maximum applies.
     */
    fun multisampling(current: Pair<Int, Boolean>): List<Choice<Pair<Int, Boolean>>> =
        offered(
            listOf(Choice(1 to false, "Off")) +
                MSAA.map { Choice(it to false, "${it}x MSAA") } +
                MSAA.map { Choice(it to true, "${it}x SSAA") },
            current,
        ) { "${it.first}x ${if (it.second) "SSAA" else "MSAA"}" }

    fun multisamplingOf(values: SettingValues) = values[MULTISAMPLES] to values[PER_SAMPLE_SHADING]

    fun withMultisampling(
        values: SettingValues,
        mode: Pair<Int, Boolean>,
    ) = values.with(MULTISAMPLES, mode.first).with(PER_SAMPLE_SHADING, mode.second)

    private val MSAA = listOf(2, 4, 8)

    val RENDERER_NOTES = mapOf("Automatic" to "Vulkan where it works well, else OpenGL ES", "OpenGL" to "OpenGL ES")

    val ASPECT_NOTES =
        mapOf(
            "Auto (Game Native)" to "The game's own (4:3 for most games)",
            "Stretch To Fill" to "Stretch to the screen",
            "PAR 1:1" to "Square pixels",
        )

    private fun Choice<String>.withNote(note: String?) = if (note == null) this else copy(note = note)
}
