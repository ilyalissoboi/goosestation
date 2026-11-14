// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncVersion
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneOffset

/** The sync conflict screen, driven with keys: a conflict's choices, the newest first in focus, and Keep all. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SyncConflictsScreenTest : ControllerKeys {
    @get:Rule override val rule = createComposeRule()

    private val resolved = mutableListOf<Pair<Long, Int>>()
    private var keptAll = 0
    private var backs = 0

    private fun show(conflicts: List<SyncConflict>) {
        rule.setContent {
            GooseStationTheme {
                SyncConflictsScreen(
                    conflicts = conflicts,
                    zone = ZoneOffset.UTC,
                    onResolve = { id, choice -> resolved += id to choice },
                    onKeepAll = { keptAll++ },
                    onBack = { backs++ },
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theFirstConflictIsFocused() {
        show(CONFLICTS)

        rule
            .onNodeWithText("2 saves changed both on this device and on another. Choose which version to keep.")
            .assertExists()
        rule.onNode(isFocused()).assert(hasText("Castlevania", substring = true))
    }

    @Test
    fun newestIsFocusedWhenOpened() {
        show(CONFLICTS)

        press(Key.ButtonA)

        rule.onNode(isFocused()).assert(hasText("Desktop PC's", substring = true))
    }

    @Test
    fun aPicksBCloses() {
        show(CONFLICTS)

        press(Key.ButtonA)
        press(Key.ButtonB)
        assertEquals(emptyList<Pair<Long, Int>>(), resolved)
        assertEquals(0, backs)
        rule.onNode(isFocused()).assert(hasText("Castlevania", substring = true))

        press(Key.ButtonA)
        press(Key.DirectionUp)
        press(Key.ButtonA)
        assertEquals(listOf(7L to 0), resolved)
    }

    @Test
    fun keepAllAsksFirstWithCancelFocused() {
        show(CONFLICTS)

        walkTo("Keep all from this device", Key.DirectionDown)
        press(Key.ButtonA)
        rule
            .onNodeWithText(
                "Keep this device's version of every save above? The other versions stay in the sync history.",
            ).assertExists()
        rule.onNode(isFocused()).assert(hasText("Cancel"))
        press(Key.ButtonA)
        assertEquals(0, keptAll)

        walkTo("Keep all from this device", Key.DirectionDown)
        press(Key.ButtonA)
        walkTo("Keep all", Key.DirectionLeft)
        press(Key.ButtonA)
        assertEquals(1, keptAll)
    }

    // A choice settles its conflict, which leaves the list: the focus goes on to the next one, never nowhere.
    @Test
    fun aPickMovesTheFocusOn() {
        rule.setContent {
            var conflicts by remember { mutableStateOf(CONFLICTS) }
            GooseStationTheme {
                SyncConflictsScreen(
                    conflicts = conflicts,
                    zone = ZoneOffset.UTC,
                    onResolve = { id, _ -> conflicts = conflicts.filterNot { it.id == id } },
                    onKeepAll = {},
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()

        press(Key.ButtonA)
        press(Key.ButtonA)

        rule.onNode(isFocused()).assert(hasText("BESLUS-01234SAVE", substring = true))
    }

    // Settling the last conflict empties the list: the screen goes back, with no focus request on what went.
    @Test
    fun settlingTheLastGoesBack() {
        rule.setContent {
            var conflicts by remember { mutableStateOf(CONFLICTS.take(1)) }
            GooseStationTheme {
                SyncConflictsScreen(
                    conflicts = conflicts,
                    zone = ZoneOffset.UTC,
                    onResolve = { id, _ -> conflicts = conflicts.filterNot { it.id == id } },
                    onKeepAll = {},
                    onBack = { backs++ },
                )
            }
        }
        rule.waitForIdle()

        press(Key.ButtonA)
        press(Key.ButtonA)

        assertEquals(1, backs)
    }

    @Test
    fun noneLeftGoesBack() {
        show(emptyList())

        assertEquals(1, backs)
    }

    companion object {
        private fun version(
            device: String,
            at: Long,
        ) = SyncVersion(device, at, true, 1, false)

        val CONFLICTS =
            listOf(
                SyncConflict(
                    7,
                    "Castlevania: Symphony of the Night (BASLUS-00067DRACULA, shared_card_1)",
                    version("AYN Thor", 1_793_800_000_000),
                    listOf(version("Desktop PC", 1_793_900_000_000)),
                    1,
                ),
                SyncConflict(
                    8,
                    "BESLUS-01234SAVE (shared_card_2)",
                    null,
                    listOf(version("Desktop PC", 1_793_900_000_000), version("Laptop", 1_793_950_000_000)),
                    2,
                ),
            )
    }
}
