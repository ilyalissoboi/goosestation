// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** The bridge's array helpers (JNIUtil::ToObjectArray, FromJStringArray, ToJLongArray), through test natives. */
@RunWith(AndroidJUnit4::class)
class JniArraysTest {
    @Test
    fun stringsComeBackWhole() {
        assertArrayEquals(arrayOf<String>(), NativeLibrary.stringArrayRoundTripForTest(arrayOf()))
        val strings = arrayOf("a", "Café", "ゴエモン", "😀𝄞")
        assertArrayEquals(strings, NativeLibrary.stringArrayRoundTripForTest(arrayOf(*strings)))
    }

    @Test
    fun aNullStringComesBackEmpty() {
        assertArrayEquals(arrayOf("a", "", "b"), NativeLibrary.stringArrayRoundTripForTest(arrayOf("a", null, "b")))
    }

    @Test
    fun longsComeBackWhole() {
        assertArrayEquals(longArrayOf(), NativeLibrary.longArrayRoundTripForTest(longArrayOf()))
        val values = longArrayOf(0, -1, 42, Long.MIN_VALUE, Long.MAX_VALUE)
        assertArrayEquals(values, NativeLibrary.longArrayRoundTripForTest(values.copyOf()))
    }

    @Test
    fun eachItemIsMadeOnceInOrder() {
        assertArrayEquals(arrayOf("0@0", "1@1", "2@2", "3@3"), NativeLibrary.objectArrayForTest(4, -1, false))
    }

    // A failed item with nothing pending gives null, and Kotlin sees no exception.
    @Test
    fun aFailedItemGivesNullWithNothingPending() {
        assertNull(NativeLibrary.objectArrayForTest(4, 2, false))
    }

    // The helper never clears an exception: the one the item left reaches Kotlin.
    @Test
    fun aFailedItemsExceptionIsLeftPending() {
        val thrown = assertThrows(IllegalArgumentException::class.java) { NativeLibrary.objectArrayForTest(4, 2, true) }
        assertEquals("item 2", thrown.message)
    }
}
