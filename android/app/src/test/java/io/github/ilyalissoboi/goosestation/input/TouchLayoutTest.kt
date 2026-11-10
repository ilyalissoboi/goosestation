// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import androidx.compose.ui.geometry.Rect
import io.github.ilyalissoboi.goosestation.input.TouchControlId.DPad
import io.github.ilyalissoboi.goosestation.input.TouchControlId.Face
import io.github.ilyalissoboi.goosestation.input.TouchControlId.L1
import io.github.ilyalissoboi.goosestation.input.TouchControlId.L2
import io.github.ilyalissoboi.goosestation.input.TouchControlId.L3
import io.github.ilyalissoboi.goosestation.input.TouchControlId.LeftStick
import io.github.ilyalissoboi.goosestation.input.TouchControlId.R1
import io.github.ilyalissoboi.goosestation.input.TouchControlId.R2
import io.github.ilyalissoboi.goosestation.input.TouchControlId.R3
import io.github.ilyalissoboi.goosestation.input.TouchControlId.RightStick
import io.github.ilyalissoboi.goosestation.input.TouchControlId.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The touch controls' layouts: the mockup's proportions, every touch area 48 dp or more, none over another. */
class TouchLayoutTest {
    private val landscape = listOf(800f to 370f, 800f to 360f, 640f to 360f, 960f to 540f, 1280f to 800f)
    private val portrait = listOf(360f to 800f, 360f to 780f, 411f to 891f, 800f to 1280f)
    private val sizes = listOf(0.8f, 1f, 1.3f)

    private fun each(check: (TouchLayout, String) -> Unit) {
        for ((w, h) in landscape + portrait) {
            for (sticks in listOf(false, true)) {
                for (size in sizes) {
                    check(TouchLayout.of(w, h, sticks, size), "${w}x$h sticks=$sticks size=$size")
                }
            }
        }
    }

    private fun Rect.encloses(other: Rect) =
        other.left >= left - EPSILON &&
            other.top >= top - EPSILON &&
            other.right <= right + EPSILON &&
            other.bottom <= bottom + EPSILON

    @Test
    fun everyTouchAreaIsAtLeast48() =
        each { layout, name ->
            for (control in layout.controls) {
                assertTrue("$name ${control.id} ${control.touch}", control.touch.width >= 48f - EPSILON)
                assertTrue("$name ${control.id} ${control.touch}", control.touch.height >= 48f - EPSILON)
            }
            // A face button presses within 0.8 of the offset from its centre (TouchTracker).
            assertTrue("$name face zone", 2 * 0.8f * layout.faceOffset >= 48f)
            // A D-pad arm presses from the centre out to the reach and beyond.
            assertTrue("$name D-pad reach", layout.getValue(DPad).touch.width / 2 >= 48f)
        }

    // From review: Compose's own Rect.overlaps shadowed the layout's, so a hair of rounding counted as an overlap.
    @Test
    fun touchAreasMeetingWithinARoundingHairDontOverlap() {
        val left = Rect(0f, 0f, 10f, 10f)
        assertFalse(left.overlapsBeyondEpsilon(Rect(9.995f, 0f, 20f, 10f)))
        assertFalse(left.overlapsBeyondEpsilon(Rect(0f, 9.995f, 10f, 20f)))
        assertTrue(left.overlapsBeyondEpsilon(Rect(9.5f, 0f, 20f, 10f)))
        assertTrue(left.overlapsBeyondEpsilon(Rect(2f, 2f, 8f, 8f)))
    }

    @Test
    fun noTouchAreasOverlap() =
        each { layout, name ->
            val controls = layout.controls
            for (i in controls.indices) {
                for (j in i + 1 until controls.size) {
                    assertFalse(
                        "$name ${controls[i].id} ${controls[i].touch} and ${controls[j].id} ${controls[j].touch}",
                        controls[i].touch.overlapsBeyondEpsilon(controls[j].touch),
                    )
                }
            }
        }

    @Test
    fun everythingIsOnScreen() =
        each { layout, name ->
            for (control in layout.controls) {
                val t = control.touch
                assertTrue(
                    "$name ${control.id} $t",
                    t.left >= -EPSILON && t.top >= -EPSILON && t.right <= layout.width + EPSILON &&
                        t.bottom <= layout.height + EPSILON,
                )
                assertTrue("$name ${control.id} drawn in touch", t.encloses(control.drawn))
            }
        }

    @Test
    fun theFaceDiamondIsSymmetric() =
        each { layout, name ->
            val centre = layout.getValue(Face).drawn.center
            val up = layout.faceButtonRect(TouchButton.Triangle)
            val down = layout.faceButtonRect(TouchButton.Cross)
            val left = layout.faceButtonRect(TouchButton.Square)
            val right = layout.faceButtonRect(TouchButton.Circle)
            for (button in listOf(up, down, left, right)) {
                assertEquals(name, layout.faceButton, button.width, EPSILON)
                assertEquals(name, layout.faceButton, button.height, EPSILON)
            }
            assertEquals(name, centre.x, up.center.x, EPSILON)
            assertEquals(name, centre.y - layout.faceOffset, up.center.y, EPSILON)
            assertEquals(name, centre.y + layout.faceOffset, down.center.y, EPSILON)
            assertEquals(name, centre.x - layout.faceOffset, left.center.x, EPSILON)
            assertEquals(name, centre.x + layout.faceOffset, right.center.x, EPSILON)
            assertEquals(name, centre.y, left.center.y, EPSILON)
            assertEquals(name, centre.y, right.center.y, EPSILON)
        }

    @Test
    fun theDPadMirrorsTheFace() =
        each { layout, name ->
            val dpad = layout.getValue(DPad).drawn.center
            val face = layout.getValue(Face).drawn.center
            assertEquals(name, layout.width - face.x, dpad.x, EPSILON)
            assertEquals(name, face.y, dpad.y, EPSILON)
        }

    @Test
    fun theSidesMirror() =
        each { layout, name ->
            for ((a, b) in listOf(L1 to R1, L2 to R2, LeftStick to RightStick, L3 to R3)) {
                val left = layout[a]?.drawn ?: continue
                val right = layout.getValue(b).drawn
                assertEquals("$name $a", layout.width - right.right, left.left, EPSILON)
                assertEquals("$name $a", right.top, left.top, EPSILON)
                assertEquals("$name $a", right.width, left.width, EPSILON)
            }
        }

    @Test
    fun sticksOnlyWhenAsked() {
        for ((w, h) in landscape + portrait) {
            val digital = TouchLayout.of(w, h, false, 1f)
            val analog = TouchLayout.of(w, h, true, 1f)
            for (id in listOf(LeftStick, RightStick, L3, R3)) {
                assertNull("${w}x$h $id", digital[id])
                assertNotNull("${w}x$h $id", analog[id])
            }
            if (w > h) {
                assertTrue("${w}x$h smaller", analog.getValue(DPad).drawn.width < digital.getValue(DPad).drawn.width)
                assertTrue("${w}x$h smaller", analog.faceButton < digital.faceButton)
                assertTrue(
                    "${w}x$h higher",
                    analog
                        .getValue(DPad)
                        .drawn.center.y <
                        digital
                            .getValue(DPad)
                            .drawn.center.y,
                )
            }
        }
    }

    @Test
    fun landscapeKeepsToTheBars() {
        for (sticks in listOf(false, true)) {
            val layout = TouchLayout.of(800f, 370f, sticks, 1f)
            assertFalse(layout.portrait)
            for (id in listOf(DPad, Face, L1, L2, R1, R2, L3, R3)) {
                val touch = layout[id]?.touch ?: continue
                assertFalse("sticks=$sticks $id $touch", touch.overlaps(layout.picture))
            }
        }
    }

    @Test
    fun portraitIsUnderThePicture() {
        for ((w, h) in listOf(360f to 800f, 411f to 891f, 800f to 1280f)) {
            for (sticks in listOf(false, true)) {
                val layout = TouchLayout.of(w, h, sticks, 1f)
                assertTrue(layout.portrait)
                assertEquals(Rect(0f, 0f, w, w * 3 / 4), layout.picture)
                for (control in layout.controls) {
                    assertFalse("${w}x$h sticks=$sticks ${control.id}", control.touch.overlaps(layout.picture))
                }
            }
        }
    }

    @Test
    fun theMockupsPhoneLayouts() {
        val digital = TouchLayout.of(800f, 370f, false, 1f)
        assertEquals(1f, digital.scale, 0.01f)
        assertEquals(
            78f,
            digital
                .getValue(DPad)
                .drawn.center.x,
            0.5f,
        )
        assertEquals(
            220f,
            digital
                .getValue(DPad)
                .drawn.center.y,
            0.5f,
        )
        assertEquals(50f, digital.faceButton, 0.5f)
        assertEquals(50f, digital.faceOffset, 0.5f)
        assertEquals(40f, digital.dpadArm, 0.5f)
        val l2 = digital.getValue(L2).drawn
        assertEquals(12f, l2.left, 0.5f)
        assertEquals(14f, l2.top, 0.5f)
        assertEquals(100f, l2.right, 0.5f)
        assertEquals(48f, l2.bottom, 0.5f)
        assertEquals(64f, digital.getValue(Select).drawn.width, 0.5f)

        // The picture sits at the very top (the core's top alignment), 40 dp higher than the mockup drew it.
        val analog = TouchLayout.of(360f, 780f, true, 1f)
        assertEquals(1f, analog.scale, 0.01f)
        assertEquals(
            270f,
            analog
                .getValue(Face)
                .drawn.center.x,
            0.5f,
        )
        assertEquals(
            440f,
            analog
                .getValue(Face)
                .drawn.center.y,
            0.5f,
        )
        assertEquals(110f, analog.getValue(LeftStick).drawn.width, 0.5f)
        assertEquals(
            90f,
            analog
                .getValue(LeftStick)
                .drawn.center.x,
            0.5f,
        )
        assertEquals(
            647f,
            analog
                .getValue(LeftStick)
                .drawn.center.y,
            0.5f,
        )
    }

    @Test
    fun aLargerSizeIsLargerWhereItFits() {
        val normal = TouchLayout.of(1280f, 800f, false, 1f)
        val large = TouchLayout.of(1280f, 800f, false, 1.3f)
        assertTrue(large.scale > normal.scale)
        val small = TouchLayout.of(800f, 370f, false, 0.8f)
        assertEquals(0.8f, small.scale, 0.01f)
    }

    private companion object {
        const val EPSILON = 0.01f
    }
}
