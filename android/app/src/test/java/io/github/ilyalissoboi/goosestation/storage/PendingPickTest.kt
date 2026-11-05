// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A system picker's one pick under way: its result, or nothing when the activity that opened it goes away. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingPickTest {
    private val pick = PendingPick<String>()

    @Test
    fun theResultIsHandedBack() =
        runTest {
            val result = async { pick.await {} }
            runCurrent()
            assertTrue(pick.busy)

            pick.deliver("content://save")

            assertEquals("content://save", result.await())
            assertFalse(pick.busy)
        }

    @Test
    fun abandoningEndsThePickWithNothing() =
        runTest {
            val result = async { pick.await {} }
            runCurrent()

            pick.abandon()

            assertNull(result.await())
            assertFalse(pick.busy)
        }

    @Test
    fun aSecondPickWhileOneIsUnderWayGetsNothing() =
        runTest {
            val first = async { pick.await {} }
            runCurrent()

            assertNull(pick.await { error("not opened") })
            pick.deliver("content://first")
            assertEquals("content://first", first.await())
        }

    @Test
    fun aLaunchThatCantOpenAbandons() =
        runTest {
            assertNull(pick.await { pick.abandon() })
            assertFalse(pick.busy)
        }
}
