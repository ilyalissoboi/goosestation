// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton

/**
 * The paused game's prompt, over the game in the same window: **Resume**, focused, then **Quit to library**. B, Back
 * and Mode resume too, on the key-up of a press that began here: the rest of the press that opened the prompt does
 * nothing.
 */
@Composable
fun GamePrompt(
    onResume: () -> Unit,
    onQuit: () -> Unit,
) {
    // Back from the system, and B and Back from the card below, which go through the back handlers, this one first.
    BackHandler(onBack = onResume)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // The keys whose key-down the card has seen, and whose key-up hasn't come yet.
    val down = remember { HashSet<Key>() }
    val resume = remember { FocusRequester() }
    LaunchedEffect(Unit) { resume.requestFocus() }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .onKeyEvent { event ->
                        val key = event.key
                        if (key != Key.Back && key != Key.ButtonB && key != Key.ButtonMode) return@onKeyEvent false
                        when (event.type) {
                            KeyEventType.KeyDown -> down += key
                            KeyEventType.KeyUp ->
                                if (down.remove(key)) {
                                    if (key == Key.ButtonMode) onResume() else dispatcher?.onBackPressed() ?: onResume()
                                }
                        }
                        true
                    }.width(320.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Paused",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
            ActionButton("Resume", onClick = onResume, modifier = Modifier.focusRequester(resume))
            ActionButton("Quit to library", onClick = onQuit)
        }
    }
}
