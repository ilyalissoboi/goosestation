// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.media.AudioAttributes
import android.media.SoundPool

/**
 * The core's achievement sounds (WAVs in its resources), played on a [SoundPool] at the game's volume. Each is loaded
 * the first time it's asked for, and played once loaded. Any thread.
 */
class AchievementSounds {
    private val pool =
        SoundPool
            .Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()

    private val loaded = HashMap<String, Int>()
    private val waiting = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { pool, id, status ->
            val play = synchronized(this) { waiting.remove(id) }
            if (status == 0 && play) pool.play(id, 1f, 1f, 0, 0, 1f)
        }
    }

    /** Plays the sound at [path]. */
    fun play(path: String) {
        val (id, ready) =
            synchronized(this) {
                loaded[path]?.let { return@synchronized it to !waiting.contains(it) }
                val id = pool.load(path, 1)
                loaded[path] = id
                waiting += id
                id to false
            }
        if (ready) pool.play(id, 1f, 1f, 0, 0, 1f)
    }
}
