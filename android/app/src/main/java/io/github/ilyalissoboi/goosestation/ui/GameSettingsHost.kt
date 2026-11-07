// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.ilyalissoboi.goosestation.app.GamePage
import io.github.ilyalissoboi.goosestation.app.GameSettingsPages
import io.github.ilyalissoboi.goosestation.app.ShaderPacksModel
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.GameContext
import io.github.ilyalissoboi.goosestation.ui.components.LocalGameContext
import io.github.ilyalissoboi.goosestation.ui.components.SettingsLoadingPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage

/**
 * The game's own settings that [pages] shows: its hub, or the page open, each row able to use the global setting.
 * [hardcoreActive] says whether its running game is in hardcore mode now. B on a page goes back to the hub, and on the
 * hub, [onBack]. Nothing shows without a game.
 */
@Composable
fun GameSettingsHost(
    pages: GameSettingsPages,
    packs: ShaderPacksModel,
    hardcoreActive: Boolean,
    onBack: () -> Unit,
) {
    val game = pages.game ?: return
    CompositionLocalProvider(LocalGameContext provides GameContext(game.title, pages.running, hardcoreActive)) {
        val page = pages.page
        if (page == null) {
            GameSettingsScreen(
                game = game,
                summary = pages.summary,
                focusEntry = pages.cameFrom,
                asking = pages.asking,
                onOpen = pages::openPage,
                onAsk = pages::ask,
                onConfirm = pages::confirm,
                onCancel = pages::cancelQuestion,
                onBack = onBack,
            )
            return@CompositionLocalProvider
        }
        if (page == GamePage.PostProcessing) {
            PostProcessingScreen(pages.postProcessing, packs, onBack = pages::closePage)
            return@CompositionLocalProvider
        }
        val values = pages.values(page)
        if (values == null) {
            SettingsLoadingPage(page.label, onBack = pages::closePage)
            return@CompositionLocalProvider
        }
        GamePageScreen(pages, page, values) { pages.change(page, it) }
    }
}

/** [page] with the game's [values]: the global page's rows, as a game's. */
@Composable
private fun GamePageScreen(
    pages: GameSettingsPages,
    page: GamePage,
    values: SettingValues,
    onChange: (SettingValues) -> Unit,
) {
    val back = pages::closePage
    when (page) {
        GamePage.Console -> ConsoleScreen(values, pages.names, onChange, back)
        GamePage.Emulation -> EmulationScreen(values, pages.rewindUsage, onChange, back)
        GamePage.Graphics -> GraphicsScreen(values, pages.names, pages.vulkanFailed, onChange, back)
        GamePage.Audio -> AudioScreen(values, pages.names, onChange, back)
        GamePage.Advanced -> AdvancedScreen(values, pages.names, pages.fastmemAvailable, onChange, back)
        GamePage.MemoryCards ->
            SettingsPage(page.label, onBack = back) {
                CardSlotRows(
                    CardSlots(values, pages.names, pages.sharedCards, onChange),
                    oneCardRow = false,
                    first = true,
                )
            }
        GamePage.Achievements -> {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { first.requestFocus() }
            SettingsPage(
                page.label,
                onBack = back,
            ) { AchievementRows(values, onChange, Modifier.focusRequester(first)) }
        }
        // Shown by GameSettingsHost itself, from its own page.
        GamePage.PostProcessing -> {}
        GamePage.Controllers ->
            pages.controllers?.let { GameControllersScreen(values, pages.names, it, onChange, back) }
                ?: SettingsLoadingPage(page.label, onBack = back)
    }
}
