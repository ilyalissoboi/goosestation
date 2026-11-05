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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import io.github.ilyalissoboi.goosestation.app.AchievementsPanelState
import io.github.ilyalissoboi.goosestation.app.CheevoTab
import io.github.ilyalissoboi.goosestation.app.CodePanel
import io.github.ilyalissoboi.goosestation.app.DiscChoice
import io.github.ilyalissoboi.goosestation.app.EditorQuestion
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.MemcardEditorWords
import io.github.ilyalissoboi.goosestation.app.PatchCodeGroup
import io.github.ilyalissoboi.goosestation.app.PatchCodeRow
import io.github.ilyalissoboi.goosestation.app.ResumeOffer
import io.github.ilyalissoboi.goosestation.app.SavePanel
import io.github.ilyalissoboi.goosestation.app.SaveSlots
import io.github.ilyalissoboi.goosestation.app.SlotEntry
import io.github.ilyalissoboi.goosestation.app.SlotMode
import io.github.ilyalissoboi.goosestation.app.SlotPanel
import io.github.ilyalissoboi.goosestation.app.SyncBadge
import io.github.ilyalissoboi.goosestation.core.Achievement
import io.github.ilyalissoboi.goosestation.core.AchievementSection
import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.GameAchievements
import io.github.ilyalissoboi.goosestation.core.GameDisc
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.Leaderboard
import io.github.ilyalissoboi.goosestation.core.LeaderboardEntry
import io.github.ilyalissoboi.goosestation.core.LeaderboardPage
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import io.github.ilyalissoboi.goosestation.core.SyncVersion
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.AchievementsSettings
import io.github.ilyalissoboi.goosestation.settings.AudioSettings
import io.github.ilyalissoboi.goosestation.settings.BiosRegion
import io.github.ilyalissoboi.goosestation.settings.ConsoleSettings
import io.github.ilyalissoboi.goosestation.settings.EmulationSettings
import io.github.ilyalissoboi.goosestation.settings.GraphicsSettings
import io.github.ilyalissoboi.goosestation.settings.InterfaceSettings
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.ui.components.Choice
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
@LargeTest
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
                    "General",
                ),
                PadBinding(
                    "Screenshot",
                    "Save Screenshot",
                    PadBindingKind.Button,
                    "Button109 & Android-1/Button188",
                    "General",
                ),
                PadBinding(
                    "IncreaseResolutionScale",
                    "Increase Resolution Scale",
                    PadBindingKind.Button,
                    null,
                    "Graphics",
                ),
                PadBinding(
                    "SaveStateAndSelectNextSlot",
                    "Save State and Select Next Slot",
                    PadBindingKind.Button,
                    null,
                    "Save States",
                ),
                PadBinding("ToggleAnalogMode", "Toggle Analog Mode", PadBindingKind.Button, null, "Controller"),
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

        /** The first choice of each Graphics row, the end the list's focus can go up to. */
        val CHOICE_FIRST =
            mapOf(
                "Renderer" to "Automatic",
                "Internal resolution" to "Match the screen",
                "Aspect ratio" to "Auto (Game Native)",
            )

        /** Values from settings.ini that the Graphics page doesn't offer, so each list ends with one. */
        val GRAPHICS_FROM_FILE =
            pageValues(
                GraphicsSettings.all,
                "GPU/Renderer" to "Metal",
                "GPU/ResolutionScale" to "12",
                "Display/AspectRatio" to "21:9",
                "Display/ShowFPS" to "true",
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

    /** The settings page's scrolling column, which the page's content shows through (not a text field's scroll). */
    private fun viewport() =
        bounds(
            rule.onNode(
                hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ),
        )

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
        val node = focused.fetchSemanticsNode()
        val box = node.boxInRoot()
        assertTrue(
            "the focused ${node.mergedText()} at ${box.describe()} isn't all in the viewport ${viewport.describe()}",
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
    ) = pressUntil(key, "\"$text\"", limit) { focusedHas(text) }

    /**
     * Presses [key] until the Controllers page's row for the input [name] has the focus, checking each one it reaches
     * is in view: found by its glyph's description with [glyphs], else by its words.
     */
    private fun pressUntilInput(
        key: Key,
        name: String,
        glyphs: Boolean,
    ) = pressUntil(key, name) { if (glyphs) focusedDescribedAs(name) else focusedHas(name) }

    private fun pressUntil(
        key: Key,
        what: String,
        limit: Int = 60,
        focused: () -> Boolean,
    ) {
        repeat(limit) {
            if (focused()) return
            press(key)
            assertFocusedInView()
        }
        if (!focused()) fail("$key never focused $what")
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
        // One fetch, so one wait for the screen to settle: the screen's box and every text's come from these nodes.
        val fetched =
            rule
                .onAllNodes(hasText("", substring = true) or hasTestTag(SCREEN), useUnmergedTree = true)
                .fetchSemanticsNodes()
        val screen = fetched.single { it.config.getOrNull(SemanticsProperties.TestTag) == SCREEN }.boxInRoot()
        val boxes =
            fetched
                .filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
                .map { node -> node to node.boxInRoot(clipped = inView) }
                .filter { (_, box) -> !inView || (box.right > box.left && box.bottom > box.top) }
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

    /**
     * The node's box in the root, as [getUnclippedBoundsInRoot] gives it, or with [clipped], as [getBoundsInRoot]: the
     * part its scrolling containers show. Read from the node, without another fetch.
     */
    private fun SemanticsNode.boxInRoot(clipped: Boolean = false): DpRect {
        val rect =
            when {
                clipped -> boundsInRoot
                layoutInfo.isPlaced -> Rect(positionInRoot, size.toSize())
                else -> Rect(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            }
        return with(layoutInfo.density) {
            DpRect(rect.left.toDp(), rect.top.toDp(), rect.right.toDp(), rect.bottom.toDp())
        }
    }

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

            // Load, with the disc on each row and another disc's saves after the slots.
            val saved = entries.filter { it.savedAt != null }.map { it.copy(disc = 1) }
            val others = listOf(3, 9).map { SlotEntry(it, now.toEpochSecond() - 600, owner = "SLPS-02021", disc = 2) }
            // Closed first, as the menu does between panels.
            menu.value = menu.value.copy(slots = null)
            rule.waitForIdle()
            menu.value = menu.value.copy(slots = SlotPanel(SlotMode.Load, saved, focus = 1, others = others))
            rule.waitForIdle()
            assertTextsLaidOut(inView = true)
            pressUntilFocusedInView(Key.DirectionDown, "Slot 9")
            assertTextsLaidOut(inView = true)

            // Change disc, with long disc titles.
            val discs =
                (1..4).map {
                    GameDisc("content://g/$it.chd", "SLPS-0202$it", "${LONG_GAME.title} (Disc $it)", it)
                }
            menu.value = menu.value.copy(slots = null)
            rule.waitForIdle()
            menu.value = menu.value.copy(disc = 1, discCount = 4, discs = DiscChoice(discs, current = 1))
            rule.waitForIdle()
            assertTextsLaidOut(inView = true)
            assertTrue("Disc 1 is focused, not ${focusedText()}", focusedHas("Disc 1"))
            pressUntilFocusedInView(Key.DirectionDown, "Disc 4")
            assertTextsLaidOut(inView = true)
            menu.value = menu.value.copy(discs = null)
            rule.waitForIdle()

            // Patch codes in groups, with names and notes as long as the database's longest (the 99th percentile of
            // its names, 80 characters; a note of 215, cut at two lines).
            val rows =
                (0..7).map {
                    PatchCodeRow(
                        it,
                        "Infinite health for every party member in battle and in the field, slot $it of 8",
                        if (it % 2 == 0) {
                            "Turn it off when you're out of battle, or the game may lock up. Don't use it with the " +
                                "maximum money code either: activate one, save the game, then the other, and save " +
                                "again before you go on."
                        } else {
                            null
                        },
                        manual = it == 7,
                        on = it % 2 == 0,
                    )
                }
            val groups = listOf(PatchCodeGroup("Player", rows.take(4)), PatchCodeGroup("Other", rows.drop(4)))
            menu.value = menu.value.copy(codes = CodePanel(groups, "${LONG_GAME.title}.cht"))
            rule.waitForIdle()
            assertTextsLaidOut(inView = true)
            pressUntilFocusedInView(Key.DirectionDown, "slot 7 of 8")
            assertTextsLaidOut(inView = true)
            menu.value = menu.value.copy(codes = null)
            // As the next config's menu opens.
            menu.value = GameMenuState(booted = true, analogMode = true)
        }
    }

    @Test
    fun theAchievementsPanel() {
        val now = ZonedDateTime.of(2026, 10, 31, 14, 5, 0, 0, ZoneId.of("Asia/Tokyo"))
        val game = GameAchievements(GameAchievements.READY, 14, 40, 85, 400, true, true, false)
        val long = "Collect every gem in every world without losing a life, in one sitting"
        val sections =
            listOf(
                AchievementSection(
                    7,
                    "Almost there",
                    arrayOf(Achievement(1, long, long, 25, "", 0, "312/500", 62f)),
                ),
                AchievementSection(
                    1,
                    "Locked",
                    (2..6).map { Achievement(it, "スパイロ $it", long, 10, "", 0, "", 0f) }.toTypedArray(),
                ),
                AchievementSection(
                    2,
                    "Unlocked",
                    arrayOf(
                        Achievement(7, "Sparx Rescue", "Free the first dragon", 5, "", now.toEpochSecond(), "", 0f),
                    ),
                ),
            )
        val state =
            GameMenuState(
                booted = true,
                achievements = game,
                cheevos = AchievementsPanelState(CheevoTab.Achievements, sections),
            )
        eachConfig({ GameMenu(state, NoMenuActions, LONG_GAME, now) }) {
            assertTextsLaidOut(inView = true)
            assertTrue("the first achievement is focused, not ${focusedText()}", focusedHas(long))
            pressUntilFocusedInView(Key.DirectionDown, "Sparx Rescue")
            assertTextsLaidOut(inView = true)
        }
    }

    @Test
    fun anOpenLeaderboard() {
        val game = GameAchievements(GameAchievements.READY, 14, 40, 85, 400, true, true, false)
        val board = Leaderboard(31, "Fastest lap on the longest track in the whole of the championship", "Fastest time")

        fun entry(rank: Int) = LeaderboardEntry(rank, "a-player-with-a-long-name-$rank", "12:34.56")
        val page =
            LeaderboardPage(
                128_400,
                (1..10).map(::entry).toTypedArray(),
                (12_208..12_216).map(::entry).toTypedArray(),
                4,
            )
        val state =
            GameMenuState(
                booted = true,
                achievements = game,
                cheevos =
                    AchievementsPanelState(
                        CheevoTab.Leaderboards,
                        leaderboards = listOf(board),
                        open = board,
                        page = page,
                    ),
            )
        eachConfig({ GameMenu(state, NoMenuActions, LONG_GAME) }) {
            assertTextsLaidOut(inView = true)
            assertTrue("your entry is focused, not ${focusedText()}", focusedHas("12,212"))
            pressUntilFocusedInView(Key.DirectionUp, "1")
            assertTextsLaidOut(inView = true)
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

        override fun pickOther(
            serial: String,
            slot: Int,
        ) {}

        override fun openDiscs() {}

        override fun closeDiscs() {}

        override fun pickDisc(number: Int) {}

        override fun openCodes() {}

        override fun closeCodes() {}

        override fun setCode(
            index: Int,
            on: Boolean,
        ) {}

        override fun applyCode(index: Int) {}

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

        override fun confirmExit() {}

        override fun cancelExit() {}

        override fun replaceResume() {}

        override fun keepResume() {}

        override fun cancelReplaceResume() {}
    }

    /**
     * A settings page at every size: its texts laid out, the focus on [rows]' first on opening, each row reached with
     * Down in order and scrolled into view, then each of [lists]' rows opened, from its first choice to its last.
     */
    private fun settingsPageFits(
        content: @Composable () -> Unit,
        rows: List<String>,
        lists: Map<String, Pair<String, String>> = emptyMap(),
    ) {
        eachConfig(content) {
            assertTextsLaidOut()
            assertTrue("the page opened on ${focusedText()}", focusedHas(rows.first()))
            assertFocusedInView()
            for (row in rows.drop(1)) pressUntilFocused(Key.DirectionDown, row)
            for ((row, ends) in lists) {
                pressUntilFocused(Key.DirectionUp, rows.first())
                pressUntilFocused(Key.DirectionDown, row)
                press(Key.ButtonA)
                assertFocusedInView()
                pressUntilFocused(Key.DirectionUp, ends.first)
                assertTextsLaidOut()
                pressUntilFocused(Key.DirectionDown, ends.second)
                assertTextsLaidOut()
                press(Key.ButtonB)
                assertTrue("B didn't close $row's list: the focus is on ${focusedText()}", focusedHas(row))
            }
            pressUntilFocused(Key.DirectionUp, rows.first())
        }
    }

    @Test
    fun theInterfacePage() =
        settingsPageFits(
            {
                var values by remember { mutableStateOf(pageValues(InterfaceSettings.all)) }
                InterfaceScreen(values, onChange = { values = it }, onBack = {})
            },
            rows =
                listOf(
                    "Pause on start",
                    "Pause when a controller disconnects",
                    "Save state on exit",
                    "Create save state backups",
                ),
        )

    @Test
    fun theConsolePage() =
        settingsPageFits(
            {
                var values by remember {
                    mutableStateOf(
                        pageValues(
                            ConsoleSettings.all,
                            "CPU/OverclockEnable" to "true",
                            "CDROM/ReadSpeedup" to "12",
                        ),
                    )
                }
                ConsoleScreen(values, SampleNames.ALL, onChange = { values = it }, onBack = {})
            },
            rows =
                listOf(
                    "Region",
                    "8 MB RAM",
                    "Disable all enhancements",
                    "Execution mode",
                    "Overclocking",
                    "Overclocking percentage",
                    "Recompiler ICache",
                    "Read speed-up",
                    "Seek speed-up",
                    "Readahead",
                    "Preload disc to RAM",
                    "Apply image patches",
                ),
            lists =
                mapOf(
                    "Region" to ("Auto-Detect" to "NTSC-U/C (US, Canada)"),
                    "Read speed-up" to ("None (double speed)" to "12x (24x speed)"),
                    "Overclocking percentage" to ("50%" to "400%"),
                ),
        )

    @Test
    fun theEmulationPage() =
        settingsPageFits(
            {
                var values by remember {
                    mutableStateOf(
                        pageValues(
                            EmulationSettings.all,
                            "Main/EmulationSpeed" to "1.33",
                            "Display/OptimalFramePacing" to "true",
                            "Display/PreFrameSleep" to "true",
                            "Main/RewindEnable" to "true",
                        ),
                    )
                }
                EmulationScreen(
                    values,
                    RewindUsage(40L * 1_048_576, 20L * 1_048_576),
                    onChange = { values = it },
                    onBack = {},
                )
            },
            rows =
                listOf(
                    "Emulation speed",
                    "Fast-forward speed",
                    "Turbo speed",
                    "VSync",
                    "Sync to host refresh rate",
                    "Optimal frame pacing",
                    "Reduce input latency",
                    "Frame time buffer",
                    "Skip duplicate frames",
                    "Rewind",
                    "Save frequency",
                    "Save slots",
                    "Run-ahead",
                ),
            lists =
                mapOf(
                    "Emulation speed" to ("Unlimited" to "1000%"),
                    "Save slots" to ("10 states" to "1000 states"),
                ),
        )

    @Test
    fun theGraphicsPage() =
        settingsPageFits(
            {
                var values by remember {
                    mutableStateOf(
                        pageValues(
                            GraphicsSettings.all,
                            "GPU/DownsampleMode" to "Box",
                            "GPU/ResolutionScale" to "4",
                            "GPU/PGXPEnable" to "true",
                            "GPU/PGXPDepthBuffer" to "true",
                            "Display/AspectRatio" to "Custom",
                            "TextureReplacements/EnableVRAMWriteReplacements" to "true",
                        ),
                    )
                }
                GraphicsScreen(values, SampleNames.ALL, vulkanFailed = true, onChange = { values = it }, onBack = {})
            },
            rows =
                listOf(
                    "Renderer",
                    "Internal resolution",
                    "Downsampling",
                    "Downsampling scale",
                    "Multisampling",
                    "Threaded presentation",
                    "Texture filtering",
                    "Sprite texture filtering",
                    "True colour",
                    "Debanding",
                    "Widescreen rendering",
                    "Round upscaled texture coordinates",
                    "Line detection",
                    "Force NTSC timings",
                    "Disable interlacing",
                    "FMV chroma smoothing",
                    "Software renderer for readbacks",
                    "Use old MDEC routines",
                    "Geometry correction",
                    "Perspective-correct textures",
                    "Perspective-correct colours",
                    "Culling correction",
                    "Preserve projection precision",
                    "Depth buffer",
                    "Depth clear threshold",
                    "CPU mode",
                    "Vertex cache",
                    "Disable on 2D polygons",
                    "Geometry tolerance",
                    "Aspect ratio",
                    "Custom ratio width",
                    "Custom ratio height",
                    "Deinterlacing",
                    "Crop",
                    "Scaling",
                    "Screen position",
                    "Rotation",
                    "Stretch vertically",
                    "Force 4:3 for FMVs",
                    "Show messages",
                    "Show FPS",
                    "Show speed",
                    "Show resolution",
                    "Show CPU usage",
                    "Show GPU usage",
                    "Show GPU statistics",
                    "Show latency",
                    "Show frame times",
                    "Show controller input",
                    "Statistics size",
                    "Size",
                    "Format",
                    "Quality",
                    "VRAM write replacements",
                    "Preload replacement textures",
                ),
            lists =
                mapOf(
                    "Multisampling" to ("Off" to "8x SSAA"),
                    "Rotation" to ("No Rotation" to "Rotate 90° (Clockwise)"),
                ),
        )

    @Test
    fun theAudioPage() =
        settingsPageFits(
            {
                var values by remember { mutableStateOf(pageValues(AudioSettings.all)) }
                AudioScreen(values, SampleNames.ALL, onChange = { values = it }, onBack = {})
            },
            rows =
                listOf(
                    "Output volume",
                    "Fast-forward volume",
                    "Mute",
                    "Mute CD audio",
                    "Stretch mode",
                    "Buffer size",
                    "Minimal output latency",
                    "Output latency",
                ),
            lists = mapOf("Output volume" to ("0%" to "200%")),
        )

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
            for (i in 0 until 4) assertShows(NEEDS_CORE, i)
        }
    }

    @Test
    fun syncConflictsWithLongNames() {
        val long = SyncVersion("A desktop computer with a rather long name", 1_793_900_000_000, true, 3, false)
        eachConfig({
            SyncConflictsScreen(
                conflicts =
                    SyncConflictsScreenTest.CONFLICTS +
                        SyncConflict(
                            9,
                            "Final Fantasy VII (Disc 1) (BASCUS-94163FF7-S01, Final_Fantasy_VII_(USA)_1)",
                            long,
                            listOf(long, long.copy(device = "Laptop", deleted = true)),
                            1,
                        ),
                zone = java.time.ZoneOffset.UTC,
                onResolve = { _, _ -> },
                onKeepAll = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Keep all from this device")
            assertFocusedInView()
        }
    }

    @Test
    fun theMemoryCardEditorFitsWithLongNames() {
        val long = MemcardSamples.file("The Legend of the Extraordinarily Long-Named Adventure: Director's Cut")
        val saves =
            (1..15).map { n ->
                // 32 characters, as the core shows a full-width title: in plain letters.
                MemcardSamples.save(n, "BASLUS-0${1000 + n}ABCDEFG", "CASTLEVANIA SYMPHONY OF THE NIGHT".take(32))
            }
        val full = MemcardSamples.card(long, saves)
        eachConfig({
            MemcardEditorScreen(
                ui = MemcardSamples.ui(left = full, right = MemcardSamples.SHARED_CARD, changes = 3),
                actions = RecordingEditorActions(),
                onLeave = {},
            )
        }) {
            assertTextsLaidOut(inView = true)
            assertFocusedInEditor()
            reachFormat()
            assertTextsLaidOut(inView = true)
            // Up to the header's buttons, Save or Discard, whichever is nearer.
            repeat(30) {
                if (focusedHas("Save") || focusedHas("Discard")) return@repeat
                press(Key.DirectionUp)
                assertFocusedInEditor()
            }
            assertTrue("didn't reach the header", focusedHas("Save") || focusedHas("Discard"))
            pressUntilFocusedInEditor(Key.DirectionLeft, "Discard")
            // R1 goes to the other card than the last one focused: each card's picker in turn.
            val pickers =
                (1..2).map {
                    press(Key.ButtonR1)
                    assertFocusedInEditor()
                    focusedText()
                }
            assertTrue(
                "R1 focused $pickers",
                pickers.any { "Shared card 1" in it } && pickers.any { "The Legend" in it },
            )
            if (!focusedHas("Shared card 1")) press(Key.ButtonR1)
            reachFormat()
        }
    }

    // The memory card editor's focus is in view: in its page's one scroll.
    private fun assertFocusedInEditor() = assertFocusedInView()

    private fun pressUntilFocusedInEditor(
        key: Key,
        text: String,
    ) {
        repeat(30) {
            if (focusedHas(text)) return
            press(key)
            assertFocusedInEditor()
        }
        if (!focusedHas(text)) fail("$key never focused \"$text\"")
    }

    // Down through a memory card's saves lands on its nearest action; Format is after it, beside or under as they wrap.
    private fun reachFormat() {
        pressUntilFocusedInEditor(Key.DirectionDown, "Import")
        val seen = mutableListOf(focusedText())
        repeat(3) {
            if (!focusedHas("Format")) press(Key.DirectionRight)
            seen += focusedText()
            if (!focusedHas("Format")) press(Key.DirectionDown)
            seen += focusedText()
        }
        assertTrue("didn't reach Format: $seen", focusedHas("Format"))
        assertFocusedInEditor()
    }

    @Test
    fun aMemoryCardQuestionWithTheSyncLineFits() {
        val question =
            EditorQuestion(
                "Erase every save on The Legend of the Extraordinarily Long-Named Adventure: Director's Cut?\n\n" +
                    "Memory card sync is on: your other devices will see these saves removed.",
                "Erase",
                {},
            )
        // The overlay alone: the texts under its scrim aren't to be read through it.
        eachConfig({ MemcardOverlay(MemcardSamples.ui(question = question), RecordingEditorActions(), onLeave = {}) }) {
            assertTextsLaidOut(inView = true)
            assertTrue(focusedHas("Cancel"))
            assertFocusedInView(screen())
            pressUntilFocusedInPanel(Key.DirectionLeft, "Erase")
        }
    }

    @Test
    fun theRenameFormFits() {
        val save = MemcardSamples.save(1, "BASLUS-01001ABCDEFGH", "CASTLEVANIA SYMPHONY OF THE NIGH")
        eachConfig({
            MemcardOverlay(
                MemcardSamples.ui(
                    left = MemcardSamples.card(MemcardSamples.CRASH, listOf(save)),
                    panel = SavePanel(0, 1, renaming = true),
                    renameError = MemcardEditorWords.NAME_INVALID,
                ),
                RecordingEditorActions(),
                onLeave = {},
            )
        }) {
            assertTextsLaidOut(inView = true)
            pressUntilFocusedInPanel(Key.DirectionDown, "Cancel")
        }
    }

    // In a panel over the screen, the focus moves within its own scroll.
    private fun pressUntilFocusedInPanel(
        key: Key,
        text: String,
    ) {
        repeat(12) {
            if (focusedHas(text)) return
            press(key)
            assertFocusedInView(scrollerAroundFocused())
        }
        if (!focusedHas(text)) fail("$key never focused \"$text\"")
    }

    @Test
    fun memoryCardsWithALongAddressAndError() {
        eachConfig({
            MemcardSyncScreen(
                state =
                    MemcardSyncScreenTest.RUNNING.copy(
                        remoteUri =
                            "s3://a-rather-long-bucket-name/emusync/v1?endpoint=https://storage.example.com&region=auto",
                        running = false,
                        statusKind = SyncStatusKind.StartError,
                        startError =
                            "This storage is not encrypted, or was set up by a newer version of emusync. Remove the " +
                                "passphrase to use it, or choose a new folder for encrypted saves.",
                        hasPassphrase = true,
                        conflictCount = 2,
                    ),
                onEnabled = {},
                onText = { _, _ -> },
                onSecret = { _, _ -> },
                onRemovePassphrase = {},
                onSyncNow = {},
                onOpenConflicts = {},
                cardsSummary = "12 cards",
                onManageCards = {},
                onBack = {},
                cards =
                    CardSlots(
                        pageValues(
                            MemoryCardSettings.all,
                            "MemoryCards/Card1Type" to "Shared",
                            "MemoryCards/Card1Path" to "a_card_with_a_rather_long_name_from_desktop.mcd",
                        ),
                        SampleNames.ALL,
                        listOf("b.mcd"),
                        onChange = {},
                    ),
            )
        }) {
            assertTextsLaidOut()
            assertFocusedInView()
            assertShows("Remove the passphrase to use it")
            pressUntilFocused(Key.DirectionDown, "2 conflicts")
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Shared card 1")
            assertTextsLaidOut()
            pressUntilFocused(Key.DirectionDown, "One card for all discs of a game")
        }
    }

    @Test
    fun achievementsSignedOut() {
        eachConfig({
            AchievementsScreen(
                account = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0),
                settings = AchievementsSettings(hardcore = true, messages = true, sounds = true, indicators = true),
                signingIn = false,
                error = "Invalid username/password.",
                expired = false,
                passwordCleared = 0,
                queue = AchievementsQueue(2, arrayOf("swan"), intArrayOf(1)),
                onSignIn = { _, _ -> },
                onSignOut = {},
                onChange = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertFocusedInView()
            assertShows("Invalid username/password.")
            pressUntilFocused(Key.DirectionDown, "Live indicators")
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Leaderboard message time")
            assertTextsLaidOut()
        }
    }

    @Test
    fun achievementsSignedIn() {
        eachConfig({
            AchievementsScreen(
                account = AchievementsAccount(AchievementsAccount.NOT_CONNECTED, "a-rather-long-goose-name", "", 0, 0),
                settings = AchievementsSettings(hardcore = false, messages = true, sounds = true, indicators = true),
                signingIn = false,
                error = null,
                expired = false,
                passwordCleared = 0,
                queue = AchievementsQueue(2, arrayOf("swan"), intArrayOf(1)),
                onSignIn = { _, _ -> },
                onSignOut = {},
                onChange = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("Sign out"))
            assertShows("Not connected")
            pressUntilFocused(Key.DirectionDown, "Live indicators")
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Leaderboard message time")
            assertTextsLaidOut()
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
            var values by remember { mutableStateOf(GRAPHICS_FROM_FILE) }
            GraphicsScreen(values, SampleNames.ALL, vulkanFailed = true, onChange = { values = it }, onBack = {})
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
                "Renderer" to "Metal (from settings.ini)",
                "Internal resolution" to "12x",
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
    fun graphicsWithEachListOpen() {
        eachConfig({
            var values by remember { mutableStateOf(GRAPHICS_FROM_FILE) }
            GraphicsScreen(values, SampleNames.ALL, vulkanFailed = true, onChange = { values = it }, onBack = {})
        }) {
            // Each row's list, from its first choice to its last (the value from settings.ini), every one in view.
            for (
            (row, last) in
            listOf(
                "Renderer" to "Metal (from settings.ini)",
                "Internal resolution" to "12x",
                "Aspect ratio" to "21:9 (from settings.ini)",
            )
            ) {
                pressUntilFocused(Key.DirectionDown, row)
                press(Key.ButtonA)
                assertTextsLaidOut()
                assertFocusedInView()
                pressUntilFocused(Key.DirectionUp, CHOICE_FIRST[row]!!)
                pressUntilFocused(Key.DirectionDown, last)
                assertTextsLaidOut()
                press(Key.ButtonB)
                assertTrue("B didn't close $row's list: the focus is on ${focusedText()}", focusedHas(row))
            }
        }
    }

    @Test
    fun biosWithItsListOpen() {
        val images =
            listOf(
                BiosImage("ps-30j.bin", SCPH5500, "NTSC-J"),
                BiosImage("scph1001.bin", "SCPH-1001, DTLH-3000 (v2.2 12-04-95 A)", "NTSC-U"),
                BiosImage("scph7502.bin", "SCPH-7502 (v4.1 12-16-97 E)", "PAL"),
            )
        val auto = mapOf(BiosRegion.NtscU to SCPH5500, BiosRegion.NtscJ to SCPH5500, BiosRegion.Pal to null)
        eachConfig({
            BiosScreen(
                images = images,
                auto = auto,
                overrides = mapOf(BiosRegion.Pal to "scph5552.bin"),
                fastBoot = true,
                onSetOverride = { _, _ -> },
                onFastBoot = {},
                onBack = {},
            )
        }) {
            assertTrue(focusedHas("NTSC-U (Americas)"))
            press(Key.ButtonA)
            assertTextsLaidOut()
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "SCPH-7502")
            assertTextsLaidOut()
            press(Key.ButtonB)

            // The PAL row's list, from the settings.ini name (its last choice, current) up to Automatic.
            pressUntilFocused(Key.DirectionDown, "PAL (Europe)")
            press(Key.ButtonA)
            assertTrue(focusedHas("scph5552.bin (from settings.ini)"))
            assertFocusedInView()
            pressUntilFocused(Key.DirectionUp, "Automatic")
            assertTextsLaidOut()
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
    fun controllersWithTwoPadsAndEveryInput() = controllersWithTwoPads(glyphs = true)

    // A controller type without glyphs: the inputs' names as words.
    @Test
    fun controllersWithTwoPadsAndEveryInputAsText() = controllersWithTwoPads(glyphs = false)

    private fun controllersWithTwoPads(glyphs: Boolean) {
        eachConfig({
            var selected by remember { mutableStateOf(LONG_PAD.descriptor) }
            PortScreen(
                title = "Port 1",
                controllers = listOf(PAD, LONG_PAD),
                labels = emptyMap(),
                selected = selected,
                bindings = BINDINGS,
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
                glyphs = glyphs,
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("Controller"))
            assertFocusedInView()

            // To the other pad and back, its bindings read again meanwhile.
            press(Key.DirectionLeft)
            assertTextsLaidOut()
            assertTrue(focusedHas(PAD.name))
            press(Key.DirectionRight)
            assertTextsLaidOut()
            assertTrue(focusedHas(LONG_PAD.name))

            pressUntilInput(Key.DirectionDown, "Right Stick Right", glyphs)
            press(Key.DirectionRight)
            assertTrue(focusedHas("Clear"))
            assertFocusedInView()
            press(Key.DirectionLeft)
            pressUntilFocused(Key.DirectionDown, "Reset to defaults")
            assertTextsLaidOut()
            assertShows("Each controller on Port 1 keeps its own bindings.")
        }
    }

    // The hub with two controllers (one with no motors), multitap on and every port.
    @Test
    fun theControllersHub() {
        eachConfig({
            ControllersScreen(
                controllers = listOf(PAD.copy(motors = 2), LONG_PAD),
                labels = emptyMap(),
                ports = mapOf(LONG_PAD.descriptor to 4),
                rumble = emptyMap(),
                offered =
                    (0..7).map {
                        Choice(
                            it,
                            listOf(
                                "Port 1A",
                                "Port 2A",
                                "Port 1B",
                                "Port 1C",
                                "Port 1D",
                                "Port 2B",
                                "Port 2C",
                                "Port 2D",
                            )[it],
                        )
                    },
                portTypes = (0..7).associateWith { if (it == 0) "AnalogController" else "NeGconRumble" },
                typeNames =
                    listOf(
                        Choice("AnalogController", "Analog Controller (DualShock)"),
                        Choice("NeGconRumble", "NeGcon (Rumble)"),
                    ),
                multitap = pageValues(listOf(PortSettings.MULTITAP), "ControllerPorts/MultitapMode" to "BothPorts"),
                multitapNames = listOf(Choice("BothPorts", "Enable on Ports 1 and 2")),
                onPort = { _, _ -> },
                onRumble = { _, _ -> },
                onMultitap = {},
                onOpenPort = {},
                onOpenHotkeys = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas(PAD.name))
            assertFocusedInView()
            pressUntilFocused(Key.DirectionDown, "Port 2D")
            assertTextsLaidOut()
            pressUntilFocused(Key.DirectionDown, "Select + a button")
            assertFocusedInView()
        }
    }

    @Test
    fun theHotkeysPage() {
        eachConfig({
            HotkeysScreen(
                controllers = listOf(PAD, LONG_PAD),
                labels = emptyMap(),
                selected = LONG_PAD.descriptor,
                hotkeys = HOTKEYS,
                hotkeysFor = LONG_PAD.descriptor,
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = {},
                onCaptureHotkey = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("Controller"))
            pressUntilFocused(Key.DirectionDown, "Save State and Select Next Slot")
            assertFocusedInView()
            assertTextsLaidOut()
            pressUntilFocused(Key.DirectionDown, "Toggle Analog Mode")
            assertFocusedInView()
        }
    }

    // The longest Port page: NeGcon's sixteen settings, its bindings and macros, reached with the D-pad.
    @Test
    fun aPortWithNeGconsSettings() {
        eachConfig({
            PortScreen(
                title = "Port 1A",
                controllers = listOf(PAD, LONG_PAD),
                labels = emptyMap(),
                selected = LONG_PAD.descriptor,
                bindings = BINDINGS,
                bindingsFor = LONG_PAD.descriptor,
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
                glyphs = false,
                type = "NeGcon",
                typeNames = listOf(Choice("NeGcon", "NeGcon")),
                typeSettings = NEGCON_SETTINGS,
                values = portPageValues(NEGCON_SETTINGS, "Pad1/Macro4Binds" to "Cross & Circle & Square & Triangle"),
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas("Controller type"))
            pressUntilFocused(Key.DirectionDown, "L Button Scaling")
            assertFocusedInView()
            assertTextsLaidOut()
            pressUntilFocused(Key.DirectionDown, "Macro 4")
            assertFocusedInView()
            assertTextsLaidOut()
        }
    }

    @Test
    fun aMacroPage() {
        eachConfig({
            MacroScreen(
                title = "Macro 2 · Port 2C",
                pad = 6,
                macro = 2,
                controllers = listOf(LONG_PAD),
                labels = emptyMap(),
                selected = LONG_PAD.descriptor,
                triggers = listOf(PadBinding("Macro2", "Macro 2", PadBindingKind.Button, null)),
                triggersFor = LONG_PAD.descriptor,
                inputs = BINDINGS,
                values = pageValues(MacroSettings.settings(6, 2)),
                capturing = null,
                secondsLeft = 0,
                undoFor = null,
                onSelect = {},
                onCapture = {},
                onCancelCapture = {},
                onClear = {},
                onUndo = {},
                onDropUndo = {},
                onChange = {},
                onBack = {},
            )
        }) {
            assertTextsLaidOut()
            assertTrue(focusedHas(LONG_PAD.name))
            pressUntilFocused(Key.DirectionDown, "Press to toggle")
            assertFocusedInView()
            assertTextsLaidOut()
        }
    }

    @Test
    fun controllersWithGlyphsAtLargeText() {
        eachConfig({
            PortScreen(
                title = "Port 1",
                controllers = listOf(PAD),
                labels = emptyMap(),
                selected = PAD.descriptor,
                bindings = BINDINGS,
                bindingsFor = PAD.descriptor,
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
                glyphs = true,
            )
        }) {
            assertTextsLaidOut()
            // Every row, its glyph and binding, reached with the D-pad and all in view, to the last input.
            var steps = 0
            while (!focusedDescribedAs("Right Stick Right")) {
                assertTrue("the D-pad never reached Right Stick Right", steps++ < 60)
                press(Key.DirectionDown)
                assertFocusedInView()
            }
            assertTextsLaidOut()
            pressUntilFocused(Key.DirectionDown, "Reset to defaults")
        }
    }

    private fun focusedDescribedAs(name: String) =
        rule.onAllNodes(isFocused() and hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun controllersWithOnePadAndNone() = onePadThenNone(glyphs = true)

    @Test
    fun controllersWithOnePadAndNoneAsText() = onePadThenNone(glyphs = false)

    private fun onePadThenNone(glyphs: Boolean) {
        val pads = mutableStateOf(listOf(PAD))
        eachConfig({
            PortScreen(
                title = "Port 1",
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
                glyphs = glyphs,
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
            assertTrue(focusedHas("No controller is on Port 1"))
            assertFocusedInView()
        }
    }

    @Test
    fun aCapturePromptFitsAndStaysInView() = capturePrompt(glyphs = true)

    @Test
    fun aCapturePromptFitsAndStaysInViewAsText() = capturePrompt(glyphs = false)

    private fun capturePrompt(glyphs: Boolean) {
        val capturing: MutableState<String?> = mutableStateOf(null)
        eachConfig({
            PortScreen(
                title = "Port 1",
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
                glyphs = glyphs,
            )
        }) {
            // The row that's lowest on screen once Down has scrolled to it: a capture there grows it at the bottom.
            pressUntilInput(Key.DirectionDown, "Right Stick Down", glyphs)
            press(Key.Enter)
            assertTextsLaidOut()
            assertTrue(focusedHas("Press the new button for Right Stick Down… Back cancels (5)"))
            assertFocusedInView()
            press(Key.Back)
            assertTrue(capturing.value == null)
        }
    }

    @Test
    fun theLibrarysTopBarWithTheSyncBadge() {
        eachConfig({
            LibraryScreen(
                games = emptyList(),
                gridView = true,
                banners = emptyList(),
                message = null,
                isError = false,
                onToggleView = {},
                onOpenSettings = {},
                onRefresh = {},
                onStartGame = {},
                focusPath = null,
                focusSettings = true,
                syncBadge = SyncBadge.Conflicts(2),
            )
        }) {
            assertTextsLaidOut()
            rule.onNodeWithText("2 conflicts", useUnmergedTree = true).assertIsDisplayed()
            // Left of Grid, which is left of Settings; on a narrow screen the buttons may wrap under the title.
            repeat(4) { if (!focusedHas("2 conflicts")) press(Key.DirectionLeft) }
            if (!focusedHas("2 conflicts")) press(Key.DirectionUp)
            assertTrue("didn't reach the badge", focusedHas("2 conflicts"))
            assertFocusedInView(screen())
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
