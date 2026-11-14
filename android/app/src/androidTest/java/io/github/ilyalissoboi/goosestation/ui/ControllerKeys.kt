// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey

/**
 * What a Compose test does as a controller would: press keys, and ask where the focus is. A test class implements it
 * with its [rule], and calls these as its own.
 */
@OptIn(ExperimentalTestApi::class)
interface ControllerKeys {
    val rule: ComposeContentTestRule

    /** Presses [key], [times] times, letting the screen settle after each. */
    fun press(
        key: Key,
        times: Int = 1,
    ) {
        repeat(times) {
            rule.onRoot().performKeyInput { pressKey(key) }
            rule.waitForIdle()
        }
    }

    /** Whether what has the focus has [text] in one of its texts. */
    fun focusedHas(text: String) =
        rule.onAllNodes(isFocused() and hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Whether what has the focus has [text] as one of its texts, whole. */
    fun focusedIs(text: String) = rule.onAllNodes(isFocused() and hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Presses [key] until what has the focus has [text] ([focusedHas]), and fails if [limit] presses don't get there. */
    fun walkTo(
        text: String,
        key: Key = Key.DirectionDown,
        limit: Int = 80,
    ) {
        repeat(limit) {
            if (focusedHas(text)) return
            press(key)
        }
        if (!focusedHas(text)) throw AssertionError("$key never reached \"$text\" in $limit presses")
    }
}
