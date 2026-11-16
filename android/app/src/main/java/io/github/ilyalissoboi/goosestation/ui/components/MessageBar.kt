// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.runtime.Composable
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/** The app's latest error ([isError]) or what it's doing, under a screen's top bar, as a [Notice]. */
@Composable
fun MessageBar(
    message: String,
    isError: Boolean,
) {
    Notice(
        if (isError) NoticeStatus.Danger else NoticeStatus.Info,
        message,
        icon = if (isError) null else GsIcons.sync,
    )
}
