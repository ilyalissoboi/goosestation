// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Achievement badges and game icons, decoded from the core's cache on [io] as they're asked for, and kept. A picture
 * not downloaded yet is tried again once the core says it's there ([downloaded]). Main thread only.
 */
class BadgeImages(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val images = mutableStateMapOf<String, ImageBitmap>()

    // Changes as a picture downloads, so what showed none asks again.
    private var downloads by mutableIntStateOf(0)

    // Asked for and not found (or being read): not read again until it's downloaded.
    private val tried = HashSet<String>()

    /** The picture at [path], or null until it's read (a recomposition follows when it is). */
    fun image(path: String): ImageBitmap? {
        if (path.isEmpty()) return null
        // Read, so a download recomposes what asked for a picture that wasn't there.
        @Suppress("UNUSED_VARIABLE")
        val seen = downloads
        images[path]?.let { return it }
        if (tried.add(path)) {
            scope.launch {
                val bitmap = withContext(io) { if (File(path).isFile) BitmapFactory.decodeFile(path) else null }
                if (bitmap != null) images[path] = bitmap.asImageBitmap()
            }
        }
        return null
    }

    /** The core downloaded [path]: read it again when it's next asked for. */
    fun downloaded(path: String) {
        tried.remove(path)
        images.remove(path)
        downloads++
    }

    fun clear() {
        images.clear()
        tried.clear()
    }
}
