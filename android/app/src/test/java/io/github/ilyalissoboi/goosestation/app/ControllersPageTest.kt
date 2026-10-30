// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.input.ConnectedController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Controllers page's capture, with a fake core and fake pads that write to one call log. */
class ControllersPageTest {
    private val calls = mutableListOf<String>()
    private val api = FakeCoreApi(calls)
    private val pads = FakePads(calls)

    private fun TestScope.openPage(): ControllersPage {
        val page = ControllersPage(backgroundScope, StandardTestDispatcher(testScheduler), api, { pads }, { true })
        page.open()
        runCurrent()
        assertEquals(PAD_A, page.state.selected)
        return page
    }

    @Test
    fun aResultAfterACancelIsDropped() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()

            page.cancelCapture()
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()

            assertTrue(calls.none { it.startsWith("setPadBinding") })
            assertNull(page.state.capturing)
            assertEquals("Button96", page.state.bindings!!.single { it.name == "Cross" }.binding)
        }

    @Test
    fun theTimeoutCancelsOnlyItsOwnCapture() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            advanceTimeBy(2_000)
            page.cancelCapture()
            page.capture("Circle")
            runCurrent()

            // Past Cross's 5 s, and 3.5 s into Circle's.
            advanceTimeBy(3_500)
            runCurrent()

            assertEquals("Circle", page.state.capturing)
            assertEquals(2, page.state.secondsLeft)
        }

    @Test
    fun everyEndReleasesAfterTheNativeCancel() =
        runTest {
            val page = openPage()

            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()
            assertReleasedAfterTheCancel()

            calls.clear()
            page.capture("Cross")
            runCurrent()
            page.cancelCapture()
            runCurrent()
            assertReleasedAfterTheCancel()

            calls.clear()
            page.capture("Cross")
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertNull(page.state.capturing)
            assertReleasedAfterTheCancel()
        }

    @Test
    fun axesAreSentBeforeTheStart() =
        runTest {
            val page = openPage()

            page.capture("Cross")
            runCurrent()

            val sent = calls.indexOf("sendAxes $PAD_A")
            assertTrue(sent >= 0)
            assertTrue(sent < calls.indexOf("startBindingCapture $PAD_A"))
        }

    @Test
    fun aGoneControllerCancelsItsCapture() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            calls.clear()

            pads.connected.value = emptyList()
            runCurrent()

            assertNull(page.state.capturing)
            assertEquals(listOf("cancelBindingCapture", "releaseAll"), calls)
        }

    // After the start, the capture's last cancelBindingCapture, if any, comes before a releaseAll.
    private fun assertReleasedAfterTheCancel() {
        val started = calls.indexOf("startBindingCapture $PAD_A")
        val released = calls.lastIndexOf("releaseAll")
        assertTrue("$calls", started >= 0 && released > started)
        assertTrue("$calls", released > calls.lastIndexOf("cancelBindingCapture"))
    }

    private class FakePads(
        private val calls: MutableList<String>,
    ) : PadPort {
        override val connected = MutableStateFlow(listOf(ConnectedController(1, "Pad A", PAD_A)))

        override fun sendAxes(descriptor: String) {
            calls += "sendAxes $descriptor"
        }

        override fun releaseAll() {
            calls += "releaseAll"
        }
    }

    private companion object {
        const val PAD_A = "pad-a"
    }
}
