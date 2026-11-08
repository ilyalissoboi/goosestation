// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max

/** A control on the touch overlay. The D-pad and the face buttons are each one control, a cluster. */
enum class TouchControlId { DPad, Face, L1, L2, R1, R2, Select, Start, Menu, L3, R3, LeftStick, RightStick }

/** Where [id] is [drawn], and the larger area that takes its touches ([touch]), in dp. */
data class TouchControl(
    val id: TouchControlId,
    val drawn: Rect,
    val touch: Rect,
)

/**
 * The touch controls on a [width] by [height] dp screen, from the agreed mockups' proportions
 * (docs/superpowers/specs/2026-11-08-android-touch-controls-mockup.html) at [scale]. **Landscape:** the shoulders in
 * the top corners (L2 over L1), the menu at the top middle, Select and Start at the bottom middle, the D-pad and the
 * face diamond at one height in the bars beside the 4:3 [picture]; with sticks, the clusters smaller and higher, the
 * sticks at the bottom, and L3 and R3 above each stick's outer edge. **Portrait:** the [picture] at the top, then the
 * shoulders (L2 L1 · R1 R2) with the menu between, the clusters mirrored around the middle, the sticks, and Select
 * and Start at the bottom. Every touch area is at least 48 dp each way and none overlaps another.
 *
 * The D-pad's arms are [dpadArm] wide; the face buttons are [faceButton] across, [faceOffset] from the diamond's
 * centre ([faceButtonRect]).
 */
data class TouchLayout(
    val width: Float,
    val height: Float,
    val portrait: Boolean,
    val scale: Float,
    val controls: List<TouchControl>,
    val dpadArm: Float,
    val faceButton: Float,
    val faceOffset: Float,
    val picture: Rect,
) {
    operator fun get(id: TouchControlId): TouchControl? = controls.firstOrNull { it.id == id }

    fun getValue(id: TouchControlId): TouchControl = get(id) ?: error("No $id in this layout")

    /** Where a face button is: Triangle above the diamond's centre, Cross below, Square left, Circle right. */
    fun faceButtonRect(button: TouchButton): Rect {
        val centre = getValue(TouchControlId.Face).drawn.center
        val direction =
            when (button) {
                TouchButton.Triangle -> Offset(0f, -1f)
                TouchButton.Cross -> Offset(0f, 1f)
                TouchButton.Square -> Offset(-1f, 0f)
                TouchButton.Circle -> Offset(1f, 0f)
                else -> error("$button isn't a face button")
            }
        return Rect(centre + direction * faceOffset, faceButton / 2)
    }

    companion object {
        /** The smallest scale: below it a face button's or a D-pad arm's touch area would be under 48 dp. */
        const val MIN_SCALE = 0.8f
        private const val MAX_FIT = 1.4f
        private const val STEP = 0.01f
        private const val TARGET = 48f
        private const val EPSILON = 0.01f

        /**
         * The layout for a [width] by [height] dp screen, with [sticks] or not, at the Size setting [size] (1 is
         * 100%). It grows with the screen (a wider bar beside the picture in landscape, a wider screen in portrait,
         * up to 1.4 times the mockup's), times [size]; then shrinks as far as it must, but not under [MIN_SCALE], for
         * every control to keep its touch area clear of the others' and on the screen. Where the bars are narrower
         * than the controls, they overlap the picture; in portrait, the pad moves up over the picture only when
         * the space under it is too short.
         */
        fun of(
            width: Float,
            height: Float,
            sticks: Boolean,
            size: Float,
        ): TouchLayout {
            val portrait = height > width
            val fit =
                if (portrait) {
                    width / 360f
                } else {
                    (width - height * 4 / 3) / 2 / 153f
                }.coerceIn(1f, MAX_FIT)
            var scale = max(fit * size, MIN_SCALE)
            while (scale > MIN_SCALE + EPSILON) {
                val layout = build(width, height, sticks, scale)
                if (layout != null && layout.isClear()) return layout
                scale -= STEP
            }
            return build(width, height, sticks, MIN_SCALE) ?: build(width, height, sticks, MIN_SCALE, force = true)!!
        }

        private fun build(
            width: Float,
            height: Float,
            sticks: Boolean,
            scale: Float,
            force: Boolean = false,
        ): TouchLayout? =
            if (height > width) {
                Builder(width, height, sticks, scale, force).portrait()
            } else {
                Builder(width, height, sticks, scale, force).landscape()
            }

        private fun TouchLayout.isClear(): Boolean {
            for ((i, a) in controls.withIndex()) {
                val t = a.touch
                if (t.width < TARGET - EPSILON || t.height < TARGET - EPSILON) return false
                if (t.left < -EPSILON || t.top < -EPSILON || t.right > width + EPSILON || t.bottom > height + EPSILON) {
                    return false
                }
                for (b in controls.subList(i + 1, controls.size)) if (t.overlaps(b.touch)) return false
            }
            return true
        }

        private fun Rect.overlaps(other: Rect) =
            left < other.right - EPSILON &&
                other.left < right - EPSILON &&
                top < other.bottom - EPSILON &&
                other.top < bottom - EPSILON
    }
}

// The mockups' numbers, at scale 1, are in dp; each is multiplied by s.
private class Builder(
    val w: Float,
    val h: Float,
    val sticks: Boolean,
    val s: Float,
    // Lay it out even where it doesn't fit (the smallest scale on too small a screen).
    val force: Boolean,
) {
    private val controls = mutableListOf<TouchControl>()

    // A touch area at least 48 dp across, with [half] of it each side of [centre].
    private fun half(n: Float) = max(24f, n * s)

    private fun add(
        id: TouchControlId,
        drawn: Rect,
        touch: Rect = drawn,
    ) {
        controls += TouchControl(id, drawn, touch)
    }

    // [id]'s mirror image across the middle, as [mirror] on the right for one on the left.
    private fun addMirrored(
        id: TouchControlId,
        mirror: TouchControlId,
    ) {
        val left = controls.first { it.id == id }
        add(mirror, left.drawn.mirrored(), left.touch.mirrored())
    }

    private fun Rect.mirrored() = Rect(w - right, top, w - left, bottom)

    private fun square(
        centre: Offset,
        half: Float,
    ) = Rect(centre, half)

    // A value between [low] and [high], or null when there's none (unless forced, then [low]).
    private fun within(
        value: Float,
        low: Float,
        high: Float,
    ): Float? =
        when {
            low <= high -> value.coerceIn(low, high)
            force -> low
            else -> null
        }

    private val reach = (if (sticks) 60f else 74f) * s
    private val arm = (if (sticks) 36f else 40f) * s
    private val button = (if (sticks) 44f else 50f) * s
    private val offset = (if (sticks) 42f else 50f) * s
    private val faceHalf = offset + button / 2

    // The taller of the clusters' halves: both sit at one height.
    private val cluster = max(reach, faceHalf)

    private fun addClusters(
        x: Float,
        y: Float,
    ) {
        add(TouchControlId.DPad, square(Offset(x, y), reach))
        add(TouchControlId.Face, square(Offset(w - x, y), faceHalf))
    }

    private fun layout(picture: Rect) = TouchLayout(w, h, h > w, s, controls.toList(), arm, button, offset, picture)

    fun landscape(): TouchLayout? {
        // L2 over L1, each in a band at least 48 dp tall, L2's to the top edge.
        val band = max(24f * 2, 51f * s)
        add(
            TouchControlId.L2,
            Rect(12f * s, band - 37f * s, 100f * s, band - 3f * s),
            Rect(0f, 0f, 106f * s, band),
        )
        add(
            TouchControlId.L1,
            Rect(12f * s, band + 3f * s, 100f * s, band + 37f * s),
            Rect(0f, band, 106f * s, band * 2),
        )
        addMirrored(TouchControlId.L2, TouchControlId.R2)
        addMirrored(TouchControlId.L1, TouchControlId.R1)

        val menu = Rect(w / 2 - 20f * s, 8f * s, w / 2 + 20f * s, 48f * s)
        add(TouchControlId.Menu, menu, Rect(w / 2 - half(24f), 0f, w / 2 + half(24f), max(48f, menu.bottom + 4f * s)))
        addSelectStart(width = 64f, gap = 12f, margin = 12f)

        val x = 78f * s
        val low = band * 2 + cluster
        val y =
            if (sticks) {
                val stick = Offset(208f * s, h - 76f * s)
                add(TouchControlId.LeftStick, square(stick, 58f * s))
                val l3 = Offset(stick.x - 88f * s, stick.y - 48f * s)
                add(TouchControlId.L3, square(l3, 16f * s), square(l3, half(16f)))
                addMirrored(TouchControlId.LeftStick, TouchControlId.RightStick)
                addMirrored(TouchControlId.L3, TouchControlId.R3)
                within(h * 160 / 370, low, l3.y - half(16f) - cluster)
            } else {
                within(h * 220 / 370, low, h - cluster)
            } ?: return null
        addClusters(x, y)

        val bar = max(0f, (w - h * 4 / 3) / 2)
        return layout(Rect(bar, 0f, w - bar, h))
    }

    fun portrait(): TouchLayout? {
        val picture = Rect(0f, 0f, w, w * 3 / 4)

        // From the bottom: Select and Start, the sticks, L3 and R3.
        val selectTop = addSelectStart(width = 62f, gap = 8f, margin = 24f)
        val floor =
            if (sticks) {
                val stick = Offset(90f * s, h - 133f * s)
                add(TouchControlId.LeftStick, square(stick, 55f * s))
                val l3 = Offset(40f * s, stick.y - 55f * s - 4f * s - half(16f))
                add(TouchControlId.L3, square(l3, 16f * s), square(l3, half(16f)))
                addMirrored(TouchControlId.LeftStick, TouchControlId.RightStick)
                addMirrored(TouchControlId.L3, TouchControlId.R3)
                l3.y - half(16f)
            } else {
                selectTop
            }

        // From the picture down: the shoulders and the menu, then the clusters; moved up over the picture only as far
        // as they must to clear what's under them.
        val top = within(picture.bottom, 0f, floor - 170f * s - cluster) ?: return null
        val shoulder = Offset(0f, top + 36f * s)
        add(
            TouchControlId.L2,
            Rect(12f * s, top + 20f * s, 78f * s, top + 52f * s),
            Rect(0f, shoulder.y - half(19f), 81f * s, shoulder.y + half(19f)),
        )
        add(
            TouchControlId.L1,
            Rect(84f * s, top + 20f * s, 150f * s, top + 52f * s),
            Rect(81f * s, shoulder.y - half(19f), 153f * s, shoulder.y + half(19f)),
        )
        addMirrored(TouchControlId.L2, TouchControlId.R2)
        addMirrored(TouchControlId.L1, TouchControlId.R1)
        val menu = Offset(w / 2, top + 40f * s)
        add(TouchControlId.Menu, square(menu, 20f * s), square(menu, half(24f)))
        addClusters(90f * s, top + 170f * s)
        return layout(picture)
    }

    // Select and Start side by side, [gap] apart around the middle, [margin] over the bottom edge; their touch areas
    // meet in the middle and run to the bottom edge. Returns the top of their touch areas.
    private fun addSelectStart(
        width: Float,
        gap: Float,
        margin: Float,
    ): Float {
        val drawn = Rect(w / 2 - (gap / 2 + width) * s, h - (margin + 26f) * s, w / 2 - gap / 2 * s, h - margin * s)
        val top = minOf(drawn.center.y - half(19f), h - 48f)
        add(TouchControlId.Select, drawn, Rect(drawn.left - gap / 2 * s, top, w / 2, h))
        addMirrored(TouchControlId.Select, TouchControlId.Start)
        return top
    }
}
