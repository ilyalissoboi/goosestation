// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.OverlayPanel
import io.github.ilyalissoboi.goosestation.ui.components.focusStop
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.update.Changes

// Loading, loaded, or (null changes) couldn't load.
private sealed interface WhatsNew {
    data object Loading : WhatsNew

    data class Loaded(
        val changes: Changes?,
    ) : WhatsNew
}

/**
 * What's new in [version]: the commits since this build, from [load], each a line of its own with its author under it,
 * focusable so the D-pad scrolls through them (the first takes the focus once they're in), then "And 12 more." for
 * those past GitHub's list. While loading it says so; when they couldn't be loaded, it says that, and Close has the
 * focus. B, Back and Close call [onClose].
 */
@Composable
fun WhatsNewPanel(
    version: String,
    load: suspend () -> Changes?,
    onClose: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    var shown by remember { mutableStateOf<WhatsNew>(WhatsNew.Loading) }
    val first = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    LaunchedEffect(Unit) { shown = WhatsNew.Loaded(load()) }
    val changes = (shown as? WhatsNew.Loaded)?.changes

    OverlayPanel(onBack = onClose, title = "What's new in $version") {
        LaunchedEffect(shown) {
            when {
                shown == WhatsNew.Loading -> close.requestFocus()
                changes != null && changes.commits.isNotEmpty() -> first.requestFocus()
                else -> close.requestFocus()
            }
        }
        Text("What's new in $version", style = MaterialTheme.typography.titleLarge, color = colors.ink)
        when {
            shown == WhatsNew.Loading ->
                Text("Loading the changes…", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
            changes == null ->
                Notice(NoticeStatus.Danger) {
                    Text(
                        "Couldn't load the changes. Check your network connection.",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                    )
                }
            else -> {
                changes.commits.forEachIndexed { index, change ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .focusStop()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(change.title, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                        Text(change.author, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                    }
                }
                val more = changes.total - changes.commits.size
                if (more > 0) {
                    Text("And $more more.", style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
                }
            }
        }
        ActionButton("Close", onClick = onClose, kind = ButtonKind.Ghost, modifier = Modifier.focusRequester(close))
    }
}
