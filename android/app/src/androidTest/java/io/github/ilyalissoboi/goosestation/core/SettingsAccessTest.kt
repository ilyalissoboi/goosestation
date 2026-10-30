// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The string and int settings calls, and the aspect ratio names: each test runs in its own process. */
@RunWith(AndroidJUnit4::class)
class SettingsAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "settings-access")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val settingsFile = File(dataRoot, "settings.ini")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun stringSettingRoundTrips() {
        initialize()

        NativeLibrary.setStringSetting("GPU", "Renderer", "OpenGL")

        assertEquals("OpenGL", NativeLibrary.getStringSetting("GPU", "Renderer", "Automatic"))
        assertEquals("OpenGL", readSettings().value("GPU", "Renderer"))
    }

    @Test
    fun nullDeletes() {
        initialize()
        NativeLibrary.setStringSetting("GPU", "Renderer", "OpenGL")

        NativeLibrary.setStringSetting("GPU", "Renderer", null)

        assertEquals(emptyList<String>(), readSettings().values("GPU", "Renderer"))
        assertEquals("Automatic", NativeLibrary.getStringSetting("GPU", "Renderer", "Automatic"))
    }

    @Test
    fun intSettingRoundTrips() {
        initialize()

        NativeLibrary.setIntSetting("GPU", "ResolutionScale", 4)

        assertEquals("4", readSettings().value("GPU", "ResolutionScale"))
        assertEquals(4, NativeLibrary.getIntSetting("GPU", "ResolutionScale", 1))
    }

    @Test
    fun aspectRatioNamesAreDesktops() {
        val names = NativeLibrary.getAspectRatioNames().toList()

        assertTrue(
            names.toString(),
            names.containsAll(listOf("Stretch To Fill", "Auto (Game Native)", "4:3", "PAR 1:1")),
        )
        assertFalse(names.toString(), "Auto (Match Window)" in names)
    }

    @Test
    fun defaultsBeforeInitialize() {
        assertEquals(7, NativeLibrary.getIntSetting("GPU", "ResolutionScale", 7))
        assertEquals("Automatic", NativeLibrary.getStringSetting("GPU", "Renderer", "Automatic"))
    }

    private fun initialize() {
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(dataRoot.absolutePath, resources.absolutePath, biosDir.absolutePath, INSTALL_ID),
        )
    }

    private fun readSettings(): Ini = Ini.parse(settingsFile.readText())

    private companion object {
        const val INSTALL_ID = "test-install"
    }
}
