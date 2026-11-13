// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/**
 * The settings' categories, each a page, in desktop's order, as the settings list them, then Covers (desktop's is a
 * dialog of its own); all but Folders need the core. Each has its [icon] beside its [label].
 */
enum class SettingsEntry(
    val label: String,
    val summary: String,
    val icon: ImageVector,
    val needsCore: Boolean = true,
) {
    Interface("Interface", "Pausing, resume states, backups, updates", GsIcons.window),
    Console("Console", "Region, CPU, CD-ROM speed", GsIcons.console),
    Emulation("Emulation", "Speed, frame pacing, rewind, run-ahead", GsIcons.gauge),
    Bios("BIOS", "Which BIOS each region uses, fast boot", GsIcons.chip),
    Graphics("Graphics", "Renderer, enhancements, display, on-screen display", GsIcons.display),
    PostProcessing("Post-processing", "Shaders over the picture", GsIcons.layers),
    Audio("Audio", "Volume, latency", GsIcons.speaker),
    Controllers("Controllers", "Ports, types, bindings, hotkeys, rumble", GsIcons.gamepad),
    MemoryCards("Memory cards", "Card types, sync, the card editor", GsIcons.memoryCard),
    Achievements("Achievements", "Sign-in, hardcore mode, messages", GsIcons.trophy),
    Advanced("Advanced", "Logging, save states, CD-ROM and CPU tweaks", GsIcons.terminal),
    Folders("Folders", "BIOS, games and data folders", GsIcons.folder, needsCore = false),
    Covers("Covers", "Cover sources and downloads", GsIcons.image),
    ;

    companion object {
        /**
         * The category the settings open on: [wanted] if it can be opened ([coreReady], or it doesn't need the core),
         * else the first that can (Interface, or Folders before the core is ready).
         */
        fun opening(
            wanted: SettingsEntry?,
            coreReady: Boolean,
        ): SettingsEntry {
            fun enabled(entry: SettingsEntry) = coreReady || !entry.needsCore
            return wanted?.takeIf(::enabled) ?: entries.first(::enabled)
        }
    }
}

/** Said, in place of its summary, on an entry that needs the core while it isn't [SettingsScreen]'s `coreReady`. */
const val NEEDS_CORE = "Available once GooseStation has started."

/**
 * The narrowest window with the settings in two panels, at text 1.0×: the categories' rail and its edge, and a page
 * at the 360 dp every layout works at.
 */
val TWO_PANEL_MIN_WIDTH = 600.dp

/**
 * The settings, in the layout the window has room for. From [TWO_PANEL_MIN_WIDTH] wide (times the font scale) it's
 * [SettingsPanels]: the categories down the left, and the open one's [content] beside them. Narrower, it's one or the
 * other: the categories as a list ([SettingsScreen]) while no page is open, else the page alone.
 *
 * [page] is the open page's category, null while none is; [focusEntry], [inPage], [coreReady] and [locked] are as
 * the two layouts take them. In the list, a category opens its page ([onOpen]), which goes back to the list
 * ([onClosePage]). In the panels, a category shows its page beside them ([onShow]), the first as they open, and the
 * focus goes into it ([onEnterPage]) and back. [onLeave] goes back to the library from either. [content] is the open
 * page, given where its Back goes.
 */
@Composable
fun SettingsHub(
    page: SettingsEntry?,
    focusEntry: SettingsEntry?,
    inPage: Boolean,
    coreReady: Boolean,
    locked: Boolean,
    onOpen: (SettingsEntry) -> Unit,
    onShow: (SettingsEntry) -> Unit,
    onEnterPage: () -> Unit,
    onClosePage: () -> Unit,
    onLeave: () -> Unit,
    content: @Composable (onBack: () -> Unit) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPanels = maxWidth >= TWO_PANEL_MIN_WIDTH * LocalDensity.current.fontScale
        when {
            twoPanels -> {
                // Never the categories alone: they open on the page last left, or the first.
                LaunchedEffect(page) { if (page == null) onShow(SettingsEntry.opening(focusEntry, coreReady)) }
                SettingsPanels(
                    current = page,
                    inPage = inPage,
                    coreReady = coreReady,
                    locked = locked,
                    onShow = onShow,
                    onEnterPage = onEnterPage,
                    onBack = onLeave,
                    page = content,
                )
            }
            page == null -> SettingsScreen(focusEntry, coreReady, onOpen = onOpen, onBack = onLeave)
            else -> content(onClosePage)
        }
    }
}

/**
 * The settings' categories as a list, for a window too narrow for [SettingsPanels]: an entry per page, with its icon
 * and a summary, which A, Enter or a tap open ([onOpen]). Until [coreReady] (the core has started, and no game is
 * under way), only Folders can be opened: the others are disabled, and say why. On opening, [focusEntry] has focus,
 * the page just left, or else the first entry that can be opened (Interface, or Folders before the core is ready). B
 * or Back go back to the library ([onBack]).
 */
@Composable
fun SettingsScreen(
    focusEntry: SettingsEntry?,
    coreReady: Boolean,
    onOpen: (SettingsEntry) -> Unit,
    onBack: () -> Unit,
) {
    fun enabled(entry: SettingsEntry) = coreReady || !entry.needsCore

    val requesters = remember { SettingsEntry.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        // The page just left, or else the first entry that can be opened, as every page opens on its first row.
        requesters.getValue(SettingsEntry.opening(focusEntry, coreReady)).requestFocus()
    }

    SettingsPage("Settings", onBack = onBack) {
        SettingsEntry.entries.forEach { entry ->
            NavRow(
                entry.label,
                if (enabled(entry)) entry.summary else NEEDS_CORE,
                onClick = { onOpen(entry) },
                modifier = Modifier.focusRequester(requesters.getValue(entry)),
                enabled = enabled(entry),
                icon = entry.icon,
            )
        }
    }
}
