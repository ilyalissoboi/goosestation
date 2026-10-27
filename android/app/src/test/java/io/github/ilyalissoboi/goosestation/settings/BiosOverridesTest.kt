// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosOverridesTest {
    @Test
    fun staleOverrideIsFound() {
        val overrides = mapOf(BiosRegion.NtscJ to "ps-30j.bin", BiosRegion.Pal to "gone.bin")

        assertEquals(listOf(BiosRegion.Pal), BiosOverrides.stale(overrides, listOf("ps-30j.bin")))
    }

    @Test
    fun emptyMeansAutomatic() {
        val s = MemoryCoreSettings()
        s.values["BIOS/PathNTSCU"] = ""
        s.values["BIOS/PathPAL"] = "scph7502.bin"

        val overrides = BiosOverrides.load(s)

        assertEquals(mapOf(BiosRegion.Pal to "scph7502.bin"), overrides)
    }

    @Test
    fun setNullDeletes() {
        val s = MemoryCoreSettings()
        BiosOverrides.set(s, BiosRegion.NtscJ, "ps-30j.bin")
        assertEquals("ps-30j.bin", s.values["BIOS/PathNTSCJ"])

        BiosOverrides.set(s, BiosRegion.NtscJ, null)

        assertFalse("BIOS/PathNTSCJ" in s.values)
        assertEquals(emptyMap<BiosRegion, String>(), BiosOverrides.load(s))
    }

    @Test
    fun regionsAreDesktops() {
        assertEquals(listOf("PathNTSCU", "PathNTSCJ", "PathPAL"), BiosRegion.entries.map { it.key })
        assertEquals(listOf("NTSC-U", "NTSC-J", "PAL"), BiosRegion.entries.map { it.coreName })
        assertEquals(
            listOf("NTSC-U (Americas)", "NTSC-J (Japan)", "PAL (Europe)"),
            BiosRegion.entries.map { it.label },
        )
    }

    @Test
    fun fastBootDefaultsOn() {
        val s = MemoryCoreSettings()
        assertTrue(BiosFastBoot.load(s))

        BiosFastBoot.save(s, false)

        assertEquals("false", s.values["BIOS/PatchFastBoot"])
        assertFalse(BiosFastBoot.load(s))
    }
}
