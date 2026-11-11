// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * B, and a Back key reaching Compose, call [onBack] on the key-up of a press that began here: one still held from the
 * screen before doesn't go back from this one too. A page takes them before the focused element does ([preview]); a
 * panel, after it.
 */
@Composable
fun Modifier.backKeys(
    preview: Boolean = true,
    onBack: () -> Unit,
): Modifier {
    // The keys whose key-down this has seen, and whose key-up hasn't come yet.
    val down = remember { HashSet<Key>() }
    // The latest onBack: what it does can change between a key's down and up (a choice shown, say).
    val back by rememberUpdatedState(onBack)
    val handle = { event: KeyEvent ->
        val key = event.key
        if (key != Key.Back && key != Key.ButtonB) {
            false
        } else {
            when (event.type) {
                KeyEventType.KeyDown -> down += key
                KeyEventType.KeyUp -> if (down.remove(key)) back()
            }
            true
        }
    }
    return if (preview) onPreviewKeyEvent(handle) else onKeyEvent(handle)
}

/** L1 and R1 call [onSwitch] on their key-up, before the focused element sees them: a panel's or a page's tabs. */
fun Modifier.tabKeys(onSwitch: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.key != Key.ButtonL1 && event.key != Key.ButtonR1) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) onSwitch()
        true
    }
