// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSettingsTest {
    @Test
    fun loadIsThePresetUntilSaved() {
        assertEquals(
            listOf("https://raw.githubusercontent.com/xlenore/psx-covers/main/covers/default/\${serial}.jpg"),
            CoverSources.load(MemoryCoreSettings()),
        )
        assertEquals(listOf(CoverSources.PRESET), CoverSources.load(MemoryCoreSettings()))
    }

    @Test
    fun anEmptiedListStaysEmpty() {
        val s = MemoryCoreSettings()

        CoverSources.save(s, emptyList())

        assertEquals(emptyList<String>(), CoverSources.load(s))
        assertEquals("0", s.values["Covers/SourceCount"])
    }

    @Test
    fun savedSourcesComeBackInOrder() {
        val s = MemoryCoreSettings()

        CoverSources.save(s, listOf("https://a/\${serial}.jpg", "https://b/\${title}.png"))

        assertEquals(listOf("https://a/\${serial}.jpg", "https://b/\${title}.png"), CoverSources.load(s))
        assertEquals("https://a/\${serial}.jpg", s.values["Covers/Source1"])
        assertEquals("https://b/\${title}.png", s.values["Covers/Source2"])
    }

    @Test
    fun saveDropsBlankLinesAndOldKeys() {
        val s = MemoryCoreSettings()
        CoverSources.save(s, listOf("a", "b", "c"))

        CoverSources.save(s, listOf("a \${serial}", " "))

        assertEquals(listOf("a \${serial}"), CoverSources.load(s))
        assertEquals("1", s.values["Covers/SourceCount"])
        assertFalse("Covers/Source2" in s.values)
        assertFalse("Covers/Source3" in s.values)
    }

    @Test
    fun useSerialIsOnByDefault() {
        val s = MemoryCoreSettings()
        assertTrue(CoverUseSerial.load(s))

        CoverUseSerial.save(s, false)

        assertFalse(CoverUseSerial.load(s))
        assertEquals("false", s.values["Covers/UseSerial"])
    }

    @Test
    fun aSourceWithoutAVariableHasANote() {
        assertEquals("Needs \${serial}, \${title} or \${filetitle}.", coverSourceProblem("https://a/cover.jpg"))
        assertNull(coverSourceProblem(""))
        assertNull(coverSourceProblem("  "))
        assertNull(coverSourceProblem(CoverSources.PRESET))
        assertNull(coverSourceProblem("https://a/\${filetitle}.jpg"))
        assertNull(coverSourceProblem("https://a/\${title}.jpg"))
    }
}
