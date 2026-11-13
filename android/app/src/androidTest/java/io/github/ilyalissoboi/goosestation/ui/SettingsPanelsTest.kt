// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.CyclerRow
import io.github.ilyalissoboi.goosestation.ui.components.OverlayHost
import io.github.ilyalissoboi.goosestation.ui.components.OverlayHostBox
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings in two panels, with a D-pad and by touch, over pages that take the focus as they open, as the app's
 * do; and the layout [SettingsHub] picks for the window's width.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsPanelsTest {
    @get:Rule val rule = createComposeRule()

    // What the app keeps: the open page, and whether the focus is in it.
    private var current by mutableStateOf<SettingsEntry?>(SettingsEntry.Interface)
    private var inPage by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    private var speed by mutableIntStateOf(1)
    private val shown = mutableListOf<SettingsEntry>()
    private var backs = 0
    private var subView by mutableStateOf(false)
    private var subViewBacks = 0
    private var dispatcher: OnBackPressedDispatcher? = null

    // The system's Back, which goes to the newest handler, not to the focus as the keys do.
    private fun systemBack() {
        rule.runOnUiThread { dispatcher!!.onBackPressed() }
        rule.waitForIdle()
    }

    // A page as the app's are: it focuses its first row as it opens, and has a row that takes Left and Right.
    @Composable
    private fun Page(
        entry: SettingsEntry,
        onBack: () -> Unit,
    ) {
        val first = remember { FocusRequester() }
        var asking by remember { mutableStateOf(false) }
        val ask = remember { FocusRequester() }
        val cancel = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }
        // Once a question closes, its button has the focus back, as the app's pages give it.
        var asked by remember { mutableStateOf(false) }
        LaunchedEffect(asking) {
            if (!asking && asked) ask.requestFocus()
            asked = asking
        }
        Box(Modifier.fillMaxSize()) {
            SettingsPage(entry.label, onBack = onBack) {
                SwitchRow("${entry.label} first", false, onToggle = {}, modifier = Modifier.focusRequester(first))
                CyclerRow(
                    "${entry.label} speed",
                    listOf(Choice(0, "Slow"), Choice(1, "Normal"), Choice(2, "Fast")),
                    speed,
                    onSelect = { speed = it },
                )
                ActionButton("Ask", onClick = { asking = true }, modifier = Modifier.focusRequester(ask))
            }
            // A view of the page's own, with its own step back, as a controller's port has.
            if (subView) BackHandler { subViewBacks++ }
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            if (asking) {
                OverlayPanel(onBack = { asking = false }, title = "Ask?") {
                    // From inside it: the host draws it a frame after it is asked for.
                    LaunchedEffect(Unit) { cancel.requestFocus() }
                    ActionButton(
                        "Cancel",
                        onClick = {},
                        modifier = Modifier.focusRequester(cancel),
                        kind = ButtonKind.Ghost,
                    )
                }
            }
        }
    }

    private fun show(coreReady: Boolean = true) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(960.dp, 540.dp))) {
                GooseStationTheme {
                    // As the app has it: overlays draw over the whole window, outside the page.
                    OverlayHostBox(remember { OverlayHost() }) {
                        SettingsPanels(
                            current = current,
                            inPage = inPage,
                            coreReady = coreReady,
                            locked = locked,
                            onShow = {
                                shown += it
                                current = it
                                inPage = false
                            },
                            onEnterPage = { inPage = true },
                            onBack = { backs++ },
                        ) { onBack ->
                            current?.let { Page(it, onBack) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun inRail() = hasAnyAncestor(hasTestTag(SETTINGS_RAIL))

    private fun category(entry: SettingsEntry) = rule.onNode(hasText(entry.label) and inRail())

    private fun assertFocusedCategory(entry: SettingsEntry) {
        category(entry).assert(isFocused())
        category(entry).assertIsSelected()
        // Its page is the one beside the rail.
        rule.onNodeWithText("${entry.label} first").assertExists()
    }

    private fun assertFocusedRow(text: String) {
        rule.onNode(isFocused()).assert(hasText(text) and hasAnyAncestor(hasTestTag(SETTINGS_PAGE)))
    }

    // The page asked for the focus as it opened, and didn't get it: the categories keep it.
    @Test
    fun theCurrentCategoryIsFocusedWithItsPageBesideIt() {
        show()

        assertFocusedCategory(SettingsEntry.Interface)
        category(SettingsEntry.Console).assertIsNotSelected()
        rule.onNodeWithText("Settings").assertExists()
    }

    @Test
    fun downAndUpShowEachCategorysPage() {
        show()

        press(Key.DirectionDown)
        assertFocusedCategory(SettingsEntry.Console)
        rule.onNodeWithText("Interface first").assertDoesNotExist()
        press(Key.DirectionDown)
        assertFocusedCategory(SettingsEntry.Emulation)
        press(Key.DirectionUp)
        assertFocusedCategory(SettingsEntry.Console)

        assertEquals(listOf(SettingsEntry.Console, SettingsEntry.Emulation, SettingsEntry.Console), shown)
    }

    @Test
    fun theCategoriesStopAtTheirEnds() {
        show()

        press(Key.DirectionUp)
        assertFocusedCategory(SettingsEntry.Interface)
        repeat(SettingsEntry.entries.size + 2) { press(Key.DirectionDown) }
        assertFocusedCategory(SettingsEntry.Covers)
        // Left has nowhere to go.
        press(Key.DirectionLeft)
        assertFocusedCategory(SettingsEntry.Covers)
    }

    @Test
    fun aGoesIntoThePageAndBComesBack() {
        show()
        press(Key.DirectionDown)

        press(Key.ButtonA)
        assertFocusedRow("Console first")
        category(SettingsEntry.Console).assertIsSelected()
        press(Key.ButtonB)
        assertFocusedCategory(SettingsEntry.Console)
        assertEquals(0, backs)

        press(Key.ButtonB)
        assertEquals(1, backs)
    }

    @Test
    fun enterAndRightGoIntoThePage() {
        show()

        press(Key.Enter)
        assertFocusedRow("Interface first")
        press(Key.Back)
        assertFocusedCategory(SettingsEntry.Interface)
        press(Key.DirectionRight)
        assertFocusedRow("Interface first")
    }

    @Test
    fun leftComesBackWhereTheRowDoesntTakeIt() {
        show()
        press(Key.DirectionRight)

        // A row that steps its value keeps Left.
        press(Key.DirectionDown)
        assertFocusedRow("Interface speed")
        press(Key.DirectionLeft)
        assertFocusedRow("Interface speed")
        assertEquals(0, speed)

        press(Key.DirectionUp)
        press(Key.DirectionLeft)
        assertFocusedCategory(SettingsEntry.Interface)
    }

    @Test
    fun theDpadStopsAtThePagesEdges() {
        show()
        press(Key.DirectionRight)

        press(Key.DirectionUp)
        assertFocusedRow("Interface first")
        press(Key.DirectionRight)
        assertFocusedRow("Interface first")
        repeat(4) { press(Key.DirectionDown) }
        assertFocusedRow("Ask")
        assertEquals(emptyList<SettingsEntry>(), shown)
    }

    @Test
    fun backFromTheCategoriesGoesBack() {
        show()

        press(Key.Back)

        assertEquals(1, backs)
    }

    @Test
    fun comingBackToAPageTheFocusIsInIt() {
        current = SettingsEntry.MemoryCards
        inPage = true
        show()

        assertFocusedRow("Memory cards first")
        category(SettingsEntry.MemoryCards).assertIsSelected()
    }

    @Test
    fun aTapOnACategoryShowsItsPageAndGoesIntoIt() {
        show()

        // Under the window's bars, the rail may not show it all.
        category(SettingsEntry.Audio).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(listOf(SettingsEntry.Audio), shown)
        assertFocusedRow("Audio first")
        category(SettingsEntry.Audio).assertIsSelected()
        press(Key.ButtonB)
        assertFocusedCategory(SettingsEntry.Audio)
    }

    @Test
    fun aQuestionOverThePageKeepsTheFocus() {
        show()
        press(Key.DirectionRight)
        repeat(2) { press(Key.DirectionDown) }
        press(Key.ButtonA)
        rule.onNode(isFocused()).assert(hasText("Cancel"))

        // Left does not get past it.
        press(Key.DirectionLeft)
        rule.onNode(isFocused()).assert(hasText("Cancel"))
        press(Key.ButtonB)
        // Closed, the focus is back in the page, not on the rail and not nowhere: on the button that asked, or, where
        // the closing panel refused the page its own request, on a row of the page.
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        rule.onNode(isFocused()).assert(hasAnyAncestor(hasTestTag(SETTINGS_PAGE)))
        assertEquals(emptyList<SettingsEntry>(), shown)
    }

    // In the page, the system's Back is the page's: a step back in its own view. Back on the rail, it's the rail's,
    // though the page is still on that view.
    @Test
    fun theSystemsBackIsTheRailsOnceTheFocusIsBackOnIt() {
        show()
        press(Key.DirectionRight)
        subView = true
        rule.waitForIdle()

        systemBack()
        assertEquals(1, subViewBacks)
        assertEquals(0, backs)

        press(Key.DirectionLeft)
        assertFocusedCategory(SettingsEntry.Interface)
        systemBack()
        assertEquals(1, subViewBacks)
        assertEquals(1, backs)

        // Gone into again, it's the page's once more.
        press(Key.DirectionRight)
        systemBack()
        assertEquals(2, subViewBacks)
        assertEquals(1, backs)
    }

    @Test
    fun withoutTheCoreOnlyFoldersCanBePicked() {
        current = SettingsEntry.Folders
        show(coreReady = false)

        assertFocusedCategory(SettingsEntry.Folders)
        rule.onNodeWithText(NEEDS_CORE_RAIL).assertExists()
        for (entry in listOf(SettingsEntry.Interface, SettingsEntry.Advanced, SettingsEntry.Covers)) {
            category(entry).assertIsNotEnabled()
            category(entry).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
        }
        press(Key.DirectionUp)
        press(Key.DirectionDown)

        assertFocusedCategory(SettingsEntry.Folders)
        assertEquals(emptyList<SettingsEntry>(), shown)
    }

    @Test
    fun lockedTheFocusGoesIntoThePageAndStays() {
        current = SettingsEntry.Folders
        show()
        assertFocusedCategory(SettingsEntry.Folders)

        locked = true
        rule.waitForIdle()

        assertFocusedRow("Folders first")
        category(SettingsEntry.Folders).assertIsNotEnabled()
        press(Key.ButtonB)
        assertFocusedRow("Folders first")
        assertEquals(0, backs)
    }

    // The layout the window has room for.

    private fun showHub(
        size: DpSize,
        page: SettingsEntry?,
    ) {
        current = page
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                GooseStationTheme {
                    SettingsHub(
                        page = current,
                        focusEntry = SettingsEntry.Audio,
                        inPage = inPage,
                        coreReady = true,
                        locked = false,
                        onOpen = {
                            current = it
                            inPage = true
                        },
                        onShow = {
                            shown += it
                            current = it
                            inPage = false
                        },
                        onEnterPage = { inPage = true },
                        onClosePage = { current = null },
                        onLeave = { backs++ },
                    ) { onBack ->
                        current?.let { Page(it, onBack) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aWideWindowOpensThePanelsOnThePageLastLeft() {
        showHub(DpSize(640.dp, 360.dp), page = null)

        assertEquals(listOf(SettingsEntry.Audio), shown)
        assertFocusedCategory(SettingsEntry.Audio)
        rule.onNodeWithTag(SETTINGS_RAIL).assertExists()
    }

    @Test
    fun aNarrowWindowListsTheCategoriesThenShowsThePageAlone() {
        showHub(DpSize(411.dp, 891.dp), page = null)

        rule.onNodeWithTag(SETTINGS_RAIL).assertDoesNotExist()
        rule.onNode(isFocused()).assert(hasText("Audio") and hasText("Volume, latency"))
        press(Key.Enter)
        rule.onNode(isFocused()).assert(hasText("Audio first"))
        rule.onNodeWithText("Volume, latency").assertDoesNotExist()
        press(Key.ButtonB)
        rule.onNode(isFocused()).assert(hasText("Audio") and hasText("Volume, latency"))
        assertEquals(emptyList<SettingsEntry>(), shown)
    }
}
