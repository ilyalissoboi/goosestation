// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** One of a [ChoiceRow]'s choices: [value] as settings.ini has it, [label] as the row shows it. */
data class Choice<T>(
    val value: T,
    val label: String,
    val note: String? = null,
)

/** Which [ChoiceRow] of a [SettingsPage] is open, if any: one at a time. */
private class OpenRow {
    var row by mutableStateOf<Any?>(null)
}

private val LocalOpenRow = staticCompositionLocalOf<OpenRow?> { null }

/**
 * A settings page: [title], then [content] in a scrolling column. B or Back go back ([onBack]), unless a row takes
 * them first, as an open [ChoiceRow] does.
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Before the rows' handlers, which take Back first while they're enabled.
    BackHandler(onBack = onBack)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val openRow = remember { OpenRow() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                // B, and a Back key reaching Compose, go to the back handlers, the innermost enabled one first.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Back && event.key != Key.ButtonB) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyUp) dispatcher?.onBackPressed() ?: onBack()
                    true
                }.verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        CompositionLocalProvider(LocalOpenRow provides openRow) { content() }
    }
}

/**
 * A [SettingsPage] for the moment its settings take to read: [title] and a line saying so. The line holds the focus,
 * with nothing to activate, so B and Back reach the page as on any other ([onBack]).
 */
@Composable
fun SettingsLoadingPage(
    title: String,
    onBack: () -> Unit,
) {
    val holder = remember { FocusRequester() }
    LaunchedEffect(Unit) { holder.requestFocus() }
    SettingsPage(title, onBack = onBack) {
        Text(
            "Reading the settings…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).focusRequester(holder).focusable(),
        )
    }
}

/**
 * A setting with a few [choices]: closed, the [label] and the [selected] choice's label. A, Enter or a tap open it,
 * listing the choices under it with the selected one focused and marked; A on one calls [onSelect] if it's another
 * value, and closes the list. B or Back close it with no change. Either way, the row takes the focus back. Opening it
 * closes any other row of the page. [note] is a line under the row.
 */
@Composable
fun <T> ChoiceRow(
    label: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val openRow = LocalOpenRow.current ?: remember { OpenRow() }
    val key = remember { Any() }
    val open = openRow.row === key
    val header = remember { FocusRequester() }
    val selectedChoice = remember { FocusRequester() }

    fun close() {
        // Before the list goes, so the focus moves straight back to the row.
        header.requestFocus()
        if (openRow.row === key) openRow.row = null
    }

    BackHandler(enabled = open, onBack = ::close)
    DisposableEffect(Unit) { onDispose { if (openRow.row === key) openRow.row = null } }
    // Focusing it brings it into view, scrolling the page.
    LaunchedEffect(open) { if (open) selectedChoice.requestFocus() }

    val current = choices.firstOrNull { it.value == selected }
    Column(modifier.fillMaxWidth()) {
        RowLine(
            label = label,
            value = current?.label ?: selected?.toString() ?: "",
            role = Role.DropdownList,
            modifier = Modifier.focusRequester(header),
            onClick = { if (open) close() else openRow.row = key },
        )
        note?.let { Note(it) }
        if (open) {
            // The selected one, or the first if the value isn't among them, takes the focus.
            val focusIndex = choices.indexOfFirst { it == current }.coerceAtLeast(0)
            Column(Modifier.padding(start = 24.dp)) {
                choices.forEachIndexed { index, choice ->
                    ChoiceLine(
                        choice = choice,
                        checked = choice == current,
                        modifier = if (index == focusIndex) Modifier.focusRequester(selectedChoice) else Modifier,
                        onClick = {
                            if (choice.value != selected) onSelect(choice.value)
                            close()
                        },
                    )
                }
            }
        }
    }
}

/** An on/off setting: the [label] and "On" or "Off". A, Enter or a tap call [onToggle]. [note] is a line under it. */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        RowLine(label, if (checked) "On" else "Off", Role.Switch, onClick = { onToggle(!checked) })
        note?.let { Note(it) }
    }
}

/**
 * An entry that opens a page: the [label], and a [summary] of what's in it under it. A disabled one is dimmed, and
 * can't be focused or opened.
 */
@Composable
fun NavRow(
    label: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.5f
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface.copy(alpha = alpha))
        summary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = alpha))
        }
    }
}

/** A row's focusable line: [label] on the left, [value] on the right. */
@Composable
private fun RowLine(
    label: String,
    value: String,
    role: Role,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(role = role, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.primary)
    }
}

/** One choice in an open [ChoiceRow]: its label and note, and a mark when it's the one [checked]. */
@Composable
private fun ChoiceLine(
    choice: Choice<*>,
    checked: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .focusRing(role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            choice.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
        if (checked) Text("✓", style = MaterialTheme.typography.titleMedium, color = colors.primary)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
