// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext

/**
 * The page models' settings work on the core queue: it runs on [core], in order with everything else there, and is
 * counted while it does, so a game's start can [settle] it first and boot on the settings asked for. The core queue's own
 * work (library scans, covers) isn't counted, so a start doesn't wait for it.
 *
 * It counts each block dispatched, not each coroutine: a settings write runs as one block, with no suspension inside.
 */
class SettingsQueue(
    private val core: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val lock = Any()
    private var dispatched = 0L
    private val running = sortedSetOf<Long>()

    // Changes as each block ends, for settle() to look again.
    private val ended = MutableStateFlow(0L)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val id = synchronized(lock) { (++dispatched).also { running += it } }
        core.dispatch(context) {
            try {
                block.run()
            } finally {
                synchronized(lock) { running -= id }
                ended.value = id
            }
        }
    }

    /** Returns once every block dispatched before this call has ended; one dispatched after it isn't waited for. */
    suspend fun settle() {
        val upTo = synchronized(lock) { dispatched }
        ended.first { synchronized(lock) { running.firstOrNull()?.let { it > upTo } ?: true } }
    }
}
