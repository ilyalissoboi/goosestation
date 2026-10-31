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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ilyalissoboi.goosestation.app.OsdMessage
import io.github.ilyalissoboi.goosestation.core.GameEntry
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * The running game: a full-screen surface the core draws to, with the system bars hidden and the screen kept on. Show it
 * only once [NativeLibrary.initialize] has succeeded, since the core ignores a surface before then. Back opens the
 * game's prompt ([onOpenPrompt]), drawn over the surface while [promptOpen], which then takes Back itself; it
 * names the [game], if it's known. The [osd] messages and, while [fastForward], its marker show over the game, under the
 * prompt; [onOsdExpire] runs at [osdExpiry].
 */
@Composable
fun GameScreen(
    promptOpen: Boolean,
    onOpenPrompt: () -> Unit,
    onResume: () -> Unit,
    onQuit: () -> Unit,
    game: GameEntry? = null,
    osd: List<OsdMessage> = emptyList(),
    fastForward: Boolean = false,
    osdExpiry: Long? = null,
    onOsdExpire: () -> Unit = {},
) {
    BackHandler(enabled = !promptOpen, onBack = onOpenPrompt)

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
                    holder.addCallback(SurfaceCallback(context.resources.displayMetrics.density))
                }
            },
        )
        OsdLayer(osd, fastForward, osdExpiry, onOsdExpire)
        if (promptOpen) GamePrompt(onResume = onResume, onQuit = onQuit, game = game)
    }
}

// Each call waits until the core has finished with the previous surface, so a destroyed one is never drawn to.
private class SurfaceCallback(
    private val scale: Float,
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
        NativeLibrary.surfaceChanged(holder.surface, width, height, scale)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeLibrary.surfaceChanged(null, 0, 0, 0.0f)
    }
}
