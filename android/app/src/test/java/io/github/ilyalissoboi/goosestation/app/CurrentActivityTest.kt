// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentActivityTest {
    private val old = Any()
    private val new = Any()

    @Test
    fun theAttachedActivityIsCurrent() {
        val current = CurrentActivity<Any>()
        assertFalse(current.isCurrent(old))

        current.attach(old)

        assertTrue(current.isCurrent(old))
        assertSame(old, current.current)
    }

    @Test
    fun anActivityRelaunchedBeforeTheOldOneStopsReplacesIt() {
        val current = CurrentActivity<Any>()
        current.attach(old)
        // Back, then a relaunch: the new activity is created and started before the old one stops and is destroyed.
        current.attach(new)

        assertFalse(current.isCurrent(old))
        current.detach(old)

        assertTrue(current.isCurrent(new))
        assertSame(new, current.current)
    }

    @Test
    fun theCurrentActivityDetaches() {
        val current = CurrentActivity<Any>()
        current.attach(old)

        current.detach(old)

        assertNull(current.current)
        assertFalse(current.isCurrent(old))
    }
}
