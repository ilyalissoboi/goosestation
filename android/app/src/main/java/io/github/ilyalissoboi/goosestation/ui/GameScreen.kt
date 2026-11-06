// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.app.Activity
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ilyalissoboi.goosestation.app.AchievementNotice
import io.github.ilyalissoboi.goosestation.app.AchievementOverlay
import io.github.ilyalissoboi.goosestation.app.GameMenuActions
import io.github.ilyalissoboi.goosestation.app.GameMenuState
import io.github.ilyalissoboi.goosestation.app.OsdMessage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * The running game: a full-screen surface the core draws to, with the system bars hidden and the screen kept on. Show it
 * only once [NativeLibrary.initialize] has succeeded, since the core ignores a surface before then. Back opens the
 * game's menu ([onOpenMenu]), drawn over the surface from [menu] while [menuOpen], which then takes Back itself; its
 * rows call [actions], and it names the [game], if it's known. The [osd] messages and, while [fastForward], its marker
 * show over the game, under the menu; [onOsdExpire] runs at [osdExpiry]. While the menu's [GameMenuState.settings],
 * the game's own settings ([settings]) take the menu's place, over the game, which stays paused under them; Mode
 * resumes from them as from the menu.
 */
@Composable
fun GameScreen(
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    menu: GameMenuState,
    actions: GameMenuActions,
    game: GameEntry? = null,
    osd: List<OsdMessage> = emptyList(),
    fastForward: Boolean = false,
    osdExpiry: Long? = null,
    onOsdExpire: () -> Unit = {},
    badge: (String) -> ImageBitmap? = { null },
    card: AchievementNotice.Card? = null,
    indicators: List<AchievementOverlay.Indicator> = emptyList(),
    overlayExpiry: Long? = null,
    onOverlayExpire: () -> Unit = {},
    settings: @Composable () -> Unit = {},
) {
    BackHandler(enabled = !menuOpen, onBack = onOpenMenu)

    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    holder.addCallback(
                        SurfaceCallback(context.resources.displayMetrics.density) {
                            display?.refreshRate
                                ?: 0f
                        },
                    )
                }
            },
        )
        OsdLayer(osd, fastForward, osdExpiry, onOsdExpire, card, indicators, overlayExpiry, onOverlayExpire, badge)
        when {
            menuOpen && menu.settings ->
                Box(
                    Modifier.fillMaxSize().onKeyEvent { event ->
                        // Mode resumes from anywhere in the menu, its settings too, on the key-up.
                        if (event.key != Key.ButtonMode) return@onKeyEvent false
                        if (event.type == KeyEventType.KeyUp) actions.resume()
                        true
                    },
                ) { settings() }
            menuOpen -> GameMenu(menu, actions, game, badge = badge)
        }
    }
}

// Each call waits until the core has finished with the previous surface, so a destroyed one is never drawn to.
private class SurfaceCallback(
    private val scale: Float,
    // The screen's, as the view shows on it now.
    private val refreshRate: () -> Float,
) : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) {
        // surfaceChanged() always follows, with the size.
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        NativeLibrary.surfaceChanged(holder.surface, width, height, scale, refreshRate())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeLibrary.surfaceChanged(null, 0, 0, 0.0f, 0.0f)
    }
}
