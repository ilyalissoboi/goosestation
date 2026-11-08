// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ilyalissoboi.goosestation.input.TouchButton
import io.github.ilyalissoboi.goosestation.input.TouchControlId
import io.github.ilyalissoboi.goosestation.input.TouchLayout
import io.github.ilyalissoboi.goosestation.input.TouchPadState
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/** The touch overlay on the emulator's screen, driven by injected touches: taps, slides, several fingers, cancels; and how it's drawn. */
@RunWith(AndroidJUnit4::class)
class TouchOverlayTest {
    @get:Rule val rule = createComposeRule()

    private val sent = mutableListOf<TouchPadState>()
    private var ticks = 0
    private var menus = 0
    private var shown by mutableStateOf(true)
    private var sticks by mutableStateOf(false)
    private var opacity by mutableStateOf(100)
    private var vibration by mutableStateOf(true)
    private var composed = false

    // The overlay's size and density, as it's laid out (the emulator's screen).
    private var size = DpSize.Zero
    private var density = 1f

    // The overlay over black, filling the screen, with these; composed once, then changed. Returns its layout.
    private fun show(
        sticks: Boolean = false,
        opacity: Int = 100,
        vibration: Boolean = true,
    ): TouchLayout {
        this.sticks = sticks
        this.opacity = opacity
        this.vibration = vibration
        if (!composed) {
            composed = true
            rule.setContent {
                GooseStationTheme {
                    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                        size = DpSize(maxWidth, maxHeight)
                        density = LocalDensity.current.density
                        if (shown) {
                            TouchOverlay(
                                sticks = this@TouchOverlayTest.sticks,
                                size = 100,
                                opacity = this@TouchOverlayTest.opacity,
                                sink = { sent += it },
                                ticker = if (this@TouchOverlayTest.vibration) Ticker { ticks++ } else null,
                                onMenu = { menus++ },
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return TouchLayout.of(size.width.value, size.height.value, sticks, 1f)
    }

    private fun overlay() = rule.onNodeWithTag(TOUCH_OVERLAY_TAG)

    // [p] in dp, as the overlay's pixels.
    private fun px(p: Offset) = p * density

    private fun TouchLayout.face(button: TouchButton) = px(faceButtonRect(button).center)

    private fun TouchLayout.centre(id: TouchControlId) = px(getValue(id).drawn.center)

    private fun bits(vararg buttons: TouchButton) = buttons.fold(0) { acc, b -> acc or b.bit }

    @Test
    fun aTapPressesAndReleases() {
        val layout = show()
        overlay().performTouchInput {
            down(layout.face(TouchButton.Cross))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(bits(TouchButton.Cross), 0), sent.map { it.buttons })
        assertEquals(1, ticks)
    }

    @Test
    fun slidingAcrossTheFace() {
        val layout = show()
        overlay().performTouchInput {
            down(layout.face(TouchButton.Cross))
            moveTo((layout.face(TouchButton.Cross) + layout.face(TouchButton.Circle)) / 2f)
            moveTo(layout.face(TouchButton.Circle))
            up()
        }
        rule.waitForIdle()
        assertEquals(
            listOf(
                bits(TouchButton.Cross),
                bits(TouchButton.Cross, TouchButton.Circle),
                bits(TouchButton.Circle),
                0,
            ),
            sent.map { it.buttons },
        )
    }

    @Test
    fun dpadDiagonal() {
        val layout = show()
        val reach = layout.getValue(TouchControlId.DPad).touch.width / 2
        overlay().performTouchInput {
            down(layout.centre(TouchControlId.DPad) + px(Offset(reach * 0.5f, -reach * 0.5f)))
            up()
        }
        rule.waitForIdle()
        assertEquals(bits(TouchButton.Up, TouchButton.Right), sent.first().buttons)
    }

    @Test
    fun twoFingers() {
        val layout = show()
        val reach = layout.getValue(TouchControlId.DPad).touch.width / 2
        overlay().performTouchInput {
            down(0, layout.centre(TouchControlId.DPad) + px(Offset(0f, -reach * 0.7f)))
            down(1, layout.face(TouchButton.Cross))
            up(0)
            up(1)
        }
        rule.waitForIdle()
        assertEquals(
            listOf(bits(TouchButton.Up), bits(TouchButton.Up, TouchButton.Cross), bits(TouchButton.Cross), 0),
            sent.map { it.buttons },
        )
    }

    @Test
    fun aCancelReleases() {
        val layout = show()
        overlay().performTouchInput {
            down(layout.centre(TouchControlId.L1))
            cancel()
        }
        rule.waitForIdle()
        assertEquals(bits(TouchButton.L1), sent.first().buttons)
        assertEquals(TouchPadState.RELEASED, sent.last())
    }

    @Test
    fun hidingReleasesEverything() {
        val layout = show()
        overlay().performTouchInput { down(layout.centre(TouchControlId.R1)) }
        rule.waitForIdle()
        assertEquals(bits(TouchButton.R1), sent.last().buttons)
        shown = false
        rule.waitForIdle()
        assertEquals(TouchPadState.RELEASED, sent.last())
    }

    @Test
    fun noTickWhenVibrationIsOff() {
        val layout = show(vibration = false)
        overlay().performTouchInput {
            down(layout.face(TouchButton.Square))
            up()
        }
        rule.waitForIdle()
        assertEquals(0, ticks)
        assertEquals(listOf(bits(TouchButton.Square), 0), sent.map { it.buttons })
    }

    @Test
    fun theMenuButtonOpensTheMenu() {
        val layout = show()
        overlay().performTouchInput {
            down(layout.centre(TouchControlId.Menu))
            up()
        }
        rule.waitForIdle()
        assertEquals(1, menus)
        assertEquals(emptyList<TouchPadState>(), sent)
    }

    @Test
    fun aStickFollowsTheThumb() {
        val layout = show(sticks = true)
        val stick = layout.getValue(TouchControlId.LeftStick).drawn
        overlay().performTouchInput {
            down(px(stick.center + Offset(stick.width / 4, 0f)))
            up()
        }
        rule.waitForIdle()
        assertEquals(0.5f, sent.first().leftX, 0.02f)
        assertEquals(TouchPadState.RELEASED, sent.last())
    }

    @Test
    fun theControlsAreWhereTheLayoutSays() {
        for (sticks in listOf(false, true)) {
            val layout = show(sticks)
            for (control in layout.controls) {
                val bounds =
                    rule
                        .onNodeWithTag(
                            "touch-${control.id}",
                            useUnmergedTree = true,
                        ).getUnclippedBoundsInRoot()
                assertEquals("${control.id} left", control.drawn.left, bounds.left.value, 1f)
                assertEquals("${control.id} top", control.drawn.top, bounds.top.value, 1f)
                assertEquals("${control.id} width", control.drawn.width, bounds.width.value, 1f)
                assertEquals("${control.id} height", control.drawn.height, bounds.height.value, 1f)
            }
        }
    }

    @Test
    fun theGlyphsAreOneSize() {
        show()
        val names = listOf("Triangle", "Circle", "Cross", "Square")
        val sides =
            names.associateWith {
                val glyph = rule.onNodeWithTag("touch-glyph-$it", useUnmergedTree = true).getUnclippedBoundsInRoot()
                val button = rule.onNodeWithTag("touch-$it", useUnmergedTree = true).getUnclippedBoundsInRoot()
                assertTrue("$it inside", glyph.left >= button.left && glyph.right <= button.right)
                assertTrue("$it inside", glyph.top >= button.top && glyph.bottom <= button.bottom)
                max(glyph.width.value, glyph.height.value)
            }
        sides.values.forEach { assertEquals(sides.getValue("Cross"), it, 1f) }
    }

    // At half opacity, a control's edge is about half as bright over black.
    @Test
    fun opacityFades() {
        show(opacity = 100)
        val full = brightness()
        show(opacity = 50)
        val half = brightness()
        assertTrue("$full then $half", half < full * 0.7f && half > full * 0.3f)
    }

    private fun brightness(): Float {
        val pixels =
            rule
                .onNodeWithTag(
                    "touch-${TouchControlId.L2}",
                    useUnmergedTree = true,
                ).captureToImage()
                .toPixelMap()
        var sum = 0f
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) sum += pixels[x, y].red
        return sum / (pixels.width * pixels.height)
    }
}
