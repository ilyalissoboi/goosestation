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
 * A file replaced since it was decoded (a new modified time) is decoded again.
 */
class CoverLoader(maxBytes: Int) {
    private val cache =
        object : LruCache<String, Bitmap>(maxBytes) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    /**
     * The image at [path], at least [targetPx] on its longer side where the file is that large, or null if it's
     * missing or isn't an image.
     */
    suspend fun load(path: String, targetPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            val file = File(path)
            // 0 for a file that's gone.
            val modified = file.lastModified()
            if (modified == 0L) return@withContext null
            val key = "$modified:$path"
            val bitmap = cache.get(key) ?: decode(file, targetPx)?.also { cache.put(key, it) }
            bitmap?.asImageBitmap()
        }

    private fun decode(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longer = maxOf(bounds.outWidth, bounds.outHeight)
        if (longer <= 0) return null
        // The largest power of two that keeps the longer side at targetPx or more.
        var sampleSize = 1
        while (longer / (sampleSize * 2) >= targetPx) sampleSize *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }
}
