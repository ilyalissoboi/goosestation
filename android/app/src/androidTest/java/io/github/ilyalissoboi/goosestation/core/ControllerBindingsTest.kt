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
        // The Analog toggle is a hotkey now (getHotkeyBindings).
        assertTrue(bindings.none { it.name == "Analog" })
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

    @Test
    fun hotkeysAreTheCoresListWithoutTheMenus() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)

        val hotkeys = NativeLibrary.getHotkeyBindings("pad-a")!!.toList()
        val names = hotkeys.map { it.name }

        for (name in listOf(
            "ToggleFastForward",
            "Turbo",
            "Rewind",
            "Screenshot",
            "SaveSelectedSaveState",
            "UndoLoadState",
            "ToggleAnalogMode",
        )) {
            assertTrue("$name is offered", name in names)
        }
        for (name in listOf("Reset", "ChangeDisc", "TogglePause", "OpenPauseMenu")) {
            assertFalse("$name isn't offered", name in names)
        }
        assertTrue(names.none { it.startsWith("LoadGameState") || it.startsWith("SaveGlobalState") })
        assertEquals(names.distinct(), names)
        // The core's categories and names, in its order: General first, the save states before the controller's.
        val rewind = hotkeys.single { it.name == "Rewind" }
        assertEquals("System", rewind.category)
        assertEquals("Rewind", rewind.displayName)
        assertEquals("Toggle Fast Forward", hotkeys.single { it.name == "ToggleFastForward" }.displayName)
        assertEquals("General", hotkeys.first().category)
        assertTrue(names.indexOf("UndoLoadState") < names.indexOf("ToggleAnalogMode"))
        // Unassigned by default.
        assertTrue(hotkeys.all { it.binding == null && it.kind == PadBindingKind.Button })
        assertNull(NativeLibrary.getHotkeyBindings("pad-unknown"))
    }

    @Test
    fun aHotkeyIsASelectChordOnThatPad() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)
        NativeLibrary.setHotkeyBinding("pad-a", "ToggleFastForward", "Button100")

        NativeLibrary.setHotkeyBinding("pad-b", "ToggleFastForward", "Button99")
        NativeLibrary.setHotkeyBinding("pad-b", "ToggleAnalogMode", "Button98")

        val ini = readSettings()
        assertEquals(
            listOf("Android-0/Button109 & Android-0/Button100", "Android-1/Button109 & Android-1/Button99"),
            ini.values("Hotkeys", "ToggleFastForward"),
        )
        assertEquals(listOf("Android-1/Button109 & Android-1/Button98"), ini.values("Hotkeys", "ToggleAnalogMode"))
        assertEquals(emptyList<String>(), ini.values("Pad1", "Analog"))
        val hotkeys = NativeLibrary.getHotkeyBindings("pad-b")!!.associate { it.name to it.binding }
        assertEquals("Button109 & Android-1/Button99", hotkeys["ToggleFastForward"])
        assertEquals("Button109 & Android-1/Button98", hotkeys["ToggleAnalogMode"])
        assertNull(hotkeys["Screenshot"])
    }

    // The old Analog Toggle row's binding, in a settings.ini from before the Hotkeys section, becomes the hotkey.
    @Test
    fun anOldAnalogToggleBindingBecomesTheHotkey() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\nControllerDescriptors = pad-a\n\n" +
                "[Pad1]\nType = AnalogController\nCross = Android-0/Button96\nAnalog = Android-0/Button100\n",
        )

        val ini = readSettings()
        assertEquals(listOf("Android-0/Button109 & Android-0/Button100"), ini.values("Hotkeys", "ToggleAnalogMode"))
        assertEquals(emptyList<String>(), ini.values("Pad1", "Analog"))
    }

    @Test
    fun aHotkeyIsCleared() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        NativeLibrary.setHotkeyBinding("pad-a", "Screenshot", "Button99")

        NativeLibrary.setHotkeyBinding("pad-a", "Screenshot", null)

        assertEquals(emptyList<String>(), readSettings().values("Hotkeys", "Screenshot"))
    }

    @Test
    fun onlyTheThreeHotkeysCanBeSet() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)

        NativeLibrary.setHotkeyBinding("pad-a", "OpenPauseMenu", "Button99")

        assertEquals(emptyList<String>(), readSettings().values("Hotkeys", "OpenPauseMenu"))
    }

    @Test
    fun resetClearsThatPadsHotkeys() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)
        NativeLibrary.setHotkeyBinding("pad-a", "Screenshot", "Button100")
        NativeLibrary.setHotkeyBinding("pad-b", "Screenshot", "Button99")
        NativeLibrary.setHotkeyBinding("pad-b", "ToggleAnalogMode", "Button98")

        NativeLibrary.resetPadBindings("pad-b")

        val ini = readSettings()
        assertEquals(listOf("Android-0/Button109 & Android-0/Button100"), ini.values("Hotkeys", "Screenshot"))
        assertEquals(emptyList<String>(), ini.values("Hotkeys", "ToggleAnalogMode"))
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

    @Test
    fun portTypeIsTheDefaultWithoutASetting() {
        initialize(null)

        // The core's defaults (Settings::DEFAULT_CONTROLLER_1_TYPE and _2_TYPE).
        assertEquals("AnalogController", NativeLibrary.getPortType(0))
        assertEquals("None", NativeLibrary.getPortType(1))
    }

    @Test
    fun portTypeIsTheSettings() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Pad1]\nType = DigitalController\n" +
                "\n[Pad2]\nType = NeGcon\n",
        )

        assertEquals("DigitalController", NativeLibrary.getPortType(0))
        assertEquals("NeGcon", NativeLibrary.getPortType(1))
    }

    @Test
    fun aNewControllerIsOnPort1() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)

        assertEquals(0, NativeLibrary.getControllerPort("pad-a"))
        assertEquals(-1, NativeLibrary.getControllerPort("no-such-pad"))
    }

    @Test
    fun movingAControllerMovesItsBindings() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Pad2]\nType = AnalogController\n",
        )
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)

        NativeLibrary.setControllerPort("pad-b", 1)

        val ini = readSettings()
        assertEquals(listOf("Android-0/Button96"), ini.values("Pad1", "Cross"))
        assertEquals(listOf("Android-1/Button96"), ini.values("Pad2", "Cross"))
        assertEquals(listOf("1", "2"), ini.values("Android", "ControllerPorts"))
        assertEquals(1, NativeLibrary.getControllerPort("pad-b"))
        assertEquals("Button96", crossOf("pad-b"))
    }

    // Review Focus 3: a desktop file's other sources stay first and untouched in the keys a controller moves into, and
    // a type Android doesn't offer stays.
    @Test
    fun aMoveKeepsOtherSourcesEntries() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\nControllerDescriptors = pad-a\n\n" +
                "[Pad1]\nType = AnalogController\nCross = SDL-0/FaceSouth\nCross = Android-0/Button97\n\n" +
                "[Pad2]\nType = AnalogController\nCross = SDL-1/FaceSouth\n",
        )
        NativeLibrary.onDeviceConnected(1, "Pad A", "pad-a")

        NativeLibrary.setControllerPort("pad-a", 1)

        val ini = readSettings()
        assertEquals(listOf("SDL-0/FaceSouth"), ini.values("Pad1", "Cross"))
        assertEquals(listOf("SDL-1/FaceSouth", "Android-0/Button97"), ini.values("Pad2", "Cross"))
    }

    // Review Focus 1: a controller moved away and back has the bindings it had.
    @Test
    fun movingBackRestoresTheRemap() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        NativeLibrary.setPadBinding("pad-a", "Cross", "Button97")

        NativeLibrary.setControllerPort("pad-a", 3)
        assertEquals(listOf("Android-0/Button97"), readSettings().values("Pad4", "Cross"))
        NativeLibrary.setControllerPort("pad-a", 0)

        assertEquals("Button97", crossOf("pad-a"))
        assertEquals(emptyList<String>(), readSettings().values("Pad4", "Cross"))
    }

    // A move to a port whose type has inputs the controller has no entry for gives it their defaults there.
    @Test
    fun aMoveAddsTheNewTypesMissingDefaults() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Pad2]\nType = NeGcon\n",
        )
        connectNewPad(1, "pad-a", 0)

        NativeLibrary.setControllerPort("pad-a", 1)

        assertTrue(readSettings().values("Pad2", "SteeringLeft").single().startsWith("Android-0/"))
    }

    // Review Focus 4: picking a type adds its defaults only for the inputs the controller has none for, so a remap stays
    // across Analog, Digital and back, and a NeGcon's steering gets one the first time.
    @Test
    fun pickingATypeAddsItsMissingDefaults() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        NativeLibrary.setPadBinding("pad-a", "Cross", "Button97")

        NativeLibrary.setPortType(0, "NeGcon")
        assertEquals("NeGcon", NativeLibrary.getPortType(0))
        assertEquals(listOf("Android-0/Button97"), readSettings().values("Pad1", "Cross"))
        assertTrue(readSettings().values("Pad1", "SteeringLeft").single().startsWith("Android-0/"))

        NativeLibrary.setPortType(0, "DigitalController")
        NativeLibrary.setPortType(0, "AnalogController")
        assertEquals(listOf("Android-0/Button97"), readSettings().values("Pad1", "Cross"))
        assertEquals("Button97", crossOf("pad-a"))
    }

    // Each controller on the port gets the type's defaults, and one on another port doesn't.
    @Test
    fun aTypeGivesDefaultsToEachControllerOnItsPort() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)
        NativeLibrary.setControllerPort("pad-b", 1)

        NativeLibrary.setPortType(0, "NeGcon")

        assertTrue(readSettings().values("Pad1", "SteeringLeft").single().startsWith("Android-0/"))
        assertEquals(emptyList<String>(), readSettings().values("Pad2", "SteeringLeft"))
    }

    @Test
    fun typeSettingsAreTheCores() {
        initialize(null)

        val flat = NativeLibrary.getTypeSettings("AnalogController")
        assertEquals(0, flat.size % TypeSetting.FIELDS)
        val byKey = TypeSetting.parse(flat).associateBy { it.key }
        val deadzone = byKey.getValue("AnalogDeadzone")
        assertEquals(TypeSetting.Kind.Float, deadzone.kind)
        assertEquals("%.0f%%", deadzone.format)
        assertEquals(100f, deadzone.multiplier)
        assertEquals(TypeSetting.Kind.Bool, byKey.getValue("ForceAnalogOnReset").kind)
        assertEquals(4, byKey.getValue("InvertLeftStick").options.size)
        assertEquals(TypeSetting.Kind.IntList, byKey.getValue("InvertLeftStick").kind)
        assertEquals(
            listOf("ForcePopnControllerMode"),
            TypeSetting.parse(NativeLibrary.getTypeSettings("DigitalController")).map { it.key },
        )
        assertEquals(0, NativeLibrary.getTypeSettings("None").size)
        assertEquals(0, NativeLibrary.getTypeSettings("NoSuchType").size)
    }

    // The analog mode hotkey is for a DualShock only, on the controller's own port.
    @Test
    fun theAnalogHotkeyFollowsThePortsType() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Pad2]\nType = DigitalController\n",
        )
        connectNewPad(1, "pad-a", 0)

        NativeLibrary.setControllerPort("pad-a", 1)

        assertFalse("ToggleAnalogMode" in NativeLibrary.getHotkeyBindings("pad-a")!!.map { it.name })
    }

    // A macro's trigger is each controller's own, in its port's [PadN], and moves with it.
    @Test
    fun aMacroTriggerIsTheControllersOwn() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n[Pad2]\nType = AnalogController\n",
        )
        connectNewPad(1, "pad-a", 0)

        NativeLibrary.setPadBinding("pad-a", "Macro1", "Button99")

        assertEquals(listOf("Android-0/Button99"), readSettings().values("Pad1", "Macro1"))
        val macros = NativeLibrary.getMacroBindings("pad-a")!!.toList()
        assertEquals(listOf("Macro1", "Macro2", "Macro3", "Macro4"), macros.map { it.name })
        assertEquals("Button99", macros[0].binding)
        assertEquals("Macro 1", macros[0].displayName)
        assertNull(NativeLibrary.getMacroBindings("pad-unknown"))

        NativeLibrary.setControllerPort("pad-a", 1)
        assertEquals(listOf("Android-0/Button99"), readSettings().values("Pad2", "Macro1"))
        assertEquals("Button99", NativeLibrary.getMacroBindings("pad-a")!![0].binding)
    }

    @Test
    fun rumbleStrengthIsKeptPerController() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        connectNewPad(2, "pad-b", 1)

        assertEquals(100, NativeLibrary.getRumbleStrength("pad-b"))
        NativeLibrary.setRumbleStrength("pad-b", 30)

        assertEquals(30, NativeLibrary.getRumbleStrength("pad-b"))
        assertEquals(100, NativeLibrary.getRumbleStrength("pad-a"))
        assertEquals(listOf("100", "30"), readSettings().values("Android", "ControllerRumble"))
        assertEquals(-1, NativeLibrary.getRumbleStrength("pad-unknown"))
    }

    @Test
    fun theSavedRumbleStrengthIsRead() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\nControllerDescriptors = pad-a\n" +
                "ControllerRumble = 40\n",
        )

        assertEquals(40, NativeLibrary.getRumbleStrength("pad-a"))
    }

    // What a macro can press: the type's buttons and axes, in the core's order.
    @Test
    fun typeInputsAreTheCores() {
        initialize(null)

        val negcon = NativeLibrary.getTypeInputs("NeGcon").toList().chunked(2)
        assertEquals(listOf("Up", "D-Pad Up"), negcon.first())
        assertTrue(listOf("SteeringLeft", "Steering (Twist) Left") in negcon)
        assertEquals(0, NativeLibrary.getTypeInputs("NoSuchType").size)
    }

    // A settings.ini from another install renumbers the controllers: its ports and strengths, by position, go with its
    // descriptors, or this install's first controller would land on the other's Port 2.
    @Test
    fun anotherInstallsPortsAndStrengthsGo() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = another-install\nControllerDescriptors = pad-x\n" +
                "ControllerPorts = 2\nControllerRumble = 30\n",
        )

        val ini = readSettings()
        assertEquals(emptyList<String>(), ini.values("Android", "ControllerPorts"))
        assertEquals(emptyList<String>(), ini.values("Android", "ControllerRumble"))
        connectNewPad(1, "pad-a", 0)
        assertEquals(0, NativeLibrary.getControllerPort("pad-a"))
    }

    @Test
    fun theSavedPortIsRead() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\nControllerDescriptors = pad-a\n" +
                "ControllerDescriptors = pad-b\nControllerPorts = 1\nControllerPorts = 6\n",
        )

        assertEquals(0, NativeLibrary.getControllerPort("pad-a"))
        assertEquals(5, NativeLibrary.getControllerPort("pad-b"))
    }

    @Test
    fun withoutMultitapOnlyPorts1And2() {
        initialize(null)

        assertEquals(listOf("0", "Port 1", "1", "Port 2"), NativeLibrary.getOfferedPorts().toList())
    }

    @Test
    fun multitapInAGamesFileOffersItsPorts() {
        File(dataRoot, "gamesettings").mkdirs()
        File(dataRoot, "gamesettings/SLUS-00001.ini")
            .writeText("[ControllerPorts]\nUseGameSettingsForController = true\nMultitapMode = Port1Only\n")
        initialize(null)

        assertEquals(
            // Desktop's order: a port's slots together, then the other port.
            listOf("0", "Port 1A", "2", "Port 1B", "3", "Port 1C", "4", "Port 1D", "1", "Port 2"),
            NativeLibrary.getOfferedPorts().toList(),
        )
    }

    @Test
    fun port2OnlyMultitapOffersPort2s() {
        initialize(
            "[Main]\nSettingsVersion = 3\n\n[Android]\nInstallID = $INSTALL_ID\n\n" +
                "[ControllerPorts]\nMultitapMode = Port2Only\n",
        )

        assertEquals(
            listOf("Port 1", "Port 2A", "Port 2B", "Port 2C", "Port 2D"),
            NativeLibrary
                .getOfferedPorts()
                .toList()
                .chunked(2)
                .map { it[1] },
        )
    }

    private fun initialize(settings: String?) {
        if (settings != null) settingsFile.writeText(settings)
        initializeCore(context, dataRoot, biosDir, File(dataRoot.parentFile, "offline"))
    }

    // --- A game's controllers ---

    private fun gameFile(key: String = GAME_KEY) =
        File(dataRoot, "gamesettings/$key.ini").apply { parentFile!!.mkdirs() }

    // A game's file counts for the controllers only with UseGameSettingsForController, as the core reads it.
    @Test
    fun aFileWithoutTheFlagOffersNoMultitap() {
        gameFile().writeText("[ControllerPorts]\nMultitapMode = Port1Only\n")
        initialize(null)

        assertEquals(listOf("0", "Port 1", "1", "Port 2"), NativeLibrary.getOfferedPorts().toList())
    }

    @Test
    fun aGamesTypeGetsItsDefaults() {
        initialize(null)
        connectNewPad(1, "pad-a", 0)
        NativeLibrary.setGameStringSetting(GAME_KEY, "Pad1", "Type", "NeGcon")

        NativeLibrary.updateGameControllers(GAME_KEY)

        assertEquals("AnalogController", NativeLibrary.getPortType(0))
        assertTrue(readSettings().values("Pad1", "SteeringLeft").single().startsWith("Android-0/"))
        assertEquals("true", Ini.parse(gameFile().readText()).value("ControllerPorts", "UseGameSettingsForController"))
    }

    @Test
    fun theFlagFollowsTheKeys() {
        initialize(null)
        NativeLibrary.setGameStringSetting(GAME_KEY, "ControllerPorts", "MultitapMode", "Port1Only")
        NativeLibrary.setGameIntSetting(GAME_KEY, "GPU", "ResolutionScale", 4)
        NativeLibrary.updateGameControllers(GAME_KEY)
        assertEquals("true", Ini.parse(gameFile().readText()).value("ControllerPorts", "UseGameSettingsForController"))

        NativeLibrary.deleteGameSetting(GAME_KEY, "ControllerPorts", "MultitapMode")
        NativeLibrary.updateGameControllers(GAME_KEY)

        assertEquals(
            emptyList<String>(),
            Ini.parse(gameFile().readText()).values("ControllerPorts", "UseGameSettingsForController"),
        )
        assertEquals("4", Ini.parse(gameFile().readText()).value("GPU", "ResolutionScale"))
    }

    // Stage 2's known limit: the Port page lists the inputs of a type a game sets for the port too, to remap them.
    @Test
    fun thePortPageListsAGamesTypesInputs() {
        gameFile().writeText("[ControllerPorts]\nUseGameSettingsForController = true\n[Pad1]\nType = NeGcon\n")
        initialize(null)
        connectNewPad(1, "pad-a", 0)

        val bindings = NativeLibrary.getPadBindings("pad-a")!!.toList()

        assertEquals("", bindings.first { it.name == "Cross" }.category)
        val steering = bindings.single { it.name == "SteeringLeft" }
        assertEquals("NeGcon", steering.category)
        // Cross is the port's own type's, listed once.
        assertEquals(1, bindings.count { it.name == "Cross" })

        NativeLibrary.setPadBinding("pad-a", "SteeringLeft", "+Axis0")
        assertEquals(listOf("Android-0/+Axis0"), readSettings().values("Pad1", "SteeringLeft"))
    }

    @Test
    fun aGamesTypeGetsItsDefaultsOnReset() {
        gameFile().writeText("[ControllerPorts]\nUseGameSettingsForController = true\n[Pad1]\nType = NeGcon\n")
        initialize(null)
        connectNewPad(1, "pad-a", 0)

        NativeLibrary.resetPadBindings("pad-a")

        assertTrue(readSettings().values("Pad1", "SteeringLeft").single().startsWith("Android-0/"))
    }

    private fun readSettings(): Ini = Ini.parse(settingsFile.readText())

    private companion object {
        const val INSTALL_ID = "test-install"
        const val GAME_KEY = "SLUS-00001"
    }
}
