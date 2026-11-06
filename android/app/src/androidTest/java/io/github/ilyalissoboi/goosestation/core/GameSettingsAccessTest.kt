// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.storage.ContentBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A game's own settings file, by its key: reads, writes, Copy and Clear. Each test runs in its own process. */
@RunWith(AndroidJUnit4::class)
class GameSettingsAccessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "game-settings-access")
    private val dataRoot = File(root, "data")
    private val biosDir = File(root, "bios")
    private val gameSettings = File(dataRoot, "gamesettings")

    @Before
    fun setUp() {
        root.deleteRecursively()
        dataRoot.mkdirs()
        ContentBridge.init(context)
    }

    @Test
    fun aSetWritesTheKeysFile() {
        initialize()

        NativeLibrary.setGameIntSetting(KEY, "GPU", "ResolutionScale", 4)

        assertEquals("4", read(KEY).value("GPU", "ResolutionScale"))
        assertEquals(4, NativeLibrary.getGameIntSetting(KEY, "GPU", "ResolutionScale", 1))
        assertTrue(NativeLibrary.hasGameSetting(KEY, "GPU", "ResolutionScale"))
    }

    // As the core loads a disc set's settings: its first disc's file, for every disc.
    @Test
    fun aDiscSetsKeyIsItsFirstSerial() {
        initialize()

        NativeLibrary.setGameBoolSetting("SCUS-94164", "GPU", "PGXPEnable", true)

        assertEquals("true", read("SCUS-94163").value("GPU", "PGXPEnable"))
        assertFalse(File(gameSettings, "SCUS-94164.ini").exists())
    }

    @Test
    fun theLastDeleteRemovesTheFile() {
        initialize()
        NativeLibrary.setGameIntSetting(KEY, "GPU", "ResolutionScale", 4)

        NativeLibrary.deleteGameSetting(KEY, "GPU", "ResolutionScale")

        assertFalse(File(gameSettings, "$KEY.ini").exists())
        assertFalse(NativeLibrary.hasGameSetting(KEY, "GPU", "ResolutionScale"))
    }

    @Test
    fun aStringNullDeletes() {
        initialize()
        NativeLibrary.setGameStringSetting(KEY, "GPU", "Renderer", "OpenGL")
        NativeLibrary.setGameIntSetting(KEY, "GPU", "ResolutionScale", 4)

        NativeLibrary.setGameStringSetting(KEY, "GPU", "Renderer", null)

        assertEquals(emptyList<String>(), read(KEY).values("GPU", "Renderer"))
        assertEquals("4", read(KEY).value("GPU", "ResolutionScale"))
    }

    @Test
    fun readsFallBackToTheDefault() {
        initialize()

        assertEquals(1.0f, NativeLibrary.getGameFloatSetting(KEY, "Main", "EmulationSpeed", 1.0f))
        assertEquals("Auto", NativeLibrary.getGameStringSetting(KEY, "Console", "Region", "Auto"))
        assertFalse(NativeLibrary.getGameBoolSetting(KEY, "GPU", "PGXPEnable", false))
        assertFalse(File(gameSettings, "$KEY.ini").exists())
    }

    @Test
    fun aFloatRoundTrips() {
        initialize()

        NativeLibrary.setGameFloatSetting(KEY, "Main", "EmulationSpeed", 1.25f)

        assertEquals("1.25", read(KEY).value("Main", "EmulationSpeed"))
        assertEquals(1.25f, NativeLibrary.getGameFloatSetting(KEY, "Main", "EmulationSpeed", 1.0f))
    }

    // Desktop's Copy: Settings::Save(si, true) of settings.ini, which leaves out what desktop keeps global.
    @Test
    fun copyIsDesktopsSave() {
        initialize()
        NativeLibrary.setIntSetting("GPU", "ResolutionScale", 3)
        NativeLibrary.setBoolSetting("Display", "ShowFPS", true)
        NativeLibrary.setBoolSetting("Main", "StartPaused", true)

        NativeLibrary.copyGlobalSettingsToGame(KEY)

        val ini = read(KEY)
        assertEquals("3", ini.value("GPU", "ResolutionScale"))
        assertTrue(ini.values("Pad1", "Type").isNotEmpty())
        assertEquals(emptyList<String>(), ini.values("Display", "ShowFPS"))
        assertEquals(emptyList<String>(), ini.values("Main", "StartPaused"))
        assertEquals(emptyList<String>(), ini.values("ControllerPorts", "UseGameSettingsForController"))
    }

    @Test
    fun clearDeletesTheWholeFile() {
        initialize()
        gameSettings.mkdirs()
        File(gameSettings, "$KEY.ini").writeText("[Desktop]\nOnly = 1\n[GPU]\nResolutionScale = 4\n")

        NativeLibrary.clearGameSettings(KEY)

        assertFalse(File(gameSettings, "$KEY.ini").exists())
    }

    @Test
    fun keysListTheFile() {
        initialize()
        gameSettings.mkdirs()
        File(gameSettings, "$KEY.ini").writeText("[GPU]\nResolutionScale = 4\n[Pad1]\nCross = SDL-0/A\n")

        assertEquals(
            listOf("GPU", "ResolutionScale", "Pad1", "Cross"),
            NativeLibrary.getGameSettingKeys(KEY).toList(),
        )
    }

    @Test
    fun anInputProfileIsReportedOnlyWithoutTheFlag() {
        initialize()
        gameSettings.mkdirs()
        val file = File(gameSettings, "$KEY.ini")
        file.writeText("[ControllerPorts]\nInputProfileName = Desk\n")
        assertEquals("Desk", NativeLibrary.getGameInputProfile(KEY))

        file.writeText("[ControllerPorts]\nInputProfileName = Desk\nUseGameSettingsForController = true\n")
        assertNull(NativeLibrary.getGameInputProfile(KEY))
    }

    @Test
    fun applyWithoutTheGameRunningIsFalse() {
        initialize()
        NativeLibrary.setGameIntSetting(KEY, "GPU", "ResolutionScale", 4)

        assertFalse(NativeLibrary.applyGameSettings(KEY))
    }

    @Test
    fun aBlankKeyWritesNothing() {
        initialize()

        NativeLibrary.setGameIntSetting("", "GPU", "ResolutionScale", 4)

        assertFalse(gameSettings.exists() && gameSettings.list()!!.isNotEmpty())
    }

    private fun initialize() {
        val resources = Resources.extract(context)
        assertTrue(
            NativeLibrary.initialize(
                dataRoot.absolutePath,
                resources.absolutePath,
                biosDir.absolutePath,
                INSTALL_ID,
                null,
                File(dataRoot.parentFile, "offline").absolutePath,
                "Goose Test Device",
            ),
        )
    }

    private fun read(key: String): Ini = Ini.parse(File(gameSettings, "$key.ini").readText())

    private companion object {
        const val INSTALL_ID = "test-install"
        const val KEY = "SLUS-00001"
    }
}
