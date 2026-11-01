// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.ilyalissoboi.goosestation.core.ArgbImage

/** Pictures from files, as the on-screen messages take them. */
object ArgbImages {
    /** The PNG (or other image) at [path], scaled to [px] square at most, or null if it can't be read. Blocks. */
    fun decode(
        path: String,
        px: Int,
    ): ArgbImage? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val scaled =
            if (bitmap.width > px ||
                bitmap.height > px
            ) {
                Bitmap.createScaledBitmap(bitmap, px, px, true)
            } else {
                bitmap
            }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return ArgbImage(scaled.width, scaled.height, pixels)
    }
}
