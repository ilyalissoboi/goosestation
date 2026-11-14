// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.onFocusEvent

/**
 * Which game has focus, by path, and which one is to take it once it's composed: [pending], until anything in the
 * screen takes the focus, that game or anything else. A game scrolled away from before it was composed doesn't take
 * the focus later, when it's scrolled back in.
 */
internal class GameFocus {
    var focused: String? = null
    var pending by mutableStateOf<String?>(null)

    /** Each composed game's requester, by path, to give it the focus back ([refocus]): none once it's left. */
    private val requesters = HashMap<String, FocusRequester>()

    /** How many games' requesters are held: those composed now. */
    val held get() = requesters.size

    /**
     * The game at [path] takes the focus again, a frame from now: after an overlay over the screen has gone, and the
     * focus has fallen where it falls as it does, which [pending] doesn't outlast.
     */
    suspend fun refocus(path: String) {
        withFrameNanos {}
        // Not composed (scrolled away): nothing to focus.
        runCatching { requesters[path]?.requestFocus() }
    }

    /** The composed game at [path] takes the focus now; false if it isn't composed. */
    fun request(path: String): Boolean = runCatching { requesters[path]?.requestFocus() == true }.getOrDefault(false)

    /** For the whole screen, which sees the focus move anywhere in it. */
    val screenModifier = Modifier.onFocusEvent { if (it.hasFocus) pending = null }

    /** For the item showing the game at [path]. */
    @Composable
    fun modifier(path: String): Modifier {
        val requester = remember { FocusRequester() }
        // Held only while the item is composed: one scrolled away has no requester to ask.
        DisposableEffect(path, requester) {
            requesters[path] = requester
            onDispose { requesters.remove(path, requester) }
        }
        if (pending == path) {
            LaunchedEffect(Unit) {
                // Not if something else has taken the focus since the item was composed.
                if (pending == path) {
                    requester.requestFocus()
                    pending = null
                }
            }
        }
        return Modifier.focusRequester(requester).onFocusChanged { if (it.isFocused) focused = path }
    }
}
