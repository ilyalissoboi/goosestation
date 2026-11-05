// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.SyncActivity
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Memory cards page, driven with keys: its rows, the secrets never shown back, Sync now and the conflicts. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MemcardSyncScreenTest {
    @get:Rule val rule = createComposeRule()

    private val texts = mutableListOf<Pair<String, String>>()
    private val secrets = mutableListOf<Pair<String, String>>()
    private var removed = 0
    private var syncs = 0
    private var conflictsOpened = 0
    private var manageOpened = 0

    private val cardChanges = mutableListOf<SettingValues>()

    private fun show(
        state: MemcardSyncState,
        cardsSummary: String = "3 cards",
        cards: SettingValues? = null,
    ) {
        rule.setContent {
            GooseStationTheme {
                MemcardSyncScreen(
                    state = state,
                    onEnabled = {},
                    onText = { key, value -> texts += key to value },
                    onSecret = { name, value -> secrets += name to value },
                    onRemovePassphrase = { removed++ },
                    onSyncNow = { syncs++ },
                    onOpenConflicts = { conflictsOpened++ },
                    cardsSummary = cardsSummary,
                    onManageCards = { manageOpened++ },
                    onBack = {},
                    cards =
                        cards?.let {
                            CardSlots(it, SampleNames.ALL, listOf("a.mcd", "shared_card_1.mcd"), onChange = { new ->
                                cardChanges +=
                                    new
                            })
                        },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun downTo(text: String) {
        repeat(40) {
            if (rule
                    .onAllNodes(
                        isFocused() and hasText(text, substring = true),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                return
            }
            press(Key.DirectionDown)
        }
        throw AssertionError("Down never reached $text")
    }

    @Test
    fun theCardRowsComeAfterSync() {
        show(RUNNING, cards = pageValues(MemoryCardSettings.all))

        rule.onNode(isFocused()).assert(hasText("Manage cards", substring = true))
        downTo("Card 1 type")
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Card 2 type", substring = true))
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("One card for all discs of a game", substring = true))
        rule.onNodeWithText("Shared card 1").assertDoesNotExist()
    }

    @Test
    fun aSharedSlotPicksItsCardAmongTheFolders() {
        val shared = pageValues(MemoryCardSettings.all, "MemoryCards/Card1Type" to "Shared")
        show(RUNNING, cards = shared)

        downTo("Shared card 1")
        rule.onNode(isFocused()).assert(hasText("(the default)", substring = true))
        press(Key.ButtonA)
        press(Key.DirectionDown)
        press(Key.ButtonA)

        assertEquals(listOf(shared.with(MemoryCardSettings.path(1), "a.mcd")), cardChanges)
    }

    @Test
    fun oneCardPerGameToggles() {
        val start = pageValues(MemoryCardSettings.all)
        show(RUNNING, cards = start)

        downTo("One card for all discs of a game")
        press(Key.ButtonA)

        assertEquals(listOf(start.with(MemoryCardSettings.ONE_CARD_PER_GAME, false)), cardChanges)
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun pressUntilFocused(
        key: Key,
        text: String,
    ) {
        repeat(16) {
            if (rule.onAllNodes(isFocused().and(hasText(text, substring = true))).fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            press(key)
        }
        rule.onNode(isFocused()).assert(hasText(text, substring = true))
    }

    private fun field(label: String) = hasSetTextAction().and(hasContentDescription(label))

    @Test
    fun focusStartsOnManageCards() {
        show(RUNNING)

        rule.onNode(isFocused()).assert(hasText("Manage cards"))
        rule.onNode(isFocused()).assert(hasText("3 cards"))
        press(Key.DirectionDown)
        rule.onNode(isFocused()).assert(hasText("Sync memory cards"))
    }

    @Test
    fun manageCardsOpensTheEditor() {
        show(RUNNING)

        press(Key.ButtonA)

        assertEquals(1, manageOpened)
    }

    @Test
    fun manageCardsSaysHowManyCards() {
        show(RUNNING, cardsSummary = "Counting your cards…")

        rule.onNode(isFocused()).assert(hasText("Counting your cards…"))
    }

    @Test
    fun aSecretIsNeverShownBack() {
        show(RUNNING.copy(hasSecret = true, hasPassphrase = true))
        assertEquals(2, rule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().size)

        pressUntilFocused(Key.DirectionDown, "Secret access key")
        press(Key.ButtonA)

        val secret = rule.onNode(field("Secret access key")).fetchSemanticsNode()
        assertEquals("", secret.config[SemanticsProperties.EditableText].text)
        assertEquals(true, SemanticsProperties.Password in secret.config)
    }

    @Test
    fun notSetSecretsSaySo() {
        show(RUNNING.copy(hasSecret = false, hasPassphrase = false))

        assertEquals(2, rule.onAllNodes(hasText("Not set")).fetchSemanticsNodes().size)
    }

    @Test
    fun aRowOpensItsFieldFocused() {
        show(RUNNING)

        pressUntilFocused(Key.DirectionDown, "Storage")
        press(Key.ButtonA)

        rule.onNode(isFocused()).assert(field("Storage"))
        assertEquals(
            "s3://goose-saves/emusync/v1",
            rule
                .onNode(field("Storage"))
                .fetchSemanticsNode()
                .config[SemanticsProperties.EditableText]
                .text,
        )
    }

    @Test
    fun doneSavesTheField() {
        show(RUNNING)

        pressUntilFocused(Key.DirectionDown, "Storage")
        press(Key.ButtonA)
        rule.onNode(field("Storage")).performTextInput("/other")
        rule.onNode(field("Storage")).performImeAction()

        assertEquals(listOf("RemoteURI" to "s3://goose-saves/emusync/v1/other"), texts)
        // Closed, and the row has the focus back.
        assertEquals(0, rule.onAllNodes(field("Storage")).fetchSemanticsNodes().size)
        rule.onNode(isFocused()).assert(hasText("Storage", substring = true))
    }

    @Test
    fun doneHandsTheSecretOver() {
        show(RUNNING)

        pressUntilFocused(Key.DirectionDown, "Secret access key")
        press(Key.ButtonA)
        rule.onNode(field("Secret access key")).performTextInput("not-a-real-secret")
        rule.onNode(field("Secret access key")).performImeAction()

        assertEquals(listOf("s3-secret-access-key" to "not-a-real-secret"), secrets)
        assertEquals(0, rule.onAllNodes(field("Secret access key")).fetchSemanticsNodes().size)
    }

    @Test
    fun bClosesTheFieldUnchanged() {
        show(RUNNING)

        pressUntilFocused(Key.DirectionDown, "Storage")
        press(Key.ButtonA)
        rule.onNode(field("Storage")).performTextInput("/other")
        press(Key.ButtonB)

        assertEquals(emptyList<Pair<String, String>>(), texts)
        assertEquals(0, rule.onAllNodes(field("Storage")).fetchSemanticsNodes().size)
        rule.onNode(isFocused()).assert(hasText("Storage", substring = true))
    }

    @Test
    fun removePassphraseOnlyWhenSaved() {
        show(RUNNING.copy(hasPassphrase = false))
        assertEquals(0, rule.onAllNodes(hasText("Remove passphrase")).fetchSemanticsNodes().size)
    }

    @Test
    fun removePassphraseRemovesIt() {
        show(RUNNING.copy(hasPassphrase = true))

        pressUntilFocused(Key.DirectionDown, "Remove passphrase")
        press(Key.ButtonA)

        assertEquals(1, removed)
    }

    // Remove passphrase goes once there's none: the focus goes to the passphrase's row, never nowhere.
    @Test
    fun removingThePassphraseKeepsTheFocus() {
        rule.setContent {
            var state by remember { mutableStateOf(RUNNING.copy(hasPassphrase = true)) }
            GooseStationTheme {
                MemcardSyncScreen(
                    state = state,
                    onEnabled = {},
                    onText = { _, _ -> },
                    onSecret = { _, _ -> },
                    onRemovePassphrase = { state = state.copy(hasPassphrase = false) },
                    onSyncNow = {},
                    onOpenConflicts = {},
                    cardsSummary = "3 cards",
                    onManageCards = {},
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()

        pressUntilFocused(Key.DirectionDown, "Remove passphrase")
        press(Key.ButtonA)

        rule.onNode(isFocused()).assert(hasText("Encryption passphrase", substring = true))
    }

    @Test
    fun syncNowSyncs() {
        show(RUNNING)

        pressUntilFocused(Key.DirectionDown, "Sync now")
        press(Key.ButtonA)

        assertEquals(1, syncs)
    }

    @Test
    fun syncNowDisabledWhileApplying() {
        show(RUNNING.copy(applying = true, running = false, statusKind = SyncStatusKind.Applying))

        rule.onNodeWithText("Sync now").assertIsNotEnabled()
        rule.onNodeWithText("Connecting to the storage…").assertExists()
    }

    @Test
    fun conflictsRowOpensTheScreen() {
        show(RUNNING.copy(conflictCount = 2))

        pressUntilFocused(Key.DirectionDown, "2 conflicts")
        press(Key.ButtonA)

        assertEquals(1, conflictsOpened)
    }

    @Test
    fun longErrorsWrap() {
        val error = "The storage rejected the request: " + "a long explanation of what went wrong ".repeat(8).trim()
        show(RUNNING.copy(running = false, statusKind = SyncStatusKind.StartError, startError = error))

        rule.onNodeWithText(error).assertExists()
    }

    @Test
    fun anEmptyDeviceNameShowsThisDevicesName() {
        show(RUNNING.copy(deviceName = ""))

        rule.onNodeWithText("Goose Test Device").assertExists()
    }

    companion object {
        val RUNNING =
            MemcardSyncState(
                SyncStatusKind.Status,
                enabled = true,
                running = true,
                applying = false,
                remoteUri = "s3://goose-saves/emusync/v1",
                accessKeyId = "GOOSEFAKEACCESSKEY",
                deviceName = "Thor",
                defaultDeviceName = "Goose Test Device",
                hasSecret = true,
                hasPassphrase = false,
                passphraseRecorded = false,
                startError = "",
                status = "Synced at 14:05.",
                activity = SyncActivity.Synced,
                conflictCount = 0,
            )
    }
}
