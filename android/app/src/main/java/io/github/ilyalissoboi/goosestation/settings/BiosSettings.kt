// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.core.BiosImage

private const val BIOS = "BIOS"

/** A console region with its own BIOS setting: `[BIOS] <key>` names its image, and [coreName] is the core's name. */
enum class BiosRegion(
    val label: String,
    val key: String,
) {
    NtscU("NTSC-U (Americas)", "PathNTSCU"),
    NtscJ("NTSC-J (Japan)", "PathNTSCJ"),
    Pal("PAL (Europe)", "PathPAL"),
    ;

    /** The core's name for the region, as `Settings::GetConsoleRegionName()` gives it. */
    val coreName: String
        get() =
            when (this) {
                NtscU -> "NTSC-U"
                NtscJ -> "NTSC-J"
                Pal -> "PAL"
            }
}

/**
 * The image each region uses, when it isn't the one the core picks itself (Automatic): `[BIOS] PathNTSCU`,
 * `PathNTSCJ` and `PathPAL`, each a file name in the BIOS folder, as desktop has them. Empty or missing is Automatic.
 */
object BiosOverrides {
    /** The regions with an image set, and its file name. */
    fun load(s: CoreSettings): Map<BiosRegion, String> =
        BiosRegion.entries.associateWith { s.getString(BIOS, it.key, "") }.filterValues { it.isNotEmpty() }

    /** Sets [region]'s image to [fileName], or back to Automatic (the key deleted) if it's null. */
    fun set(
        s: CoreSettings,
        region: BiosRegion,
        fileName: String?,
    ) = s.setString(BIOS, region.key, fileName)

    /** The regions whose image isn't among [copies], the file names in the BIOS folder. */
    fun stale(
        overrides: Map<BiosRegion, String>,
        copies: List<String>,
    ): List<BiosRegion> = overrides.filterValues { it !in copies }.keys.sortedBy { it.ordinal }
}

/** `[BIOS] PatchFastBoot`: the BIOS intro is skipped. On by default, as the core has it on Android. */
object BiosFastBoot {
    private const val KEY = "PatchFastBoot"

    fun load(s: CoreSettings): Boolean = s.getBool(BIOS, KEY, true)

    fun save(
        s: CoreSettings,
        on: Boolean,
    ) = s.setBool(BIOS, KEY, on)
}

/**
 * What the BIOS page shows: the [images] among the app's copies, the image the core picks for each region on its own
 * ([auto], its description, or null for none), the regions set to an image ([overrides]), and [fastBoot].
 */
data class BiosPage(
    val images: List<BiosImage>,
    val auto: Map<BiosRegion, String?>,
    val overrides: Map<BiosRegion, String>,
    val fastBoot: Boolean,
)
