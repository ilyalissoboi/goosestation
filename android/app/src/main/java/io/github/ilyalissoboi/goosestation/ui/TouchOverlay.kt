// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.input.TouchButton
import io.github.ilyalissoboi.goosestation.input.TouchControl
import io.github.ilyalissoboi.goosestation.input.TouchControlId
import io.github.ilyalissoboi.goosestation.input.TouchLayout
import io.github.ilyalissoboi.goosestation.input.TouchPadState
import io.github.ilyalissoboi.goosestation.input.TouchTracker
import io.github.ilyalissoboi.goosestation.ui.components.PsFaceSymbols
import io.github.ilyalissoboi.goosestation.ui.components.TouchGlyphs
import io.github.ilyalissoboi.goosestation.ui.components.TouchGlyphs.drawTouchGlyph
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsColors
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons

/** Where the touch pad's state goes as it changes: the core (NativeLibrary.setTouchPad). */
fun interface TouchSink {
    fun send(state: TouchPadState)
}

/** The press tick. */
fun interface Ticker {
    fun tick()
}

/** The tag of the overlay's box, for tests. */
const val TOUCH_OVERLAY_TAG = "touch-overlay"

/** A short tick on the device's own vibrator ([VibrationEffect.EFFECT_TICK]); nothing on a device without one. */
fun deviceTicker(context: Context): Ticker {
    val vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    if (vibrator == null || !vibrator.hasVibrator()) return Ticker {}
    val tick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    return Ticker { vibrator.vibrate(tick) }
}

/**
 * The on-screen touch pad over the game, filling its box: every control of a [TouchLayout] for the box's size, with
 * [sticks] or not, at the Size setting [size] and [opacity] (both percent). Each finger is tracked on its own
 * ([TouchTracker]); every change of what the pad holds goes to [sink] at once, from the touch event itself (not the
 * next frame), each press ticks [ticker] (none: no tick), and the menu button calls [onMenu]. A cancelled gesture, a
 * new layout (a resize, the sticks coming or going) and leaving the composition let go of everything. Drawn in the
 * touch tokens: [GsColors.touch] at rest, [GsColors.touchPressed] held, [GsColors.touchLine] edges and labels; the
 * face glyphs in their PlayStation colours, one size ([TouchGlyphs]). It's touch only: nothing in it is focusable or
 * heard by screen readers.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TouchOverlay(
    sticks: Boolean,
    size: Int,
    opacity: Int,
    sink: TouchSink,
    ticker: Ticker?,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSink by rememberUpdatedState(sink)
    val currentTicker by rememberUpdatedState(ticker)
    val currentMenu by rememberUpdatedState(onMenu)
    val view = LocalView.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .testTag(TOUCH_OVERLAY_TAG)
            .graphicsLayer { alpha = opacity / 100f },
    ) {
        val layout =
            remember(maxWidth, maxHeight, sticks, size) {
                TouchLayout.of(maxWidth.value, maxHeight.value, sticks, size / 100f)
            }
        val tracker = remember { TouchTracker(layout) }
        var state by remember { mutableStateOf(TouchPadState.RELEASED) }
        val density = LocalDensity.current.density

        // Sends what changed between [before] and now, ticks for its new presses, and opens the menu if asked.
        fun publish(
            before: TouchPadState,
            menu: Boolean = false,
        ) {
            val now = tracker.state
            if (now != before) {
                state = now
                currentSink.send(now)
            }
            if (now.buttons and before.buttons.inv() != 0) currentTicker?.tick()
            if (menu) currentMenu()
        }

        // A new layout lets go of everything (TouchTracker).
        SideEffect {
            if (tracker.layout != layout) {
                val before = tracker.state
                tracker.layout = layout
                publish(before)
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                val before = tracker.state
                tracker.cancel()
                if (before != TouchPadState.RELEASED) currentSink.send(TouchPadState.RELEASED)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInteropFilter { event ->
                    val before = tracker.state
                    var menu = false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                            // Moves come as they happen, not batched for the next frame.
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(event)
                            val i = event.actionIndex
                            tracker.down(
                                event.getPointerId(i).toLong(),
                                event.getX(i) / density,
                                event.getY(i) / density,
                            )
                        }
                        MotionEvent.ACTION_MOVE ->
                            for (i in 0 until event.pointerCount) {
                                tracker.move(
                                    event.getPointerId(i).toLong(),
                                    event.getX(i) / density,
                                    event.getY(i) / density,
                                )
                            }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                            val i = event.actionIndex
                            // Where it lifted, so the menu opens only on a lift over it.
                            tracker.move(
                                event.getPointerId(i).toLong(),
                                event.getX(i) / density,
                                event.getY(i) / density,
                            )
                            menu = tracker.up(event.getPointerId(i).toLong()).menu
                        }
                        MotionEvent.ACTION_CANCEL -> tracker.cancel()
                    }
                    publish(before, menu)
                    true
                },
        ) {
            for (control in layout.controls) TouchControlView(control, layout, state)
        }
    }
}

// One control at its drawn place, tagged touch-<id> (and its face buttons touch-<button>) for tests.
@Composable
private fun TouchControlView(
    control: TouchControl,
    layout: TouchLayout,
    state: TouchPadState,
) {
    val colors = GooseStationTheme.colors
    when (control.id) {
        TouchControlId.DPad -> {
            Placed(control.drawn, "touch-${control.id}") {
                Box(Modifier.fillMaxSize().drawBehind { drawDPad(layout.dpadArm.dp.toPx(), state, colors) })
            }
        }
        TouchControlId.Face -> {
            Placed(control.drawn, "touch-${control.id}") {}
            for (button in FACE) {
                val rect = layout.faceButtonRect(button)
                Placed(rect, "touch-$button") {
                    val local = Rect(Offset.Zero, Size(rect.width, rect.height))
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            drawOctagon(state.has(button), colors)
                            drawTouchGlyph(button.name, local.px(this), PsFaceSymbols.color(button.name, colors))
                        },
                    )
                    val glyph = TouchGlyphs.bounds(button.name, local)
                    Box(
                        Modifier
                            .absoluteOffset(
                                glyph.left.dp,
                                glyph.top.dp,
                            ).size(glyph.width.dp, glyph.height.dp)
                            .testTag("touch-glyph-$button"),
                    )
                }
            }
        }
        TouchControlId.LeftStick, TouchControlId.RightStick -> {
            val left = control.id == TouchControlId.LeftStick
            val deflection = if (left) Offset(state.leftX, state.leftY) else Offset(state.rightX, state.rightY)
            Placed(control.drawn, "touch-${control.id}") {
                Box(Modifier.fillMaxSize().drawBehind { drawStick(deflection, colors) })
            }
        }
        TouchControlId.Menu ->
            Placed(control.drawn, "touch-${control.id}") {
                Box(
                    Modifier.fillMaxSize().drawBehind { drawOctagon(false, colors) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        GsIcons.pause,
                        contentDescription = null,
                        tint = colors.touchLine,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        else -> {
            val button = LABELLED.getValue(control.id)
            val round = control.id == TouchControlId.L3 || control.id == TouchControlId.R3
            Placed(control.drawn, "touch-${control.id}") {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        if (round) drawOctagon(state.has(button), colors) else drawTab(state.has(button), colors)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        button.name.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.touchLine,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

// [content] in a box at [rect] (dp, from the overlay's corner), out of the screen readers' way.
@Composable
private fun Placed(
    rect: Rect,
    tag: String,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .absoluteOffset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) { hideFromAccessibility() },
    ) { content() }
}

private val FACE = listOf(TouchButton.Triangle, TouchButton.Circle, TouchButton.Cross, TouchButton.Square)

private val LABELLED =
    mapOf(
        TouchControlId.L1 to TouchButton.L1,
        TouchControlId.L2 to TouchButton.L2,
        TouchControlId.R1 to TouchButton.R1,
        TouchControlId.R2 to TouchButton.R2,
        TouchControlId.Select to TouchButton.Select,
        TouchControlId.Start to TouchButton.Start,
        TouchControlId.L3 to TouchButton.L3,
        TouchControlId.R3 to TouchButton.R3,
    )

private const val EDGE = 1.5f

// A rect in dp as this scope's pixels.
private fun Rect.px(scope: DrawScope) =
    with(scope) { Rect(left.dp.toPx(), top.dp.toPx(), right.dp.toPx(), bottom.dp.toPx()) }

// The design system's octagon, filling [rect] (the whole scope by default).
private fun octagon(rect: Rect): Path {
    val cut = rect.width * 0.2929f
    return Path().apply {
        moveTo(rect.left + cut, rect.top)
        lineTo(rect.right - cut, rect.top)
        lineTo(rect.right, rect.top + cut)
        lineTo(rect.right, rect.bottom - cut)
        lineTo(rect.right - cut, rect.bottom)
        lineTo(rect.left + cut, rect.bottom)
        lineTo(rect.left, rect.bottom - cut)
        lineTo(rect.left, rect.top + cut)
        close()
    }
}

private fun DrawScope.drawOctagon(
    pressed: Boolean,
    colors: GsColors,
    rect: Rect = Rect(Offset.Zero, size),
    fill: Color = if (pressed) colors.touchPressed else colors.touch,
) {
    val path = octagon(rect)
    drawPath(path, fill)
    drawPath(path, colors.touchLine, style = Stroke(EDGE.dp.toPx()))
}

// A rectangle at radius-md, as the shoulders, Select and Start are.
private fun DrawScope.drawTab(
    pressed: Boolean,
    colors: GsColors,
    rect: Rect = Rect(Offset.Zero, size),
) {
    val radius = CornerRadius(8.dp.toPx())
    drawRoundRect(if (pressed) colors.touchPressed else colors.touch, rect.topLeft, rect.size, radius)
    drawRoundRect(colors.touchLine, rect.topLeft, rect.size, radius, style = Stroke(EDGE.dp.toPx()))
}

// The D-pad's four arms, [arm] wide, from near the centre to the edge, each with its arrowhead; a held arm filled.
private fun DrawScope.drawDPad(
    arm: Float,
    state: TouchPadState,
    colors: GsColors,
) {
    val r = size.width / 2
    val a = arm / 2
    val arms =
        listOf(
            TouchButton.Up to Rect(r - a, 0f, r + a, r - a),
            TouchButton.Down to Rect(r - a, r + a, r + a, 2 * r),
            TouchButton.Left to Rect(0f, r - a, r - a, r + a),
            TouchButton.Right to Rect(r + a, r - a, 2 * r, r + a),
        )
    for ((button, rect) in arms) {
        drawTab(state.has(button), colors, rect)
        val c = rect.center
        val h = arm * 0.22f
        val head =
            when (button) {
                TouchButton.Up ->
                    listOf(
                        Offset(c.x, c.y - h),
                        Offset(c.x + h, c.y + h * 0.6f),
                        Offset(
                            c.x - h,
                            c.y + h * 0.6f,
                        ),
                    )
                TouchButton.Down ->
                    listOf(
                        Offset(c.x, c.y + h),
                        Offset(c.x - h, c.y - h * 0.6f),
                        Offset(
                            c.x + h,
                            c.y - h * 0.6f,
                        ),
                    )
                TouchButton.Left ->
                    listOf(
                        Offset(c.x - h, c.y),
                        Offset(c.x + h * 0.6f, c.y - h),
                        Offset(
                            c.x + h * 0.6f,
                            c.y + h,
                        ),
                    )
                else -> listOf(Offset(c.x + h, c.y), Offset(c.x - h * 0.6f, c.y + h), Offset(c.x - h * 0.6f, c.y - h))
            }
        drawPath(
            Path().apply {
                moveTo(head[0].x, head[0].y)
                lineTo(head[1].x, head[1].y)
                lineTo(head[2].x, head[2].y)
                close()
            },
            colors.touchLine,
        )
    }
}

// A stick: its ring, and the knob (0.4 of it across) pushed by [deflection] within it.
private fun DrawScope.drawStick(
    deflection: Offset,
    colors: GsColors,
) {
    drawOctagon(false, colors)
    val knob = size.width * 0.4f
    val travel = (size.width - knob) / 2
    val centre = Offset(size.width / 2, size.height / 2) + deflection * travel
    val held = deflection != Offset.Zero
    drawOctagon(held, colors, Rect(centre, knob / 2), fill = if (held) colors.touchPressed else colors.touchLine)
}
