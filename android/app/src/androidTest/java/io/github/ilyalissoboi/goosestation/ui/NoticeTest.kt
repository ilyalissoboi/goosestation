// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A notice that says one thing: a screen reader hears it as it appears. */
@RunWith(AndroidJUnit4::class)
class NoticeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aOneMessageNoticeIsAPoliteLiveRegion() {
        rule.setContent { GooseStationTheme { Notice(NoticeStatus.Danger, "Couldn't save the card.") } }
        rule.waitForIdle()

        rule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(hasText("Couldn't save the card."))
    }
}
