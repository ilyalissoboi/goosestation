// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import io.github.ilyalissoboi.goosestation.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesADescribedCommit() {
        assertEquals(AppVersion.Parsed(0, 1, 574, "ec4c2bf2e", false), AppVersion.parse("0.1-574-gec4c2bf2e"))
    }

    @Test
    fun parsesATagAlone() {
        assertEquals(AppVersion.Parsed(0, 1, 0, null, false), AppVersion.parse("0.1"))
    }

    @Test
    fun parsesDirty() {
        assertEquals(AppVersion.Parsed(0, 1, 3, "abc1234", true), AppVersion.parse("0.1-3-gabc1234-dirty"))
        assertEquals(100003, AppVersion.code("0.1-3-gabc1234-dirty"))
    }

    @Test
    fun codeFollowsTheFormula() {
        assertEquals(100574, AppVersion.code("0.1-574-gec4c2bf2e"))
        assertEquals(100200007, AppVersion.code("1.2-7-gaaaaaaa"))
        assertEquals(2099999999, AppVersion.code("20.999-99999-gaaaaaaa"))
    }

    @Test
    fun codeRefusesOutOfRange() {
        assertNull(AppVersion.code("21.0"))
        assertNull(AppVersion.code("0.1000"))
        assertNull(AppVersion.code("0.1-100000-gaaaaaaa"))
    }

    @Test
    fun unparseableIsNull() {
        for (name in listOf("0.0-unknown", "", "v0.1", "0.1-5", "0.1-5-gXYZ")) {
            assertNull(name, AppVersion.parse(name))
            assertNull(name, AppVersion.code(name))
        }
    }

    @Test
    fun shortDropsTheHash() {
        assertEquals("0.1-512", AppVersion.short("0.1-512-g1a2b3c4"))
        assertEquals("0.1-512", AppVersion.short("0.1-512-g1a2b3c4-dirty"))
        assertEquals("0.1", AppVersion.short("0.1"))
        assertEquals("0.0-unknown", AppVersion.short("0.0-unknown"))
    }

    // The unit tests run on the debug variant: its ID, its fixed code, no channel, and Gradle's code for its version.
    @Test
    fun buildConfigAgreesWithTheFormula() {
        assumeTrue(AppVersion.parse(BuildConfig.VERSION_NAME) != null)
        assertEquals(AppVersion.code(BuildConfig.VERSION_NAME), BuildConfig.GIT_VERSION_CODE)
        assertEquals(1, BuildConfig.VERSION_CODE)
        assertEquals("io.github.ilyalissoboi.goosestation.debug", BuildConfig.APPLICATION_ID)
        assertEquals("", BuildConfig.UPDATE_CHANNEL)
    }
}
