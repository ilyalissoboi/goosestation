// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * "Is this read still the latest?", for a page model that reads something on the core and shows the answer later. A
 * read takes [next]'s id, and its answer is shown only if [isLatest] still says so when it lands; the site then calls
 * [done]. A change that makes the read out stale calls [invalidate], and reads again after it if one was out, so the
 * page doesn't keep what the stale read would have shown. Main thread only.
 */
class Latest {
    private var id = 0

    /** Whether a read is out: from [next] until [done]. An [invalidate] leaves it out; it just won't land. */
    var pending = false
        private set

    /** A read starts: its id. */
    fun next(): Int {
        pending = true
        return ++id
    }

    /** Whether the read [read] is still the latest. */
    fun isLatest(read: Int): Boolean = read == id

    /** The latest read has landed: none is out. */
    fun done() {
        pending = false
    }

    /** A change makes any read out stale. Returns whether one was out, to read again after the change. */
    fun invalidate(): Boolean {
        id++
        return pending
    }
}
