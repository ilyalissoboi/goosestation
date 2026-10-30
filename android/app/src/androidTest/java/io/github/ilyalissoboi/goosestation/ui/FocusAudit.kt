// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.junit.Assert.fail

/**
 * Asserts the screen shown can be used with a controller alone: once it settles, something has the focus, so keys
 * (B among them) reach the screen; and everything that can be clicked, and isn't disabled, can take the focus, so a
 * D-pad can reach it. A failure names what's wrong by its text.
 */
fun ComposeContentTestRule.assertControllerReady() {
    waitForIdle()
    if (onAllNodes(isFocused()).fetchSemanticsNodes().isEmpty()) {
        fail("Nothing has the focus, so a controller's keys reach nothing.")
    }
    val unreachable =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and
                isEnabled() and
                SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus),
        ).fetchSemanticsNodes()
    if (unreachable.isNotEmpty()) {
        fail(
            unreachable.joinToString("\n") {
                "\"${it.text()}\" can be clicked but not focused, so a D-pad can't reach it."
            },
        )
    }
}

private fun SemanticsNode.text(): String {
    val texts = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    return (texts + descriptions).joinToString(" ").ifEmpty { "(no text, node #$id)" }
}
