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
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow

/**
 * The Graphics page: the renderer and aspect ratio (of the core's [names]), internal resolution and whether the FPS
 * shows, from [values]. Each change comes as the whole [values] with that setting changed ([onChange]).
 * [vulkanFailed] says Vulkan didn't start in this run. The renderer is focused on opening; B or Back go back
 * ([onBack]).
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
    val scale = values[GraphicsSettings.RESOLUTION_SCALE]
    val aspectRatio = values[GraphicsSettings.ASPECT_RATIO]

    SettingsPage("Graphics", onBack = onBack) {
        CyclerRow(
            "Renderer",
            GraphicsSettings.renderers(names[NameList.GPURenderer].orEmpty(), renderer),
            renderer,
            onSelect = { onChange(values.with(GraphicsSettings.RENDERER, it)) },
            modifier = Modifier.focusRequester(first),
            note =
                if (vulkanFailed) {
                    "Vulkan didn't start on this device, so games use OpenGL ES until the app restarts."
                } else {
                    null
                },
        )
        CyclerRow(
            "Internal resolution",
            GraphicsSettings.scales(scale),
            scale,
            onSelect = { onChange(values.with(GraphicsSettings.RESOLUTION_SCALE, it)) },
            note =
                if (scale !=
                    1
                ) {
                    "Higher is sharper and slower. Match the screen picks the highest that fits."
                } else {
                    null
                },
        )
        CyclerRow(
            "Aspect ratio",
            GraphicsSettings.aspectRatios(names[NameList.AspectRatio].orEmpty(), aspectRatio),
            aspectRatio,
            onSelect = { onChange(values.with(GraphicsSettings.ASPECT_RATIO, it)) },
        )
        SwitchRow(
            "Show FPS",
            values[GraphicsSettings.SHOW_FPS],
            onToggle = { onChange(values.with(GraphicsSettings.SHOW_FPS, it)) },
        )
    }
}
