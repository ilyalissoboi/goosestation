// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * A question, in place of what it's about: [text] in [textStyle], then [confirm] in [confirmKind] (it overwrites or
 * destroys, so `Danger` unless it says otherwise), any [extra] answers, then the safe one, [cancel] in [cancelKind],
 * which takes the focus as the question shows and again when [key] changes. The design system's rule for a question,
 * kept in one place: the destructive answer first, the focus on the safe one.
 */
@Composable
fun Question(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancel: String = "Cancel",
    confirmKind: ButtonKind = ButtonKind.Danger,
    cancelKind: ButtonKind = ButtonKind.Ghost,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    key: Any? = Unit,
    extra: @Composable FlowRowScope.() -> Unit = {},
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(key) { cancelFocus.requestFocus() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, style = textStyle, color = GooseStationTheme.colors.ink)
        ButtonRow(Modifier.fillMaxWidth()) {
            ActionButton(confirm, onClick = onConfirm, kind = confirmKind)
            extra()
            ActionButton(cancel, onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus), kind = cancelKind)
        }
    }
}

/** A [Question] over the screen, in an [OverlayPanel], where B and Back give the safe answer. */
@Composable
fun QuestionPanel(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancel: String = "Cancel",
    confirmKind: ButtonKind = ButtonKind.Danger,
    key: Any? = Unit,
) {
    OverlayPanel(onBack = onCancel, title = text) {
        Question(text, confirm, onConfirm, onCancel, cancel = cancel, confirmKind = confirmKind, key = key)
    }
}
