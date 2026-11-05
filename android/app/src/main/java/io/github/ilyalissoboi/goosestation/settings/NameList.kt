// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.Choice

/** The core's lists of setting values, each by the [kind] the bridge names it (its enum in the core). */
enum class NameList(
    val kind: String,
) {
    ConsoleRegion("ConsoleRegion"),
    CpuExecutionMode("CPUExecutionMode"),
    GPURenderer("GPURenderer"),
    TextureFilter("GPUTextureFilter"),
    DownsampleMode("GPUDownsampleMode"),
    LineDetectMode("GPULineDetectMode"),
    AspectRatio("DisplayAspectRatio"),
    DeinterlacingMode("DisplayDeinterlacingMode"),
    CropMode("DisplayCropMode"),
    Scaling("DisplayScalingMode"),
    Alignment("DisplayAlignment"),
    Rotation("DisplayRotation"),
    ScreenshotMode("DisplayScreenshotMode"),
    ScreenshotFormat("DisplayScreenshotFormat"),
    StretchMode("AudioStretchMode"),
    MemoryCardType("MemoryCardType"),

    /** Every controller type but those that need a pointer (GunCon, Justifier, mouse). */
    ControllerType("ControllerType"),
    MultitapMode("MultitapMode"),
}

/** Each of the core's lists, its choices as the core names them. */
typealias CoreNames = Map<NameList, List<Choice<String>>>

/** Said after a value settings.ini has that a row doesn't offer. */
const val FROM_SETTINGS = "(from settings.ini)"

/**
 * [choices], and [current] after them, marked, if it isn't one: a value set by hand or on desktop shows as itself,
 * and opening the row doesn't change it.
 */
fun offered(
    choices: List<Choice<String>>,
    current: String,
): List<Choice<String>> = offered(choices, current) { it }

/** As [offered] for names, for any value, shown by [label] when it isn't among [choices]. */
fun <T> offered(
    choices: List<Choice<T>>,
    current: T,
    label: (T) -> String,
): List<Choice<T>> =
    if (choices.any { it.value == current }) choices else choices + Choice(current, "${label(current)} $FROM_SETTINGS")
