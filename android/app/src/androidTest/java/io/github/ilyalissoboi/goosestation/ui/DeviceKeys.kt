// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Keys as a controller sends them on a device: out of touch mode, through the window. Out of touch mode, a focused
 * element that goes leaves the focus to Android, which gives it back to the first thing that takes it (in the
 * settings' two panels, the rail), as it doesn't in the touch mode Compose's own key input leaves the tests in.
 * Touch mode is the display's, not the test's: it's put back after each test that left it.
 */
class DeviceKeys : ExternalResource() {
    private var leftTouchMode = false

    /** Presses the key with [keyCode] (an Android `KeyEvent` code), and waits until the app has taken it. */
    fun press(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.setInTouchMode(false)
            leftTouchMode = true
        }
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
    }

    override fun after() {
        if (leftTouchMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        }
    }
}
