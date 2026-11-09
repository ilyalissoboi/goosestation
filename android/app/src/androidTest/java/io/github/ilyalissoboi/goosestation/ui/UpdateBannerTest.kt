// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.app.UpdateFailure
import io.github.ilyalissoboi.goosestation.app.UpdateState
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.update.Change
import io.github.ilyalissoboi.goosestation.update.Changes
import io.github.ilyalissoboi.goosestation.update.ChannelRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The library's update banner in each state, and What's new, driven with keys. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UpdateBannerTest {
    @get:Rule val rule = createComposeRule()

    private val release = ChannelRelease("preview", "0.1-512-g1a2b3c4", 100_512, "a".repeat(40), "http://x/apk", 4)
    private val calls = mutableListOf<String>()

    private fun model(
        state: UpdateState,
        failureMessage: String? = null,
    ) = UpdateBannerModel(
        state = state,
        ownVersion = "0.1-498-gfffffff",
        failureMessage = failureMessage,
        onUpdate = { calls += "update" },
        onSkip = { calls += "skip" },
        onHide = { calls += "hide" },
        onCancel = { calls += "cancel" },
        onOpenSettings = { calls += "settings" },
        loadChanges = { null },
    )

    private fun show(
        state: UpdateState,
        failureMessage: String? = null,
    ) {
        rule.setContent {
            GooseStationTheme {
                Column {
                    UpdateBanner(model(state, failureMessage), onWhatsNew = { calls += "whatsNew" })
                    ActionButton("Elsewhere", onClick = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focus(text: String) {
        rule.onNodeWithText(text).requestFocus()
        rule.waitForIdle()
    }

    private fun shown(text: String) = rule.onAllNodesWithTextCount(text) > 0

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Test
    fun availableShowsTheVersionAndThreeButtons() {
        show(UpdateState.Available(release))
        assertTrue(shown("GooseStation 0.1-512 is available"))
        assertTrue(shown("You have 0.1-498."))
        for (button in listOf("Update", "What's new", "Skip this version")) assertTrue(button, shown(button))
    }

    @Test
    fun theButtonsCallBack() {
        show(UpdateState.Available(release))
        rule.onNodeWithText("Update").performClick()
        rule.onNodeWithText("What's new").performClick()
        rule.onNodeWithText("Skip this version").performClick()
        assertEquals(listOf("update", "whatsNew", "skip"), calls)
    }

    @Test
    fun bHidesWhenAvailable() {
        show(UpdateState.Available(release))
        focus("Update")
        press(Key.ButtonB)
        assertEquals(listOf("hide"), calls)
    }

    @Test
    fun bOutsideTheBannerDoesntHideIt() {
        show(UpdateState.Available(release))
        focus("Elsewhere")
        press(Key.ButtonB)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun bDoesNothingWhileDownloading() {
        show(UpdateState.Downloading(release, 12L * 1024 * 1024 + 900_000, 48L * 1024 * 1024))
        focus("Cancel")
        press(Key.ButtonB)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun downloadingShowsProgressAndCancel() {
        show(UpdateState.Downloading(release, 12L * 1024 * 1024 + 900_000, 48L * 1024 * 1024))
        assertTrue(shown("Downloading GooseStation 0.1-512…"))
        assertTrue(shown("12 of 48 MB"))
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), calls)
        assertEquals(0, rule.onAllNodes(hasContentDescription("Hide")).fetchSemanticsNodes().size)
    }

    @Test
    fun anUnknownSizeShowsTheMegabytesSoFar() {
        show(UpdateState.Downloading(release, 12L * 1024 * 1024, -1))
        assertTrue(shown("12 MB"))
    }

    @Test
    fun theCrossIsAButtonNamedHide() {
        show(UpdateState.Available(release))
        val cross =
            hasContentDescription("Hide").and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        rule.onNode(cross).performClick()
        assertEquals(listOf("hide"), calls)
        // Not a focus stop: B hides it by controller.
        assertEquals(0, rule.onAllNodes(cross.and(isFocused())).fetchSemanticsNodes().size)
    }

    @Test
    fun checkingSaysSo() {
        show(UpdateState.Checking(release))
        assertTrue(shown("Checking the download…"))
    }

    @Test
    fun failedIsDangerWithTryAgain() {
        show(UpdateState.Failed(release, UpdateFailure.Download))
        assertTrue(shown("The download didn't finish. Check your network connection."))
        rule.onNodeWithText("Try again").performClick()
        assertEquals(listOf("update"), calls)
    }

    @Test
    fun eachFailureSaysWhy() {
        show(
            UpdateState.Failed(release, UpdateFailure.Installer),
            failureMessage = "INSTALL_FAILED_INSUFFICIENT_STORAGE",
        )
        assertTrue(shown("Android didn't install the update."))
        assertTrue(shown(breakable("INSTALL_FAILED_INSUFFICIENT_STORAGE")))
    }

    @Test
    fun aBadApkSaysSo() {
        show(UpdateState.Failed(release, UpdateFailure.NotThisApp))
        assertTrue(shown("The download isn't a GooseStation update, so it wasn't installed."))
    }

    @Test
    fun needsPermissionOpensSettings() {
        show(UpdateState.NeedsPermission(release, refused = false))
        assertTrue(shown("To install the update, allow GooseStation to install apps."))
        assertTrue(shown("Android's settings may need touch."))
        rule.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun refusedPermissionSaysWhere() {
        show(UpdateState.NeedsPermission(release, refused = true))
        assertTrue(shown("Settings → Apps → Special app access → Install unknown apps → GooseStation"))
    }

    @Test
    fun installingSaysTheInstallerMayNeedTouch() {
        show(UpdateState.Installing(release))
        assertTrue(shown("Installing GooseStation 0.1-512…"))
        assertTrue(shown("Android's installer may need touch."))
    }

    @Test
    fun whatsNewListsChangesAndCloses() {
        var closed = 0
        rule.setContent {
            GooseStationTheme {
                WhatsNewPanel(
                    "0.1-512",
                    load = {
                        Changes(
                            listOf(Change("Android: a banner", "Ada"), Change("Fix it", "Grace")),
                            total = 14,
                        )
                    },
                    onClose = { closed++ },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(shown("What's new in 0.1-512"))
        assertTrue(shown("Android: a banner"))
        assertTrue(shown("Grace"))
        assertTrue(shown("And 12 more."))
        assertEquals(
            1,
            rule.onAllNodes(isFocused().and(hasText("Android: a banner", substring = true))).fetchSemanticsNodes().size,
        )
        press(Key.ButtonB)
        assertEquals(1, closed)
    }

    @Test
    fun whatsNewSaysWhenItCouldntLoad() {
        rule.setContent { GooseStationTheme { WhatsNewPanel("0.1-512", load = { null }, onClose = {}) } }
        rule.waitForIdle()
        assertTrue(shown("Couldn't load the changes. Check your network connection."))
        assertEquals(1, rule.onAllNodes(isFocused().and(hasText("Close"))).fetchSemanticsNodes().size)
    }
}
