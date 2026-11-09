// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterfaceSettingsTest {
    // Desktop's keys, so a data folder shared with desktop keeps one choice.
    @Test
    fun theUpdateKeysAreDesktops() {
        assertEquals(
            "AutoUpdater" to "CheckAtStartup",
            InterfaceSettings.CHECK_UPDATES_AT_START.let {
                it.section to
                    it.key
            },
        )
        assertEquals(true, InterfaceSettings.CHECK_UPDATES_AT_START.default)
        assertEquals("AutoUpdater" to "UpdateTag", InterfaceSettings.UPDATE_CHANNEL.let { it.section to it.key })
        // The debug build has no channel of its own: Preview.
        assertEquals("preview", InterfaceSettings.UPDATE_CHANNEL.default)
    }

    @Test
    fun thePageHasTheUpdateSettings() {
        assertTrue(InterfaceSettings.CHECK_UPDATES_AT_START in InterfaceSettings.all)
        assertTrue(InterfaceSettings.UPDATE_CHANNEL in InterfaceSettings.all)
    }
}
