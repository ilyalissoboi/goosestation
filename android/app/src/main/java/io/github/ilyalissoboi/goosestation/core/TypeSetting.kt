// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * One of a controller type's settings, as the core describes it (`Controller::ControllerInfo::settings`, a
 * `SettingInfo`): its [kind], its `[PadN]` [key], its [label] and [description], and its [default], [min], [max] and
 * [step] as the core writes them ("0.00", "1.00", "0.01"), each empty when the core gives none. A number is shown
 * through [format] (printf's, such as `%.0f%%`) after it's multiplied by [multiplier]. An [Kind.IntList]'s value is
 * [min] plus the index of one of its [options].
 */
data class TypeSetting(
    val kind: Kind,
    val key: String,
    val label: String,
    val description: String,
    val default: String,
    val min: String,
    val max: String,
    val step: String,
    val format: String,
    val multiplier: Float,
    val options: List<String>,
) {
    enum class Kind { Bool, Int, IntList, Float, String }

    companion object {
        /** The strings each setting takes in [NativeLibrary.getTypeSettings]' list. */
        const val FIELDS = 11

        /**
         * [NativeLibrary.getTypeSettings]' list: [FIELDS] strings per setting, in [TypeSetting]'s order, the kind by
         * name, the multiplier as a number and the options joined by newlines. A setting of a kind not known is left
         * out.
         */
        fun parse(flat: Array<String>): List<TypeSetting> =
            flat.toList().chunked(FIELDS).filter { it.size == FIELDS }.mapNotNull { f ->
                val kind = Kind.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
                TypeSetting(
                    kind = kind,
                    key = f[1],
                    label = f[2],
                    description = f[3],
                    default = f[4],
                    min = f[5],
                    max = f[6],
                    step = f[7],
                    format = f[8],
                    multiplier = f[9].toFloatOrNull() ?: 1f,
                    options = if (f[10].isEmpty()) emptyList() else f[10].split('\n'),
                )
            }
    }
}
