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
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.NumberRow
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
        Names(
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
            Number(
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
            Names(values, onChange, names, "Downsampling", GraphicsSettings.DOWNSAMPLE_MODE)
            if (values[GraphicsSettings.DOWNSAMPLE_MODE] == BOX) {
                Number(
                    values,
                    onChange,
                    "Downsampling scale",
                    GraphicsSettings.DOWNSAMPLE_SCALE,
                    GraphicsSettings.DOWNSAMPLE_SCALES,
                )
            }
            val multisampling = GraphicsSettings.multisamplingOf(values)
            CyclerRow(
                "Multisampling",
                GraphicsSettings.multisampling(multisampling),
                multisampling,
                onSelect = { onChange(GraphicsSettings.withMultisampling(values, it)) },
                note = "The device's maximum applies.",
            )
        }
        // As Automatic picks Vulkan on most devices.
        if (renderer == VULKAN || renderer == AUTOMATIC) {
            Switch(
                values,
                onChange,
                "Threaded presentation",
                GraphicsSettings.THREADED_PRESENTATION,
                "Presents on another thread when fast-forwarding or without VSync.",
            )
        }
        if (!hardware) Switch(values, onChange, "Threaded rendering", GraphicsSettings.THREADED_RENDERING)

        SettingsSection("Enhancements")
        if (hardware) {
            Names(values, onChange, names, "Texture filtering", GraphicsSettings.TEXTURE_FILTER)
            val sprites = GraphicsSettings.spriteFilter(values)
            CyclerRow(
                "Sprite texture filtering",
                offered(names[NameList.TextureFilter].orEmpty(), sprites),
                sprites,
                onSelect = { onChange(values.with(GraphicsSettings.SPRITE_TEXTURE_FILTER, it)) },
            )
            Switch(values, onChange, "True colour", GraphicsSettings.TRUE_COLOR, "No dithering, full colour.")
            if (trueColor) {
                Switch(values, onChange, "Debanding", GraphicsSettings.DEBANDING)
            } else {
                Switch(values, onChange, "Scaled dithering", GraphicsSettings.SCALED_DITHERING)
                Switch(values, onChange, "Accurate blending", GraphicsSettings.ACCURATE_BLENDING)
            }
        }
        Switch(
            values,
            onChange,
            "Widescreen rendering",
            GraphicsSettings.WIDESCREEN,
            "Wider view in 3D games, at the display's aspect ratio.",
        )
        if (hardware) {
            if (values[GraphicsSettings.RESOLUTION_SCALE] != 1) {
                Switch(
                    values,
                    onChange,
                    "Round upscaled texture coordinates",
                    GraphicsSettings.ROUND_TEXTURE_COORDINATES,
                )
            }
            Names(values, onChange, names, "Line detection", GraphicsSettings.LINE_DETECTION)
        }
        Switch(
            values,
            onChange,
            "Force NTSC timings",
            GraphicsSettings.FORCE_NTSC_TIMINGS,
            "PAL games run at 60 Hz. Some run too fast.",
        )
        Switch(values, onChange, "Disable interlacing", GraphicsSettings.DISABLE_INTERLACING)
        Switch(values, onChange, "FMV chroma smoothing", GraphicsSettings.CHROMA_SMOOTHING)
        if (hardware) {
            Switch(values, onChange, "Software renderer for readbacks", GraphicsSettings.SOFTWARE_READBACKS)
        }
        Switch(values, onChange, "Use old MDEC routines", GraphicsSettings.OLD_MDEC)

        if (hardware) {
            SettingsSection("PGXP")
            Switch(values, onChange, "Geometry correction", GraphicsSettings.PGXP, "Less wobbly polygons.")
        }
        if (pgxp) {
            Switch(values, onChange, "Perspective-correct textures", GraphicsSettings.PGXP_TEXTURE_CORRECTION)
            Switch(values, onChange, "Perspective-correct colours", GraphicsSettings.PGXP_COLOR_CORRECTION)
            Switch(values, onChange, "Culling correction", GraphicsSettings.PGXP_CULLING)
            Switch(values, onChange, "Preserve projection precision", GraphicsSettings.PGXP_PRESERVE_PROJECTION)
            if (values[GraphicsSettings.PGXP_TEXTURE_CORRECTION]) {
                Switch(values, onChange, "Depth buffer", GraphicsSettings.PGXP_DEPTH_BUFFER, "Low compatibility.")
                if (values[GraphicsSettings.PGXP_DEPTH_BUFFER]) {
                    Number(
                        values,
                        onChange,
                        "Depth clear threshold",
                        GraphicsSettings.DEPTH_CLEAR_THRESHOLD,
                        GraphicsSettings.DEPTH_CLEAR_THRESHOLDS,
                        units = 1,
                    )
                }
            }
            Switch(values, onChange, "CPU mode", GraphicsSettings.PGXP_CPU, "PGXP for every instruction. Slower.")
            Switch(values, onChange, "Vertex cache", GraphicsSettings.PGXP_VERTEX_CACHE)
            Switch(values, onChange, "Disable on 2D polygons", GraphicsSettings.PGXP_DISABLE_2D)
            Number(
                values,
                onChange,
                "Geometry tolerance",
                GraphicsSettings.PGXP_TOLERANCE,
                GraphicsSettings.TOLERANCES,
                units = 100,
            )
        }

        SettingsSection("Display")
        Names(
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
                NumberRow(
                    label,
                    values[term].coerceAtLeast(1),
                    GraphicsSettings.ASPECT_TERMS,
                    onChange = { onChange(values.with(term, it)) },
                )
            }
        }
        Names(values, onChange, names, "Deinterlacing", GraphicsSettings.DEINTERLACING)
        Names(values, onChange, names, "Crop", GraphicsSettings.CROP)
        Names(values, onChange, names, "Scaling", GraphicsSettings.SCALING)
        Names(values, onChange, names, "Screen position", GraphicsSettings.ALIGNMENT)
        Names(values, onChange, names, "Rotation", GraphicsSettings.ROTATION)
        Switch(values, onChange, "Stretch vertically", GraphicsSettings.STRETCH_VERTICALLY)
        Switch(values, onChange, "Force 4:3 for FMVs", GraphicsSettings.FORCE_4_3_FOR_FMVS)

        SettingsSection("On-screen display")
        Switch(
            values,
            onChange,
            "Show messages",
            GraphicsSettings.SHOW_MESSAGES,
            "The game's messages, such as a memory card save. The app's own still show.",
        )
        Switch(values, onChange, "Show FPS", GraphicsSettings.SHOW_FPS)
        Switch(values, onChange, "Show speed", GraphicsSettings.SHOW_SPEED)
        Switch(values, onChange, "Show resolution", GraphicsSettings.SHOW_RESOLUTION)
        Switch(values, onChange, "Show CPU usage", GraphicsSettings.SHOW_CPU)
        Switch(values, onChange, "Show GPU usage", GraphicsSettings.SHOW_GPU)
        Switch(values, onChange, "Show GPU statistics", GraphicsSettings.SHOW_GPU_STATISTICS)
        Switch(values, onChange, "Show latency", GraphicsSettings.SHOW_LATENCY)
        Switch(values, onChange, "Show frame times", GraphicsSettings.SHOW_FRAME_TIMES)
        Switch(values, onChange, "Show controller input", GraphicsSettings.SHOW_INPUTS)
        Number(
            values,
            onChange,
            "Statistics size",
            GraphicsSettings.OSD_SCALE,
            GraphicsSettings.OSD_SCALES,
            units = 1,
            note = "The size of what's drawn over the game.",
        )

        SettingsSection("Screenshots")
        Names(values, onChange, names, "Size", GraphicsSettings.SCREENSHOT_MODE)
        Names(values, onChange, names, "Format", GraphicsSettings.SCREENSHOT_FORMAT)
        Number(values, onChange, "Quality", GraphicsSettings.SCREENSHOT_QUALITY, GraphicsSettings.SCREENSHOT_QUALITIES)

        SettingsSection("Texture replacement")
        Switch(
            values,
            onChange,
            "VRAM write replacements",
            GraphicsSettings.VRAM_REPLACEMENTS,
            "From the data folder's textures folder.",
        )
        if (values[GraphicsSettings.VRAM_REPLACEMENTS]) {
            Switch(values, onChange, "Preload replacement textures", GraphicsSettings.PRELOAD_TEXTURES)
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
