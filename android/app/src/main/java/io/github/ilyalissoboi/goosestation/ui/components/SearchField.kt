// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.ui.theme.GsShapes

/** The field's accessible name, and its placeholder where there's room for it. */
private const val SEARCH_NAME = "Search your games"

/** How wide the field must be for [SEARCH_NAME] as its placeholder rather than "Search". */
private val LONG_PLACEHOLDER_WIDTH = 200.dp

/** Moves the focus to a [SearchField] from outside it: Select in the library, or a screen giving it back. */
@Stable
class SearchFocus {
    internal val requester = FocusRequester()

    /** Each [focusAndType] asks the field to open the keyboard. */
    internal var typeRequests by mutableIntStateOf(0)
        private set

    /** Focuses the field, as the D-pad would: no keyboard. */
    fun focus() {
        requester.requestFocus()
    }

    /** Focuses the field and opens Android's keyboard, for a quick search. */
    fun focusAndType() {
        requester.requestFocus()
        typeRequests++
    }
}

@Composable
fun rememberSearchFocus(): SearchFocus = remember { SearchFocus() }

/**
 * A search box over [state]: a `sunken` well with a `line-strong` edge, the `search` icon, a placeholder, and a ✕ that
 * clears the text while there's any (a 48 dp tap target and a button to a screen reader, not a focus stop: B clears by
 * controller). Focus doesn't open the keyboard, so a D-pad passes through; A, the D-pad centre, a tap or
 * [SearchFocus.focusAndType] do. Up leaves it, Down calls [onDown]. The keyboard's action is Search, which closes it;
 * [onKeyboardClosed] runs whenever the keyboard closes, by that or by Back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchField(
    state: TextFieldState,
    focus: SearchFocus,
    modifier: Modifier = Modifier,
    onKeyboardClosed: () -> Unit = {},
    onDown: () -> Unit = {},
    onFocusChange: (Boolean) -> Unit = {},
) {
    val colors = GooseStationTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val closed by rememberUpdatedState(onKeyboardClosed)
    var focused by remember { mutableStateOf(false) }
    // Asked for by A, a tap or focusAndType, until the focus leaves: Compose's field starts its text session, which the
    // keyboard needs, only for a tap or while it's to show the keyboard on focus.
    var keyboardWanted by remember { mutableStateOf(false) }
    // Open, as far as the field knows: so a close is told once, whether by the Search action or Back.
    var keyboardOpen by remember { mutableStateOf(false) }

    fun openKeyboard() {
        keyboardWanted = true
        keyboardOpen = true
        keyboard?.show()
    }

    fun keyboardClosed() {
        if (!keyboardOpen) return
        keyboardOpen = false
        closed()
    }

    LaunchedEffect(focus.typeRequests) { if (focus.typeRequests > 0) openKeyboard() }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) keyboardOpen = true else keyboardClosed()
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val placeholder = if (maxWidth >= LONG_PLACEHOLDER_WIDTH) SEARCH_NAME else "Search"
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusIndicator(focused, colors.focus, GsShapes.small)
                    .heightIn(min = 48.dp)
                    .background(colors.sunken, GsShapes.small)
                    .border(1.dp, colors.lineStrong, GsShapes.small)
                    .padding(start = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GsIcons.search, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DockedKeyboard {
                    BasicTextField(
                        state = state,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focus.requester)
                                .onFocusChanged {
                                    if (focused != it.isFocused) onFocusChange(it.isFocused)
                                    focused = it.isFocused
                                    if (!it.isFocused) keyboardWanted = false
                                }.onPreviewKeyEvent { event ->
                                    when (event.key) {
                                        // A single-line field would move its cursor instead.
                                        Key.DirectionUp -> {
                                            if (event.type ==
                                                KeyEventType.KeyDown
                                            ) {
                                                focusManager.moveFocus(FocusDirection.Up)
                                            }
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            if (event.type == KeyEventType.KeyDown) onDown()
                                            true
                                        }
                                        Key.ButtonA, Key.DirectionCenter -> {
                                            if (event.type == KeyEventType.KeyUp) openKeyboard()
                                            true
                                        }
                                        else -> false
                                    }
                                }.semantics { contentDescription = SEARCH_NAME }
                                .padding(vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction = ImeAction.Search,
                                autoCorrectEnabled = false,
                                showKeyboardOnFocus = keyboardWanted,
                            ),
                        onKeyboardAction =
                            KeyboardActionHandler {
                                keyboard?.hide()
                                keyboardOpen = true
                                keyboardClosed()
                            },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        cursorBrush = SolidColor(colors.beak),
                    )
                }
            }
            if (state.text.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .pointerInput(state) { detectTapGestures { state.clearText() } }
                            // A button to a screen reader, which can't tap it; still not a focus stop.
                            .semantics {
                                contentDescription = "Clear search"
                                role = Role.Button
                                onClick {
                                    state.clearText()
                                    true
                                }
                                notAFocusStop = "B clears the search"
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        GsIcons.close,
                        contentDescription = null,
                        tint = colors.inkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Box(Modifier.size(width = 4.dp, height = 48.dp))
            }
        }
    }
}
