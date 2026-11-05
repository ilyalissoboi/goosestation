// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.NativeCoreSettings
import io.github.ilyalissoboi.goosestation.settings.Setting
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
        val names =
            NativeLibrary
                .getSettingNames("DisplayAspectRatio")
                .toList()
                .chunked(2)
                .map { it[0] }

        assertTrue(
            names.toString(),
            names.containsAll(listOf("Stretch To Fill", "Auto (Game Native)", "4:3", "PAR 1:1")),
        )
        assertFalse(names.toString(), "Auto (Match Window)" in names)
    }

    @Test
    fun floatSettingRoundTrips() {
        initialize()

        NativeLibrary.setFloatSetting("Main", "EmulationSpeed", 1.25f)
        NativeLibrary.setFloatSetting("Main", "RewindFrequency", 0.1f)

        assertEquals(1.25f, NativeLibrary.getFloatSetting("Main", "EmulationSpeed", 1f))
        assertEquals("1.25", readSettings().value("Main", "EmulationSpeed"))
        assertEquals("0.1", readSettings().value("Main", "RewindFrequency"))
    }

    @Test
    fun aFloatDesktopWroteReads() {
        settingsFile.writeText("[Main]\nSettingsVersion = 3\nEmulationSpeed = 10.000000\n")
        initialize()

        assertEquals(10f, NativeLibrary.getFloatSetting("Main", "EmulationSpeed", 1f))
    }

    @Test
    fun eachKindHasTheCoresNames() {
        initialize()

        for (kind in KINDS) {
            val names = NativeLibrary.getSettingNames(kind)
            assertTrue("$kind has no names", names.isNotEmpty())
            assertEquals("$kind isn't name and display name pairs", 0, names.size % 2)
        }
        assertEquals(
            listOf("Automatic", "Vulkan", "OpenGL", "Software"),
            NativeLibrary
                .getSettingNames("GPURenderer")
                .toList()
                .chunked(2)
                .map { it[0] },
        )
        assertTrue(
            "Custom" in
                NativeLibrary
                    .getSettingNames("DisplayAspectRatio")
                    .toList()
                    .chunked(2)
                    .map { it[0] },
        )
        assertEquals(0, NativeLibrary.getSettingNames("NoSuchKind").size)
    }

    @Test
    fun sharedCardNamesAreTheCardFolders() {
        initialize()
        val cards = File(dataRoot, "memcards").apply { mkdirs() }
        File(cards, "shared_card_1.mcd").writeBytes(ByteArray(16))
        File(cards, "b.mcd").writeBytes(ByteArray(16))
        File(cards, "notes.txt").writeText("not a card")

        assertEquals(listOf("b.mcd", "shared_card_1.mcd"), NativeLibrary.getSharedCardNames().toList())
    }

    @Test
    fun rewindUsageGrowsWithSlots() {
        initialize()

        val ten = NativeLibrary.getRewindMemoryUsage(10)
        val twenty = NativeLibrary.getRewindMemoryUsage(20)

        assertTrue(ten.toList().toString(), ten[0] > 0 && ten[1] > 0)
        assertEquals(ten[0] * 2, twenty[0])
        assertEquals(ten[1] * 2, twenty[1])
    }

    @Test
    fun graphicsDefaultsMatchTheCore() = assertDefaultsMatchTheCore(GraphicsSettings.all)

    @Test
    fun defaultsBeforeInitialize() {
        assertEquals(7, NativeLibrary.getIntSetting("GPU", "ResolutionScale", 7))
        assertEquals("Automatic", NativeLibrary.getStringSetting("GPU", "Renderer", "Automatic"))
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

    private fun readSettings(): Ini = Ini.parse(settingsFile.readText())

    // The core writes every default into a new settings.ini: each page's own defaults must be the same. The keys
    // adapting a new install's file deletes (foreign_settings.cpp) take the core's default when read instead.
    private fun assertDefaultsMatchTheCore(settings: List<Setting<*>>) {
        initialize()
        val file = readSettings()
        for (setting in settings) {
            val name = "${setting.section}/${setting.key}"
            if (name in ADAPTED_AWAY) continue
            assertTrue("$name isn't in a new settings.ini", file.value(setting.section, setting.key) != null)
            assertEquals(name, setting.default, setting.read(NativeCoreSettings))
        }
    }

    private companion object {
        const val INSTALL_ID = "test-install"

        /** The keys a new install's settings.ini loses as it's adapted: the device-specific ones. */
        val ADAPTED_AWAY = setOf("GPU/Renderer")

        /** Every choice list the bridge names. */
        val KINDS =
            listOf(
                "ConsoleRegion",
                "CPUExecutionMode",
                "GPURenderer",
                "GPUTextureFilter",
                "GPUDownsampleMode",
                "GPULineDetectMode",
                "DisplayAspectRatio",
                "DisplayDeinterlacingMode",
                "DisplayCropMode",
                "DisplayScalingMode",
                "DisplayAlignment",
                "DisplayRotation",
                "DisplayScreenshotMode",
                "DisplayScreenshotFormat",
                "AudioStretchMode",
                "MemoryCardType",
            )
    }
}
