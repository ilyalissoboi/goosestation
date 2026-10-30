// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.settings.GraphicsChoices
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.ui.components.ChoiceRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow

/**
 * The Graphics page: the renderer, internal resolution, aspect ratio (of the core's [aspectNames]) and whether the FPS
 * shows. Each change comes as the whole [settings] with that field changed ([onChange]). [vulkanFailed] says Vulkan
 * didn't start in this run. The renderer is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun GraphicsScreen(
    settings: GraphicsSettings,
    aspectNames: List<String>,
    vulkanFailed: Boolean,
    onChange: (GraphicsSettings) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    SettingsPage("Graphics", onBack = onBack) {
        ChoiceRow(
            "Renderer",
            GraphicsChoices.renderers(settings.renderer),
            settings.renderer,
            onSelect = { onChange(settings.copy(renderer = it)) },
            modifier = Modifier.focusRequester(first),
            note =
                if (vulkanFailed) {
                    "Vulkan didn't start on this device, so games use OpenGL ES until the app restarts."
                } else {
                    null
                },
        )
        ChoiceRow(
            "Internal resolution",
            GraphicsChoices.scales(settings.resolutionScale),
            settings.resolutionScale,
            onSelect = { onChange(settings.copy(resolutionScale = it)) },
            note =
                if (settings.resolutionScale != 1) {
                    "Higher is sharper and slower. Match the screen picks the highest that fits."
                } else {
                    null
                },
        )
        ChoiceRow(
            "Aspect ratio",
            GraphicsChoices.aspectRatios(aspectNames, settings.aspectRatio),
            settings.aspectRatio,
            onSelect = { onChange(settings.copy(aspectRatio = it)) },
        )
        SwitchRow("Show FPS", settings.showFps, onToggle = { onChange(settings.copy(showFps = it)) })
    }
}
