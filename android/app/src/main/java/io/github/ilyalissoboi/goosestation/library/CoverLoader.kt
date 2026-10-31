// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cover images from the working copy, decoded no larger than needed and kept in memory, up to [maxBytes] of bitmaps.
 * A file replaced since it was decoded (a new modified time) is decoded again, and so is one asked for larger than it
 * was decoded for, as when a cover first seen in the list is shown in the grid.
 */
class CoverLoader(
    maxBytes: Int,
) {
    /** A decoded [bitmap], and the [targetPx] it was decoded for. */
    private class Cover(
        val bitmap: Bitmap,
        val targetPx: Int,
    )

    private val cache =
        object : LruCache<String, Cover>(maxBytes) {
            override fun sizeOf(
                key: String,
                value: Cover,
            ) = value.bitmap.allocationByteCount
        }

    /**
     * The image at [path], at least [targetPx] on its longer side where the file is that large, or null if it's
     * missing or isn't an image.
     */
    suspend fun load(
        path: String,
        targetPx: Int,
    ): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            // 0 for a file that's gone.
            val modified = file.lastModified()
            if (modified == 0L) return@withContext null
            val key = "$modified:$path"
            val cached = cache.get(key)?.takeIf { servesTarget(it.targetPx, targetPx) }
            val bitmap = cached?.bitmap ?: decode(file, targetPx)?.also { cache.put(key, Cover(it, targetPx)) }
            bitmap?.asImageBitmap()
        }

    private fun decode(
        file: File,
        targetPx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        if (longer <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longer, targetPx) }
        return BitmapFactory.decodeFile(file.path, options)
    }
}

/** Whether a cover decoded for [decodedForPx] is large enough for [targetPx]: one decoded for as much or more is. */
internal fun servesTarget(
    decodedForPx: Int,
    targetPx: Int,
): Boolean = decodedForPx >= targetPx

/** The largest power of two that keeps an image's [longer] side at [targetPx] or more, and at least 1 px. */
internal fun sampleSizeFor(
    longer: Int,
    targetPx: Int,
): Int {
    // A target of 0 or less (a tile not laid out yet) would double the size until it overflowed.
    val target = targetPx.coerceAtLeast(1)
    var sampleSize = 1
    while (longer / (sampleSize * 2) >= target) sampleSize *= 2
    return sampleSize
}
