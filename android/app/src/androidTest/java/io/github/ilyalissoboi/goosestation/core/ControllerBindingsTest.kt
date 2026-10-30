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

/**
 * Each controller's `Android-<n>` index, kept in settings.ini by its descriptor, and its own bindings on Port 1. Each
 * test runs in its own process, so it gets its own initialize() and a descriptor table read from the file afresh.
 */
@RunWith(AndroidJUnit4::class)
class ControllerBindingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "controller-bindings")
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
    fun descriptorTableIsSaved() {
        initialize(null)

        NativeLibrary.onDeviceConnected(1, "Pad A", "pad-a")
        NativeLibrary.onDeviceConnected(2, "Pad B", "pad-b")

        waitFor("both descriptors are saved") { descriptors().size >= 2 }
        assertEquals(listOf("pad-a", "pad-b"), descriptors())
    }

    // Review Focus 1: a pad remapped in an earlier run keeps its remap, and no default comes back, when it connects
    // again after a restart, and again when it reconnects with a new device ID.
    @Test
    fun remapSurvivesRestartAndReconnect() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n" +
                "ControllerDescriptors = pad-b\nControllerDescriptors = pad-a\n\n" +
                "[Pad1]\nType = AnalogController\nCross = Android-1/Button97\n",
        )

        // Connections are handled in order: once the new pad-c has its defaults, pad-a's connection is done too.
        NativeLibrary.onDeviceConnected(1, "Pad A", "pad-a")
        connectNewPad(2, "pad-c", 2)

        assertEquals("Button97", crossOf("pad-a"))
        assertEquals(listOf("Android-1/Button97"), crossEntries(1))
        assertEquals(listOf("pad-b", "pad-a", "pad-c"), descriptors())

        NativeLibrary.onDeviceDisconnected(1)
        NativeLibrary.onDeviceConnected(3, "Pad A", "pad-a")
        connectNewPad(4, "pad-d", 3)

        assertEquals("Button97", crossOf("pad-a"))
        assertEquals(listOf("Android-1/Button97"), crossEntries(1))
        assertEquals(listOf("pad-b", "pad-a", "pad-c", "pad-d"), descriptors())
    }

    // Ruling 10: a known pad with no binding of its own anywhere on Port 1 would be dead, so it gets the defaults.
    @Test
    fun knownPadWithNoBindingsGetsDefaults() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\nControllerDescriptors = pad-a\n\n" +
                "[Pad1]\nType = AnalogController\nCross = Android-1/Button96\n",
        )

        NativeLibrary.onDeviceConnected(1, "Pad A", "pad-a")

        waitFor("Port 1 has pad-a's defaults") { crossEntries(0).isNotEmpty() }
        assertEquals(listOf("Android-0/Button96"), crossEntries(0))
        assertEquals(listOf("pad-a"), descriptors())
    }

    // Any reload, adapted or not, maps a connected pad left with no binding of its own.
    @Test
    fun reloadRemapsAConnectedPadWithNoBindings() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        settingsFile.writeText(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\nControllerDescriptors = pad-a\n\n" +
                "[Pad1]\nType = AnalogController\n",
        )

        NativeLibrary.reloadSettings()

        waitFor("Port 1 has pad-a's defaults again") { crossEntries(0).isNotEmpty() }
        assertEquals(listOf("Android-0/Button96"), crossEntries(0))
        assertEquals("Button96", crossOf("pad-a"))
    }

    @Test
    fun newPadGetsDefaults() {
        initialize(null)

        connectNewPad(1, "pad-c", 0)

        assertEquals(listOf("Android-0/Button96"), crossEntries(0))
        assertEquals("Button96", crossOf("pad-c"))
    }

    // A pad's place in the descriptor table, which numbers pads that share a name the same way in every run.
    @Test
    fun controllerIndexFollowsTheTable() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)

        assertEquals(0, NativeLibrary.getControllerIndex("pad-a"))
        assertEquals(1, NativeLibrary.getControllerIndex("pad-b"))
        assertEquals(-1, NativeLibrary.getControllerIndex("never-connected"))
    }

    @Test
    fun unknownPadHasNoBindings() {
        initialize(null)

        assertNull(NativeLibrary.getPadBindings("never-connected"))
    }

    // Port 1's inputs, in the core's order: buttons and half axes, as the controller type lists them.
    @Test
    fun padBindingsListThePortsInputs() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)

        val bindings = NativeLibrary.getPadBindings("pad-a")!!.toList()

        assertEquals("Up", bindings.first().name)
        assertEquals("D-Pad Up", bindings.first().displayName)
        assertEquals("Button19", bindings.first().binding)
        val cross = bindings.single { it.name == "Cross" }
        assertEquals(PadBindingKind.Button, cross.kind)
        assertEquals("Button96", cross.binding)
        val leftStickLeft = bindings.single { it.name == "LLeft" }
        assertEquals(PadBindingKind.HalfAxis, leftStickLeft.kind)
        assertEquals("-Axis0", leftStickLeft.binding)
        // The Analog toggle has no default on a pad: Mode opens the menu.
        assertNull(bindings.single { it.name == "Analog" }.binding)
        assertEquals("RUp", bindings.last().name)
    }

    @Test
    fun setPadBindingTouchesOnlyThatPad() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)

        NativeLibrary.setPadBinding("pad-b", "Cross", "Button99")

        assertEquals("Button96", crossOf("pad-a"))
        assertEquals("Button99", crossOf("pad-b"))
        assertEquals(listOf("Android-0/Button96", "Android-1/Button99"), readSettings().values("Pad1", "Cross"))
    }

    // getPadBindings lists only buttons and axes, so setPadBinding takes only those: never a GunCon's pointer.
    @Test
    fun setPadBindingRefusesAPointer() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Pad1]\nType = GunCon\n",
        )
        // A GunCon gets no pad defaults, so wait for the pad to be known instead.
        NativeLibrary.onDeviceConnected(1, "Pad pad-a", "pad-a")
        waitFor(
            "pad-a is in the table",
        ) { readSettings().values("Android", "ControllerDescriptors") == listOf("pad-a") }

        NativeLibrary.setPadBinding("pad-a", "Pointer", "Button96")

        assertEquals(emptyList<String>(), readSettings().values("Pad1", "Pointer"))
    }

    @Test
    fun clearingTouchesOnlyThatPad() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)

        NativeLibrary.setPadBinding("pad-b", "Cross", null)
        NativeLibrary.setPadBinding("pad-a", "Circle", "")

        assertNull(crossOf("pad-b"))
        assertEquals("Button96", crossOf("pad-a"))
        assertEquals(listOf("Android-0/Button96"), readSettings().values("Pad1", "Cross"))
        assertEquals(listOf("Android-1/Button97"), readSettings().values("Pad1", "Circle"))
    }

    @Test
    fun resetRestoresDefaults() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)
        NativeLibrary.setPadBinding("pad-a", "Cross", "Button100")
        NativeLibrary.setPadBinding("pad-b", "Cross", "Button99")
        NativeLibrary.setPadBinding("pad-b", "Circle", null)

        NativeLibrary.resetPadBindings("pad-b")

        assertEquals("Button96", crossOf("pad-b"))
        assertEquals("Button100", crossOf("pad-a"))
        val ini = readSettings()
        assertEquals(listOf("Android-0/Button100", "Android-1/Button96"), ini.values("Pad1", "Cross"))
        assertEquals(listOf("Android-0/Button97", "Android-1/Button97"), ini.values("Pad1", "Circle"))
    }

    // Every pad key but the menu's reaches the core, so BUTTON_1 to 16, C and Z can be bound.
    @Test
    fun unmappedKeyReachesCore() {
        initialize(null)
        NativeLibrary.onDeviceConnected(1, "Pad A", "pad-a")

        assertTrue(NativeLibrary.onKey(1, 188, true)) // KEYCODE_BUTTON_1
        assertTrue(NativeLibrary.onKey(1, 188, false))
        assertTrue(NativeLibrary.onKey(1, 98, true)) // KEYCODE_BUTTON_C
        assertTrue(NativeLibrary.onKey(1, 98, false))
        assertFalse(NativeLibrary.onKey(1, 110, true)) // KEYCODE_BUTTON_MODE
        assertFalse(NativeLibrary.onKey(1, 4, true)) // KEYCODE_BACK
    }

    /** Connects a pad never seen before and waits until Port 1 has its defaults, at [index]. */
    private fun connectNewPad(
        deviceId: Int,
        descriptor: String,
        index: Int,
    ) {
        NativeLibrary.onDeviceConnected(deviceId, "Pad $descriptor", descriptor)
        waitFor("Port 1 has $descriptor's defaults") { crossEntries(index).isNotEmpty() }
    }

    private fun crossOf(descriptor: String): String? =
        NativeLibrary.getPadBindings(descriptor)!!.single { it.name == "Cross" }.binding

    private fun crossEntries(index: Int): List<String> =
        readSettings().values("Pad1", "Cross").filter { it.startsWith("Android-$index/") }

    private fun descriptors(): List<String> = readSettings().values("Android", "ControllerDescriptors")

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting: $what")
            Thread.sleep(50)
        }
    }

    private fun initialize(settings: String?) {
        if (settings != null) settingsFile.writeText(settings)
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
