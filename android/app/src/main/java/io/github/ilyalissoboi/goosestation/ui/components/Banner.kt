// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** Said under every button that opens the system folder picker, which may not take a controller. */
const val PICKER_HINT = "Android's folder picker may need touch."

/** What a [Notice] reports, which sets its tint and its icon's shape, so it reads without colour too. */
enum class NoticeStatus {
    /** Something under way: a square, or the sync arrows. */
    Info,

    /** Done and safe: a ticked square. */
    Success,

    /** Needs a look, nothing lost: a triangle. */
    Warning,

    /** Failed: an octagon. */
    Danger,
}

/**
 * A message in the page, tinted by its [status], with its status icon ([icon] in its place, if given): [content], and
 * [actions] under it.
 */
@Composable
fun Notice(
    status: NoticeStatus,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GooseStationTheme.colors
    val (ground, accent, statusIcon) =
        when (status) {
            NoticeStatus.Info -> Triple(colors.infoSoft, colors.info, GsIcons.info)
            NoticeStatus.Success -> Triple(colors.successSoft, colors.success, GsIcons.success)
            NoticeStatus.Warning -> Triple(colors.warningSoft, colors.warning, GsIcons.warning)
            NoticeStatus.Danger -> Triple(colors.dangerSoft, colors.danger, GsIcons.error)
        }
    Row(
        modifier = modifier.fillMaxWidth().background(ground, GsShapes.medium).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon ?: statusIcon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
            actions?.let {
                // The focus ring needs its room inside the notice.
                Row(Modifier.padding(top = 8.dp)) { it() }
            }
        }
    }
}

/**
 * A prompt drawn in the screen: [text], and up to two actions, each a label and what it does. [note] is a line under
 * the text, such as that the system folder picker may need touch.
 */
data class BannerModel(
    val text: String,
    val actions: List<Pair<String, () -> Unit>>,
    val note: String? = null,
) {
    init {
        require(actions.size <= 2) { "A banner has at most two actions." }
    }
}

/** Draws [model] as a warning [Notice], its actions focusable in a row; [firstAction] goes on the first one. */
@Composable
fun Banner(
    model: BannerModel,
    modifier: Modifier = Modifier,
    firstAction: FocusRequester? = null,
) {
    val colors = GooseStationTheme.colors
    Notice(
        status = NoticeStatus.Warning,
        modifier = modifier,
        actions =
            if (model.actions.isEmpty()) {
                null
            } else {
                {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        model.actions.forEachIndexed { index, (label, action) ->
                            ActionButton(
                                text = label,
                                onClick = action,
                                modifier =
                                    if (index == 0 && firstAction != null) {
                                        Modifier.focusRequester(firstAction)
                                    } else {
                                        Modifier
                                    },
                            )
                        }
                    }
                }
            },
    ) {
        Text(model.text, style = MaterialTheme.typography.titleMedium, color = colors.ink)
        model.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink) }
    }
}
