// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * A picture from the core: [width] × [height] [pixels], row by row, each `0xAARRGGBB` as `Bitmap.createBitmap` takes
 * them. A save state's screenshot, or an on-screen message's icon. Made by native code.
 */
class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)
