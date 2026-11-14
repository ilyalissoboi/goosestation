// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** Runs what's queued on the test's scheduler until [done], for work that also waits on a real thread: 5 s at most. */
fun TestScope.until(done: () -> Boolean) {
    val end = System.nanoTime() + 5_000_000_000
    while (!done()) {
        check(System.nanoTime() < end) { "timed out" }
        runCurrent()
        Thread.sleep(1)
    }
}
