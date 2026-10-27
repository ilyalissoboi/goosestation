// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** Said under every button that opens the system folder picker, which may not take a controller. */
const val PICKER_HINT = "Android's folder picker may need touch."

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

/** Draws [model] as a warning, its actions focusable in a row; [firstAction] goes on the first one. */
@Composable
fun Banner(
    model: BannerModel,
    modifier: Modifier = Modifier,
    firstAction: FocusRequester? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.errorContainer, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(model.text, style = MaterialTheme.typography.bodyLarge, color = colors.onErrorContainer)
        model.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer) }
        if (model.actions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.actions.forEachIndexed { index, (label, action) ->
                    ActionButton(
                        text = label,
                        onClick = action,
                        modifier =
                            if (index == 0 && firstAction != null) Modifier.focusRequester(firstAction) else Modifier,
                    )
                }
            }
        }
    }
}
