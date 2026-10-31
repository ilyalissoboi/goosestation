// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The settings pages and the library's top bar at phone portrait and handheld landscape sizes, at font scales 1.0 and
 * 1.3: no text squeezed, broken mid-word, overlapping another or past the screen's edge; every focusable reached with
 * the D-pad, and in view once focused.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OrientationLayoutTest {
    private data class Config(
        val name: String,
        val size: DpSize,
        val fontScale: Float,
    ) {
        override fun toString() = "$name at font scale $fontScale"
    }

    private companion object {
        val HOTKEYS =
            listOf(
                PadBinding(
                    "ToggleFastForward",
                    "Toggle Fast Forward",
                    PadBindingKind.Button,
                    "Button109 & Android-1/Button99",
                ),
                PadBinding("Analog", "Analog Toggle", PadBindingKind.Button, null),
                PadBinding("Screenshot", "Save Screenshot", PadBindingKind.Button, "Button109 & Android-1/Button188"),
            )

        val LONG_GAME =
            GameEntry(
                path = "content://test/tree/games/document/long.cue",
                title = "The Legend of the Extraordinarily Long-Named Adventure: Director's Cut Special Edition",
                serial = "SLPS-01234",
                region = "NTSC-J",
                totalPlayedSeconds = 12 * 3600 + 34 * 60,
                lastPlayedSeconds = 0,
                coverPath = null,
            )

        const val SCREEN = "screen"
        const val SCPH5500 = "SCPH-5500 (v3.0 09-09-96 J)"

        val SIZES =
            listOf(
                "portrait 360x800" to DpSize(360.dp, 800.dp),
                "portrait 411x891" to DpSize(411.dp, 891.dp),
                "landscape 800x360" to DpSize(800.dp, 360.dp),
                // The Thor's 1920x1080 px at 480 and 320 dpi.
                "landscape 640x360" to DpSize(640.dp, 360.dp),
                "landscape 960x540" to DpSize(960.dp, 540.dp),
            )
        val CONFIGS = SIZES.flatMap { (name, size) -> listOf(1f, 1.3f).map { Config(name, size, it) } }

        val PAD = ConnectedController(deviceId = 5, name = "Goose Virtual Pad", descriptor = "pad-a")
        val LONG_PAD =
            ConnectedController(
                deviceId = 6,
                name = "Second Wireless Controller With A Long Name",
                descriptor = "pad-b",
            )

        /** Port 1's inputs, as the analog controller has them, most bound as a pad's defaults would be. */
        val BINDINGS =
            listOf(
                Triple("Up", "D-Pad Up", "Button19"),
                Triple("Right", "D-Pad Right", "Button22"),
                Triple("Down", "D-Pad Down", "Button20"),
                Triple("Left", "D-Pad Left", "Button21"),
                Triple("Triangle", "Triangle", "Button100"),
                Triple("Circle", "Circle", "Button97"),
                Triple("Cross", "Cross", "Button96"),
                Triple("Square", "Square", "Button99"),
                Triple("Select", "Select", "Button109"),
                Triple("Start", "Start", "Button108"),
                Triple("Analog", "Analog Toggle", null),
                Triple("L1", "L1", "Button102"),
                Triple("R1", "R1", "Button103"),
                Triple("L2", "L2", "+Axis23"),
                Triple("R2", "R2", "+Axis22"),
                Triple("L3", "L3", "Button106"),
                Triple("R3", "R3", "Button107"),
            ).map { (name, display, binding) -> PadBinding(name, display, PadBindingKind.Button, binding) } +
                listOf(
                    Triple("LLeft", "Left Stick Left", "-Axis0"),
                    Triple("LRight", "Left Stick Right", "+Axis0"),
                    Triple("LDown", "Left Stick Down", "+Axis1"),
                    Triple("LUp", "Left Stick Up", "-Axis1"),
                    Triple("RLeft", "Right Stick Left", "-Axis11"),
                    Triple("RRight", "Right Stick Right", "+Axis11"),
                    Triple("RDown", "Right Stick Down", "+Axis14"),
                    Triple("RUp", "Right Stick Up", "-Axis14"),
                ).map { (name, display, binding) -> PadBinding(name, display, PadBindingKind.HalfAxis, binding) }

        val ASPECT_NAMES =
            listOf("Auto (Game Native)", "Stretch To Fill", "Custom", "4:3", "16:9", "19:9", "20:9", "PAR 1:1")
    }

    @get:Rule val rule = createComposeRule()

    private val config = mutableStateOf(CONFIGS.first())

    /** Shows [content] at each of [CONFIGS] in turn, composed afresh, and runs [check] on it. */
    private fun eachConfig(
        content: @Composable () -> Unit,
        check: (Config) -> Unit,
    ) {
        rule.setContent {
            val current by config
            key(current) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(current.size) then
                        DeviceConfigurationOverride.FontScale(current.fontScale),
                ) {
                    GooseStationTheme {
                        Box(Modifier.fillMaxSize().testTag(SCREEN)) { content() }
                    }
                }
            }
        }
        for (each in CONFIGS) {
            config.value = each
            rule.waitForIdle()
            try {
                check(each)
            } catch (e: AssertionError) {
                throw AssertionError("$each: ${e.message}", e)
            }
        }
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun bounds(node: SemanticsNodeInteraction): DpRect = node.getUnclippedBoundsInRoot()

    private fun screen() = bounds(rule.onNodeWithTag(SCREEN))

    /** The settings page's scrolling column, which the page's content shows through. */
    private fun viewport() = bounds(rule.onNode(hasScrollAction()))

    private fun DpRect.describe() = "[$left, $top, $right, $bottom]"

    private fun DpRect.contains(other: DpRect): Boolean {
        val slack = 0.5.dp
        return other.left >= left - slack &&
            other.top >= top - slack &&
            other.right <= right + slack &&
            other.bottom <= bottom + slack
    }

    private fun DpRect.overlaps(other: DpRect): Boolean {
        val slack = 0.5.dp
        return left < other.right - slack &&
            other.left < right - slack &&
            top < other.bottom - slack &&
            other.top < bottom - slack
    }

    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    /** The focused element, all of it in [viewport]. */
    private fun assertFocusedInView(viewport: DpRect = viewport()) {
        val focused = rule.onNode(isFocused())
        focused.assertIsDisplayed()
        val box = bounds(focused)
        assertTrue(
            "the focused ${focused.fetchSemanticsNode().mergedText()} at ${box.describe()} isn't all in the " +
                "viewport ${viewport.describe()}",
            viewport.contains(box),
        )
    }

    private fun SemanticsNode.mergedText(): String =
        "\"" + (config.getOrNull(SemanticsProperties.Text)?.joinToString(" / ") { it.text } ?: "") + "\""

    private fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Presses [key] until the focused element has [text], checking each one it reaches is in view. */
    private fun pressUntilFocused(
        key: Key,
        text: String,
        limit: Int = 60,
    ) {
        repeat(limit) {
            if (focusedHas(text)) return
            press(key)
            assertFocusedInView()
        }
        if (!focusedHas(text)) fail("$key never focused \"$text\"")
    }

    private fun focusedText(): String = rule.onNode(isFocused()).fetchSemanticsNode().mergedText()

    /**
     * Steps the focused cycler row with [key] until its value stops changing, at an end of its choices, checking at
     * each choice that every text is laid out and the row is in view.
     */
    private fun stepToTheEnd(
        key: Key,
        limit: Int = 30,
    ) {
        repeat(limit) {
            val before = focusedText()
            press(key)
            assertTextsLaidOut()
            assertFocusedInView()
            if (focusedText() == before) return
        }
        fail("$key never reached the end of ${focusedText()}")
    }

    /**
     * Every text on screen: laid out whole (not cut off, no word broken over lines), inside the screen across, and
     * clear of every other text. With [inView], only the part of each text its scrolling containers show is compared,
     * and texts scrolled out of view are left out: for a screen of several scrolling panels.
     */
    private fun assertTextsLaidOut(inView: Boolean = false) {
        val screen = screen()
        val nodes =
            rule
                .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val boxes =
            nodes
                .map { node ->
                    val interaction = rule.onNode(matchesId(node.id), useUnmergedTree = true)
                    node to if (inView) interaction.getBoundsInRoot() else bounds(interaction)
                }.filter { (_, box) -> !inView || (box.right > box.left && box.bottom > box.top) }
        for ((node, box) in boxes) {
            val text = node.text()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            val layout = results.single()
            // Not didOverflowWidth: the semantics' layout is at the text's max width, wider than the text itself. A
            // word too wide for its line is broken, which the check below catches. A text ending in an ellipsis, such
            // as a game's title in the list, is cut short on purpose.
            assertTrue(
                "\"$text\" is cut off at the bottom",
                !layout.didOverflowHeight || layout.layoutInput.overflow == TextOverflow.Ellipsis,
            )
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                if (end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()) {
                    fail("\"$text\" is broken mid-word at ${box.right - box.left} wide: \"${text.substring(0, end)}|\"")
                }
            }
            assertTrue(
                "\"$text\" at ${box.describe()} goes past the screen's sides ${screen.describe()}",
                box.left >= screen.left - 0.5.dp && box.right <= screen.right + 0.5.dp,
            )
        }
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                val (a, aBox) = boxes[i]
                val (b, bBox) = boxes[j]
                assertTrue(
                    "\"${a.text()}\" ${aBox.describe()} overlaps \"${b.text()}\" ${bBox.describe()}",
                    !aBox.overlaps(bBox),
                )
            }
        }
    }

    private fun matchesId(id: Int) = SemanticsMatcher("id $id") { it.id == id }

    private fun assertShows(
        text: String,
        index: Int = 0,
    ) {
        val node = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)[index]
        node.performScrollTo()
        node.assertIsDisplayed()
    }

    @Test
    fun theGameMenuAndItsSlots() {
        val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
        val entries =
            (1..SaveSlots.COUNT).map {
                SlotEntry(
                    it,
                    if (it % 3 ==
                        0
                    ) {
                        null
                    } else {
                        now.toEpochSecond() - 86_400 * it
                    },
                )
            }
        val menu = mutableStateOf(GameMenuState(booted = true, analogMode = true))
        eachConfig({ GameMenu(menu.value, NoMenuActions, LONG_GAME, now) }) {
            assertTextsLaidOut(inView = true)
            assertTrue("Resume is focused, not ${focusedText()}", focusedHas("Resume"))
            pressUntilFocusedInView(Key.DirectionDown, "Exit game")

            menu.value = menu.value.copy(slots = SlotPanel(SlotMode.Save, entries, focus = 1))
            rule.waitForIdle()
            assertTextsLaidOut(inView = true)
            assertTrue("Slot 1 is focused, not ${focusedText()}", focusedHas("Slot 1"))
            pressUntilFocusedInView(Key.DirectionDown, "Slot 10")
            assertTextsLaidOut(inView = true)
            // As the next config's menu opens.
            menu.value = GameMenuState(booted = true, analogMode = true)
        }
    }

    @Test
    fun theResumePromptWithALongTitle() {
        val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
        val offer = ResumeOffer(LONG_GAME, now.toEpochSecond() - 86_400, null)
        eachConfig({ ResumePrompt(offer, onResume = {}, onStartOver = {}, onCancel = {}, now = now) }) {
            assertTextsLaidOut()
            assertTrue("Resume is focused, not ${focusedText()}", focusedHas("Resume"))
            assertFocusedInView()
            // Beside Resume, or under it once the buttons wrap.
            press(Key.DirectionRight)
            if (!focusedHas("Start over")) pressUntilFocused(Key.DirectionDown, "Start over")
            assertFocusedInView()
        }
    }

    /** As [pressUntilFocused], each focused element all in view in the scrolling container around it. */
    private fun pressUntilFocusedInView(
        key: Key,
        text: String,
        limit: Int = 30,
    ) {
        repeat(limit) {
            if (focusedHas(text)) return
            press(key)
            assertFocusedInView(scrollerAroundFocused())
        }
        if (!focusedHas(text)) fail("$key never focused \"$text\"")
    }

    /** The scrolling container the focused element is in, of the several a screen can have. */
    private fun scrollerAroundFocused(): DpRect {
        val focused = bounds(rule.onNode(isFocused()))
        val scrollers = rule.onAllNodes(hasScrollAction())
        val count = scrollers.fetchSemanticsNodes().size
        // The one it overlaps most: a row half scrolled out still mostly overlaps its own.
        return (0 until count)
            .map { bounds(scrollers[it]) }
            .maxBy { box ->
                val across = minOf(box.right, focused.right) - maxOf(box.left, focused.left)
                val down = minOf(box.bottom, focused.bottom) - maxOf(box.top, focused.top)
                if (across > 0.dp && down > 0.dp) across.value * down.value else 0f
            }
    }

    private object NoMenuActions : GameMenuActions {
        override fun resume() {}

        override fun openSlots(mode: SlotMode) {}

        override fun closeSlots() {}

        override fun pickSlot(slot: Int) {}

        override fun confirmReplace() {}

        override fun cancelReplace() {}

        override fun toggleFastForward() {}

        override fun toggleAnalogMode() {}

        override fun screenshot() {}

        override fun askReset() {}

        override fun confirmReset() {}

        override fun cancelReset() {}

        override fun exit() {}
    }

    @Test
    fun theHubWithTheCoreReady() {
        eachConfig({ SettingsScreen(focusEntry = null, coreReady = true, onOpen = {}, onBack = {}) }) {
            assertTextsLaidOut()
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Folders")
            pressUntilFocused(Key.DirectionUp, "Controllers")
        }
    }

    @Test
    fun theHubBeforeTheCoreStarts() {
        eachConfig({ SettingsScreen(focusEntry = null, coreReady = false, onOpen = {}, onBack = {}) }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("Folders"))
            assertFocusedInView()
            for (i in 0 until 3) assertShows(NEEDS_CORE, i)
        }
    }

    @Test
    fun theLoadingPage() {
        eachConfig({ SettingsLoadingPage("Graphics", onBack = {}) }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("Reading the settings"))
            assertFocusedInView()
        }
    }

    @Test
    fun graphicsThroughEachRowsChoices() {
        eachConfig({
            // Values from settings.ini that the page doesn't offer, so each list ends with one.
            var settings by remember { mutableStateOf(GraphicsSettings("Software", 12, "21:9", showFps = true)) }
            GraphicsScreen(settings, ASPECT_NAMES, vulkanFailed = true, onChange = { settings = it }, onBack = {})
        }) {
            assertTextsLaidOut()
            assertShows("Vulkan didn't start")
            assertShows("Higher is sharper")
            assertTrue(focusedHas("Renderer"))
            assertFocusedInView()

            // Each row starts on its value from settings.ini, the last of its choices, and steps back to the first.
            for (
            (row, last) in
            listOf(
                "Renderer" to "Software (from settings.ini)",
                "Internal resolution" to "12x (from settings.ini)",
                "Aspect ratio" to "21:9 (from settings.ini)",
            )
            ) {
                pressUntilFocused(Key.DirectionDown, row)
                assertTrue("$row isn't on $last", focusedHas(last))
                stepToTheEnd(Key.DirectionLeft)
                assertTrue("$row stepped past its first choice", focusedHas(row))
            }
            pressUntilFocused(Key.DirectionDown, "Show FPS")
            rule.onNode(isFocused()).assertIsOn()
        }
    }

    @Test
    fun biosWithLongNames() {
        val images =
            listOf(
                BiosImage("ps-30j.bin", SCPH5500, "NTSC-J"),
                BiosImage("scph1001.bin", "SCPH-1001, DTLH-3000 (v2.2 12-04-95 A)", "NTSC-U"),
                BiosImage("scph7502.bin", "SCPH-7502 (v4.1 12-16-97 E)", "PAL"),
            )
        val auto = mapOf(BiosRegion.NtscU to SCPH5500, BiosRegion.NtscJ to SCPH5500, BiosRegion.Pal to null)
        eachConfig({
            var overrides by remember { mutableStateOf(mapOf(BiosRegion.Pal to "scph5552.bin")) }
            BiosScreen(
                images = images,
                auto = auto,
                overrides = overrides,
                fastBoot = true,
                onSetOverride = { region, file ->
                    overrides = if (file == null) overrides - region else overrides + (region to file)
                },
                onFastBoot = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("NTSC-U (Americas)"))
            assertFocusedInView()

            // Automatic, then the Japanese image, noted, on to the last.
            press(Key.DirectionRight)
            assertTextsLaidOut()
            assertShows("made for NTSC-J")
            stepToTheEnd(Key.DirectionRight)
            assertTrue(focusedHas("SCPH-7502"))

            // From the override settings.ini names, the last, back to Automatic.
            pressUntilFocused(Key.DirectionDown, "PAL (Europe)")
            assertTrue(focusedHas("scph5552.bin (from settings.ini)"))
            stepToTheEnd(Key.DirectionLeft)
            assertTrue(focusedHas("Automatic"))

            pressUntilFocused(Key.DirectionDown, "Fast boot")
            assertShows("The images come from your BIOS folder")
        }
    }

    @Test
    fun controllersWithTwoPadsAndEveryInput() {
        eachConfig({
            var selected by remember { mutableStateOf(LONG_PAD.descriptor) }
            ControllersScreen(
                controllers = listOf(PAD, LONG_PAD),
                labels = emptyMap(),
                selected = selected,
                bindings = BINDINGS,
                hotkeys = HOTKEYS,
                bindingsFor = LONG_PAD.descriptor,
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = { selected = it },
                onCapture = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onReset = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertShows("Back, the Mode")
            assertTrue(focusedHas("Controller"))
            assertFocusedInView()

            // To the other pad and back, its bindings read again meanwhile.
            press(Key.DirectionLeft)
            assertTextsLaidOut()
            assertTrue(focusedHas(PAD.name))
            press(Key.DirectionRight)
            assertTextsLaidOut()
            assertTrue(focusedHas(LONG_PAD.name))

            pressUntilFocused(Key.DirectionDown, "Right Stick Right")
            press(Key.DirectionRight)
            assertTrue(focusedHas("Clear"))
            assertFocusedInView()
            press(Key.DirectionLeft)
            pressUntilFocused(Key.DirectionDown, "Analog mode")
            assertTextsLaidOut()
            pressUntilFocused(Key.DirectionDown, "Reset to defaults")
            assertShows("Every connected controller drives Port 1")
        }
    }

    @Test
    fun controllersWithOnePadAndNone() {
        val pads = mutableStateOf(listOf(PAD))
        eachConfig({
            ControllersScreen(
                controllers = pads.value,
                labels = emptyMap(),
                selected = pads.value.firstOrNull()?.descriptor,
                bindings = if (pads.value.isEmpty()) emptyList() else BINDINGS,
                bindingsFor = pads.value.firstOrNull()?.descriptor,
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = {},
                onCapture = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onReset = {},
                onBack = {},
            )
        }) {
            pads.value = listOf(PAD)
            rule.waitForIdle()
            assertTextsLaidOut()
            assertShows(PAD.name)
            assertTrue(focusedHas(PAD.name))
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Reset to defaults")

            pads.value = emptyList()
            rule.waitForIdle()
            assertTextsLaidOut()
            assertTrue(focusedHas("Connect a controller"))
            assertFocusedInView()
        }
    }

    @Test
    fun aCapturePromptFitsAndStaysInView() {
        val capturing: MutableState<String?> = mutableStateOf(null)
        eachConfig({
            ControllersScreen(
                controllers = listOf(LONG_PAD),
                labels = emptyMap(),
                selected = LONG_PAD.descriptor,
                bindings = BINDINGS,
                bindingsFor = LONG_PAD.descriptor,
                capturing = capturing.value,
                secondsLeft = 5,
                undoFor = null,
                onSelect = {},
                onCapture = { capturing.value = it },
                onCancelCapture = { capturing.value = null },
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onReset = {},
                onBack = {},
            )
        }) {
            // The row that's lowest on screen once Down has scrolled to it: a capture there grows it at the bottom.
            pressUntilFocused(Key.DirectionDown, "Right Stick Down")
            press(Key.Enter)
            assertTextsLaidOut()
            assertTrue(focusedHas("Press the new button for Right Stick Down… Back cancels (5)"))
            assertFocusedInView()
            press(Key.Back)
            assertTrue(capturing.value == null)
        }
    }

    @Test
    fun theLibrarysTopBar() {
        val games =
            (1..3).map { number ->
                GameEntry(
                    path = "content://test/tree/games/document/game-$number.cue",
                    title = "Game $number",
                    serial = "SLUS-000$number",
                    region = "NTSC-U",
                    totalPlayedSeconds = 0,
                    lastPlayedSeconds = 0,
                    coverPath = null,
                )
            }
        eachConfig({
            var grid by remember { mutableStateOf(false) }
            LibraryScreen(
                games = games,
                gridView = grid,
                banners = emptyList(),
                message = null,
                isError = false,
                onToggleView = { grid = !grid },
                onOpenSettings = {},
                onRefresh = {},
                onStartGame = {},
                focusPath = null,
                focusSettings = true,
            )
        }) {
            assertTextsLaidOut()
            for (text in listOf("Library", "Grid", "Settings", "Refresh")) {
                rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
            }
            assertTrue(focusedHas("Settings"))
            val screen = screen()
            assertFocusedInView(screen)
            press(Key.DirectionLeft)
            assertTrue("Left didn't reach Grid", focusedHas("Grid"))
            press(Key.DirectionRight)
            press(Key.DirectionRight)
            if (!focusedHas("Refresh")) press(Key.DirectionDown)
            assertTrue("Refresh can't be reached", focusedHas("Refresh"))
            assertFocusedInView(screen)
        }
    }
}
