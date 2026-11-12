// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.settings.offered
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection

/**
 * The Graphics page, in desktop's sections: rendering, enhancements, PGXP, the display and the on-screen display, from
 * [values], with the core's [names] for the choice rows. A row that does nothing with the renderer chosen, or with
 * the switch it depends on off, is left out. Each change comes as the whole [values] with that setting changed
 * ([onChange]). [vulkanFailed] says Vulkan didn't start in this run. The renderer is focused on opening; B or Back go
 * back ([onBack]).
 */
@Composable
fun GraphicsScreen(
    values: SettingValues,
    names: CoreNames,
    vulkanFailed: Boolean,
    onChange: (SettingValues) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    val renderer = values[GraphicsSettings.RENDERER]
    val hardware = renderer != SOFTWARE
    val trueColor = values[GraphicsSettings.TRUE_COLOR]
    val pgxp = hardware && values[GraphicsSettings.PGXP]

    SettingsPage("Graphics", onBack = onBack) {
        SettingsSection("Rendering")
        GameNames(
            values,
            onChange,
            names,
            "Renderer",
            GraphicsSettings.RENDERER,
            note = if (vulkanFailed) VULKAN_FAILED else null,
            modifier = Modifier.focusRequester(first),
            notes = GraphicsSettings.RENDERER_NOTES,
        )
        if (hardware) {
            GameNumber(
                values,
                onChange,
                "Internal resolution",
                GraphicsSettings.RESOLUTION_SCALE,
                GraphicsSettings.RESOLUTIONS,
                note =
                    if (values[GraphicsSettings.RESOLUTION_SCALE] != 1) {
                        "Higher is sharper and slower. Match the screen picks the highest that fits."
                    } else {
                        null
                    },
            )
            GameNames(values, onChange, names, "Downsampling", GraphicsSettings.DOWNSAMPLE_MODE)
            if (values[GraphicsSettings.DOWNSAMPLE_MODE] == BOX) {
                GameNumber(
                    values,
                    onChange,
                    "Downsampling scale",
                    GraphicsSettings.DOWNSAMPLE_SCALE,
                    GraphicsSettings.DOWNSAMPLE_SCALES,
                )
            }
            GameGroupChoices(
                values,
                onChange,
                "Multisampling",
                listOf(GraphicsSettings.MULTISAMPLES, GraphicsSettings.PER_SAMPLE_SHADING),
                GraphicsSettings.multisampling(GraphicsSettings.multisamplingOf(values)),
                value = GraphicsSettings::multisamplingOf,
                write = GraphicsSettings::withMultisampling,
                note = "The device's maximum applies.",
            )
        }
        // As Automatic picks Vulkan on most devices.
        if (renderer == VULKAN || renderer == AUTOMATIC) {
            GameSwitch(
                values,
                onChange,
                "Threaded presentation",
                GraphicsSettings.THREADED_PRESENTATION,
                "Presents on another thread when fast-forwarding or without VSync.",
            )
        }
        if (!hardware) GameSwitch(values, onChange, "Threaded rendering", GraphicsSettings.THREADED_RENDERING)

        SettingsSection("Enhancements")
        if (hardware) {
            GameNames(values, onChange, names, "Texture filtering", GraphicsSettings.TEXTURE_FILTER)
            GameGroupChoices(
                values,
                onChange,
                "Sprite texture filtering",
                listOf(GraphicsSettings.SPRITE_TEXTURE_FILTER),
                offered(names[NameList.TextureFilter].orEmpty(), GraphicsSettings.spriteFilter(values)),
                value = GraphicsSettings::spriteFilter,
                write = { v, filter -> v.with(GraphicsSettings.SPRITE_TEXTURE_FILTER, filter) },
            )
            GameSwitch(values, onChange, "True colour", GraphicsSettings.TRUE_COLOR, "No dithering, full colour.")
            if (trueColor) {
                GameSwitch(values, onChange, "Debanding", GraphicsSettings.DEBANDING)
            } else {
                GameSwitch(values, onChange, "Scaled dithering", GraphicsSettings.SCALED_DITHERING)
                GameSwitch(values, onChange, "Accurate blending", GraphicsSettings.ACCURATE_BLENDING)
            }
        }
        GameSwitch(
            values,
            onChange,
            "Widescreen rendering",
            GraphicsSettings.WIDESCREEN,
            "Wider view in 3D games, at the display's aspect ratio.",
        )
        if (hardware) {
            if (values[GraphicsSettings.RESOLUTION_SCALE] != 1) {
                GameSwitch(
                    values,
                    onChange,
                    "Round upscaled texture coordinates",
                    GraphicsSettings.ROUND_TEXTURE_COORDINATES,
                )
            }
            GameNames(values, onChange, names, "Line detection", GraphicsSettings.LINE_DETECTION)
        }
        GameSwitch(
            values,
            onChange,
            "Force NTSC timings",
            GraphicsSettings.FORCE_NTSC_TIMINGS,
            "PAL games run at 60 Hz. Some run too fast.",
        )
        GameSwitch(values, onChange, "Disable interlacing", GraphicsSettings.DISABLE_INTERLACING)
        GameSwitch(values, onChange, "FMV chroma smoothing", GraphicsSettings.CHROMA_SMOOTHING)
        if (hardware) {
            GameSwitch(values, onChange, "Software renderer for readbacks", GraphicsSettings.SOFTWARE_READBACKS)
        }
        GameSwitch(values, onChange, "Use old MDEC routines", GraphicsSettings.OLD_MDEC)

        if (hardware) {
            SettingsSection("PGXP")
            GameSwitch(values, onChange, "Geometry correction", GraphicsSettings.PGXP, "Less wobbly polygons.")
        }
        if (pgxp) {
            GameSwitch(values, onChange, "Perspective-correct textures", GraphicsSettings.PGXP_TEXTURE_CORRECTION)
            GameSwitch(values, onChange, "Perspective-correct colours", GraphicsSettings.PGXP_COLOR_CORRECTION)
            GameSwitch(values, onChange, "Culling correction", GraphicsSettings.PGXP_CULLING)
            GameSwitch(values, onChange, "Preserve projection precision", GraphicsSettings.PGXP_PRESERVE_PROJECTION)
            if (values[GraphicsSettings.PGXP_TEXTURE_CORRECTION]) {
                GameSwitch(values, onChange, "Depth buffer", GraphicsSettings.PGXP_DEPTH_BUFFER, "Low compatibility.")
                if (values[GraphicsSettings.PGXP_DEPTH_BUFFER]) {
                    GameNumber(
                        values,
                        onChange,
                        "Depth clear threshold",
                        GraphicsSettings.DEPTH_CLEAR_THRESHOLD,
                        GraphicsSettings.DEPTH_CLEAR_THRESHOLDS,
                        units = 1,
                    )
                }
            }
            GameSwitch(values, onChange, "CPU mode", GraphicsSettings.PGXP_CPU, "PGXP for every instruction. Slower.")
            GameSwitch(values, onChange, "Vertex cache", GraphicsSettings.PGXP_VERTEX_CACHE)
            GameSwitch(values, onChange, "Disable on 2D polygons", GraphicsSettings.PGXP_DISABLE_2D)
            GameNumber(
                values,
                onChange,
                "Geometry tolerance",
                GraphicsSettings.PGXP_TOLERANCE,
                GraphicsSettings.TOLERANCES,
                units = 100,
            )
        }

        SettingsSection("Display")
        GameNames(
            values,
            onChange,
            names,
            "Aspect ratio",
            GraphicsSettings.ASPECT_RATIO,
            notes = GraphicsSettings.ASPECT_NOTES,
        )
        if (values[GraphicsSettings.ASPECT_RATIO] == CUSTOM) {
            // As the core takes them: at least 1 (a settings.ini from desktop can hold 0).
            for (
            (label, term) in
            listOf(
                "Custom ratio width" to GraphicsSettings.CUSTOM_ASPECT_NUMERATOR,
                "Custom ratio height" to GraphicsSettings.CUSTOM_ASPECT_DENOMINATOR,
            )
            ) {
                GameGroupNumber(
                    values,
                    onChange,
                    label,
                    listOf(term),
                    GraphicsSettings.ASPECT_TERMS,
                    value = { it[term].coerceAtLeast(1) },
                    write = { v, n -> v.with(term, n) },
                )
            }
        }
        GameNames(values, onChange, names, "Deinterlacing", GraphicsSettings.DEINTERLACING)
        GameNames(values, onChange, names, "Crop", GraphicsSettings.CROP)
        GameNames(values, onChange, names, "Scaling", GraphicsSettings.SCALING)
        GameNames(values, onChange, names, "Screen position", GraphicsSettings.ALIGNMENT)
        GameNames(values, onChange, names, "Rotation", GraphicsSettings.ROTATION)
        GameSwitch(values, onChange, "Stretch vertically", GraphicsSettings.STRETCH_VERTICALLY)
        GameSwitch(values, onChange, "Force 4:3 for FMVs", GraphicsSettings.FORCE_4_3_FOR_FMVS)

        SettingsSection("On-screen display")
        GameSwitch(
            values,
            onChange,
            "Show messages",
            GraphicsSettings.SHOW_MESSAGES,
            "The game's messages, such as a memory card save. The app's own still show.",
        )
        GameSwitch(values, onChange, "Show FPS", GraphicsSettings.SHOW_FPS)
        GameSwitch(values, onChange, "Show speed", GraphicsSettings.SHOW_SPEED)
        GameSwitch(values, onChange, "Show resolution", GraphicsSettings.SHOW_RESOLUTION)
        GameSwitch(values, onChange, "Show CPU usage", GraphicsSettings.SHOW_CPU)
        GameSwitch(values, onChange, "Show GPU usage", GraphicsSettings.SHOW_GPU)
        GameSwitch(values, onChange, "Show GPU statistics", GraphicsSettings.SHOW_GPU_STATISTICS)
        GameSwitch(values, onChange, "Show latency", GraphicsSettings.SHOW_LATENCY)
        GameSwitch(values, onChange, "Show frame times", GraphicsSettings.SHOW_FRAME_TIMES)
        GameSwitch(values, onChange, "Show controller input", GraphicsSettings.SHOW_INPUTS)
        GameNumber(
            values,
            onChange,
            "Statistics size",
            GraphicsSettings.OSD_SCALE,
            GraphicsSettings.OSD_SCALES,
            units = 1,
            note = "The size of what's drawn over the game.",
        )

        SettingsSection("Screenshots")
        GameNames(values, onChange, names, "Size", GraphicsSettings.SCREENSHOT_MODE)
        GameNames(values, onChange, names, "Format", GraphicsSettings.SCREENSHOT_FORMAT)
        GameNumber(
            values,
            onChange,
            "Quality",
            GraphicsSettings.SCREENSHOT_QUALITY,
            GraphicsSettings.SCREENSHOT_QUALITIES,
        )

        SettingsSection("Texture replacement")
        GameSwitch(
            values,
            onChange,
            "VRAM write replacements",
            GraphicsSettings.VRAM_REPLACEMENTS,
            "From the data folder's textures folder.",
        )
        if (values[GraphicsSettings.VRAM_REPLACEMENTS]) {
            GameSwitch(values, onChange, "Preload replacement textures", GraphicsSettings.PRELOAD_TEXTURES)
        }
    }
}

private const val VULKAN_FAILED = "Vulkan didn't start on this device, so games use OpenGL ES until the app restarts."

// The core's names for the values the page's rows depend on.
private const val AUTOMATIC = "Automatic"
private const val VULKAN = "Vulkan"
private const val SOFTWARE = "Software"
private const val BOX = "Box"
private const val CUSTOM = "Custom"
