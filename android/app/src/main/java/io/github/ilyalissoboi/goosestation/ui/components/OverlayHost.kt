// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

/**
 * Where overlays draw: above the screens it holds, which are hidden from accessibility while it shows one, so a screen
 * reader reaches only the overlay. Each [Overlay] is an entry, the newest on top. Main thread only.
 */
class OverlayHost {
    internal val entries = mutableStateListOf<Entry>()

    /** An overlay shows. */
    val showing: Boolean
        get() = entries.isNotEmpty()

    internal class Entry(
        title: String,
        locals: CompositionLocalContext,
        content: @Composable () -> Unit,
    ) {
        var title by mutableStateOf(title)
        var locals by mutableStateOf(locals)
        var content by mutableStateOf(content)
    }
}

/** The [OverlayHost] an [Overlay] draws into: the nearest one. */
val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** [content], the screens, with [host]'s overlays drawn above it; hidden from accessibility while one shows. */
@Composable
fun OverlayHostBox(
    host: OverlayHost,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOverlayHost provides host) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().then(
                    if (host.showing) Modifier.semantics { hideFromAccessibility() } else Modifier,
                ),
            ) {
                content()
            }
            for (entry in host.entries) {
                key(entry) {
                    // As where it was asked for: the theme and the screen's locals, which the host's place lacks.
                    CompositionLocalProvider(entry.locals) { Pane(entry.title, entry.content) }
                }
            }
        }
    }
}

/**
 * [content] over the screen, as a pane titled [title]: drawn into the nearest [OverlayHost], which hides the screen
 * under it from accessibility, or here when there's none. The caller keeps its Back handling where it is, so the order
 * Back closes things in is the order they opened. It may draw a frame after it's asked for: [content] asks for its focus
 * from inside itself.
 */
@Composable
fun Overlay(
    title: String,
    content: @Composable () -> Unit,
) {
    val host = LocalOverlayHost.current
    if (host == null) {
        Pane(title, content)
        return
    }
    val locals = currentCompositionLocalContext
    val entry = remember { OverlayHost.Entry(title, locals, content) }
    SideEffect {
        entry.title = title
        entry.locals = locals
        entry.content = content
    }
    DisposableEffect(host, entry) {
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}

// An overlay as a pane: a screen reader announces it by its title as it opens.
@Composable
private fun Pane(
    title: String,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().semantics { paneTitle = title }) { content() }
}
