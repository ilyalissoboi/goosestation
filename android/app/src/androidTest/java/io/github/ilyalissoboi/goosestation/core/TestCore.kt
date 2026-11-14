// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.io.File

/** The install the tests' settings.ini belongs to, and this device's name for memory card sync. */
const val TEST_INSTALL_ID = "test-install"
const val TEST_DEVICE_NAME = "Goose Test Device"

/**
 * Starts the core for a test, with its settings in [dataRoot], BIOS images in [biosDir] and the achievements' own
 * folder at [offline], and fails the test if it doesn't start. [token] is the stored RetroAchievements login.
 */
fun initializeCore(
    context: Context,
    dataRoot: File,
    biosDir: File,
    offline: File,
    token: String? = null,
    deviceName: String = TEST_DEVICE_NAME,
) {
    val resources = Resources.extract(context)
    assertTrue(
        NativeLibrary.initialize(
            dataRoot.absolutePath,
            resources.absolutePath,
            biosDir.absolutePath,
            TEST_INSTALL_ID,
            token,
            offline.absolutePath,
            deviceName,
        ),
    )
}

/**
 * Waits up to [seconds] for [condition], looking every 50 ms, and fails naming [what] (and [state], read as it fails)
 * if it never holds.
 */
fun waitFor(
    what: String,
    seconds: Long = 20,
    state: () -> String = { "" },
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out waiting: $what${state()}")
        Thread.sleep(50)
    }
}

/**
 * Copies the emulator's [path] to [destination] as the shell user, which can read /sdcard where the app can't.
 * [path] may be a glob matching one file, whose name may have spaces: the command is split at spaces with no shell,
 * so it runs in sh, with ${'$'}{IFS} for its space. False if there's nothing there.
 */
fun copyFromShell(
    path: String,
    destination: File,
): Boolean {
    val output =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand("sh -c cat${'$'}{IFS}$path")
    destination.parentFile!!.mkdirs()
    ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
        destination.outputStream().use { input.copyTo(it) }
    }
    return destination.length() > 0
}
