// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * JNI_OnLoad's table loaders (LoadClasses, LoadMethods, LoadEnums in jni_classes.cpp), through test natives that
 * return [loaded (1 or 0), rows filled]. A miss stops the loader there, with the rows after it untouched and no
 * exception left pending: one would be thrown here, at the call.
 */
@RunWith(AndroidJUnit4::class)
class JniLookupsTest {
    @Test
    fun realRowsLoadAndFillEveryTarget() {
        assertArrayEquals(
            intArrayOf(1, 2),
            NativeLibrary.classLookupsForTest(arrayOf("java/lang/String", "java/lang/Object"), arrayOf("([C)V", null)),
        )
        assertArrayEquals(
            intArrayOf(1, 2),
            NativeLibrary.methodLookupsForTest(
                "java/lang/Object",
                arrayOf("toString", "hashCode"),
                arrayOf("()Ljava/lang/String;", "()I"),
            ),
        )
        assertArrayEquals(
            intArrayOf(1, 2),
            NativeLibrary.enumLookupsForTest("java/lang/Thread\$State", arrayOf("NEW", "RUNNABLE")),
        )
    }

    @Test
    fun aMissingClassStopsThere() {
        assertArrayEquals(
            intArrayOf(0, 1),
            NativeLibrary.classLookupsForTest(
                arrayOf("java/lang/String", "no/such/Class", "java/lang/Object"),
                arrayOf(null, null, null),
            ),
        )
    }

    @Test
    fun aConstructorThatDoesntMatchStopsThere() {
        assertArrayEquals(
            intArrayOf(0, 1),
            NativeLibrary.classLookupsForTest(
                arrayOf("java/lang/String", "java/lang/Object", "java/lang/Thread"),
                arrayOf("([C)V", "(I)V", null),
            ),
        )
    }

    @Test
    fun aMissingMethodStopsThere() {
        assertArrayEquals(
            intArrayOf(0, 1),
            NativeLibrary.methodLookupsForTest(
                "java/lang/Object",
                arrayOf("toString", "noSuchMethod", "hashCode"),
                arrayOf("()Ljava/lang/String;", "()V", "()I"),
            ),
        )
    }

    @Test
    fun aMissingEnumConstantStopsThere() {
        assertArrayEquals(
            intArrayOf(0, 1),
            NativeLibrary.enumLookupsForTest("java/lang/Thread\$State", arrayOf("NEW", "NO_SUCH", "RUNNABLE")),
        )
    }
}
