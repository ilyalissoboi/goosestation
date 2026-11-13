// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The page models' settings work on the core queue ([SettingsQueue]), which a game's start waits for. Real threads: the
 * work blocks its thread, as the core's does.
 */
class SettingsQueueTest {
    private val core: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val queue = SettingsQueue(core)
    private val gates = mutableListOf<CountDownLatch>()

    // The work runs outside the test's own scope: runBlocking would wait for gated work still running at its end.
    private val outside = CoroutineScope(Job())

    @After
    fun close() {
        gates.forEach { it.countDown() }
        outside.cancel()
        core.close()
    }

    private fun gate() = CountDownLatch(1).also { gates += it }

    @Test
    fun settleWaitsForTheWorkBeforeIt() =
        runBlocking {
            val write = gate()
            outside.launch(queue) { write.await() }

            val settled = async { queue.settle() }
            delay(100)
            assertFalse("still writing", settled.isCompleted)

            write.countDown()
            withTimeout(5_000) { settled.await() }
        }

    @Test
    fun settleDoesntWaitForWorkAfterIt() =
        runBlocking {
            val first = gate()
            outside.launch(queue) { first.await() }
            val settled = async { queue.settle() }
            delay(100)
            val later = gate()
            outside.launch(queue) { later.await() }

            first.countDown()
            withTimeout(5_000) { settled.await() }
        }

    // A scan, or cover work, on the core queue itself isn't settings work: a start doesn't wait for it.
    @Test
    fun rawCoreWorkDoesntCount() =
        runBlocking {
            val scan = gate()
            outside.launch(core) { scan.await() }
            delay(50)

            withTimeout(1_000) { queue.settle() }
        }
}
