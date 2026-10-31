// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow

/**
 * The BIOS page: for each region, Automatic (the image the core picks, [auto], named) or one of the [images], as
 * [overrides] has it; then fast boot. A pick comes as the region and the image's file name, null for Automatic
 * ([onSetOverride]). The first region is focused on opening; B or Back go back ([onBack]).
 */
@Composable
fun BiosScreen(
    images: List<BiosImage>,
    auto: Map<BiosRegion, String?>,
    overrides: Map<BiosRegion, String>,
    fastBoot: Boolean,
    onSetOverride: (BiosRegion, String?) -> Unit,
    onFastBoot: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    SettingsPage("BIOS", onBack = onBack) {
        BiosRegion.entries.forEachIndexed { index, region ->
            CyclerRow(
                region.label,
                biosChoices(region, images, auto[region], overrides[region]),
                overrides[region],
                onSelect = { onSetOverride(region, it) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        SwitchRow("Fast boot (skip the BIOS intro)", fastBoot, onToggle = onFastBoot)
        Text(
            "The images come from your BIOS folder. Change it, or rescan it, in Folders.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * [region]'s choices: Automatic (null), naming the image the core picks ([auto]); each image, noting one made for
 * another region, and with its file name when another image has its description; and an [override] that isn't among
 * the images, marked as from settings.ini.
 */
private fun biosChoices(
    region: BiosRegion,
    images: List<BiosImage>,
    auto: String?,
    override: String?,
): List<Choice<String?>> {
    val automatic = Choice<String?>(null, "Automatic: ${auto ?: "none found"}")
    val shared =
        images
            .groupingBy { it.description }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    val offered =
        images.map { image ->
            val other = image.region != ANY_REGION && image.region != region.coreName
            val named = image.description in shared
            val label = if (named) "${image.description} (${image.fileName})" else image.description
            Choice<String?>(image.fileName, label, if (other) "made for ${image.region}" else null)
        }
    val unoffered =
        override
            ?.takeIf { file -> images.none { it.fileName == file } }
            ?.let { Choice<String?>(it, "$it (from settings.ini)") }
    return listOf(automatic) + offered + listOfNotNull(unoffered)
}

/** [BiosImage.region] for an image good for any region. */
private const val ANY_REGION = "Auto"
