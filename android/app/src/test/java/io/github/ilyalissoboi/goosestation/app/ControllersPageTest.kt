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
            assertEquals(
                "Button96",
                page.state.bindings!!
                    .single { it.name == "Cross" }
                    .binding,
            )
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

    @Test
    fun hotkeysAreReadWithTheBindings() =
        runTest {
            val page = openPage()

            assertEquals(listOf("ToggleFastForward", "Analog", "Screenshot"), page.state.hotkeys!!.map { it.name })
        }

    @Test
    fun aHotkeyCaptureSetsTheHotkey() =
        runTest {
            val page = openPage()
            calls.clear()

            page.captureHotkey("ToggleFastForward")
            runCurrent()
            assertEquals("ToggleFastForward", page.state.capturing)
            page.onBindingCaptured("Button99")
            runCurrent()

            assertTrue(calls.contains("startBindingCapture $PAD_A chord"))
            assertTrue(calls.contains("setHotkeyBinding $PAD_A ToggleFastForward Button99"))
            assertTrue(calls.none { it.startsWith("setPadBinding") })
            assertEquals(
                "Button109 & Android-0/Button99",
                page.state.hotkeys!!
                    .single { it.name == "ToggleFastForward" }
                    .binding,
            )
        }

    @Test
    fun aPadCaptureIsntAChord() =
        runTest {
            val page = openPage()
            calls.clear()

            page.capture("Cross")
            runCurrent()

            assertTrue(calls.contains("startBindingCapture $PAD_A"))
        }

    @Test
    fun clearAndUndoWorkOnHotkeys() =
        runTest {
            val page = openPage()
            page.captureHotkey("Screenshot")
            runCurrent()
            page.onBindingCaptured("Button99")
            runCurrent()
            page.captureHotkey("Screenshot")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()
            assertEquals(Undo("Screenshot", "Button109 & Android-0/Button99"), page.state.undo)
            calls.clear()

            page.undo()
            runCurrent()
            assertEquals(listOf("setHotkeyBinding $PAD_A Screenshot Button99"), calls)

            calls.clear()
            page.clear("Screenshot")
            runCurrent()
            assertEquals(listOf("setHotkeyBinding $PAD_A Screenshot null"), calls)
            assertNull(
                page.state.hotkeys!!
                    .single { it.name == "Screenshot" }
                    .binding,
            )
        }

    @Test
    fun undoPutsThePreviousBindingBack() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()
            assertEquals(Undo("Cross", "Button96"), page.state.undo)
            calls.clear()

            page.undo()
            runCurrent()

            assertEquals(listOf("setPadBinding $PAD_A Cross Button96"), calls)
            assertNull(page.state.undo)
            assertEquals(
                "Button96",
                page.state.bindings!!
                    .single { it.name == "Cross" }
                    .binding,
            )
        }

    @Test
    fun anotherCaptureDropsTheUndo() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()

            page.capture("Circle")

            assertNull(page.state.undo)
        }

    @Test
    fun dropUndoForgetsIt() =
        runTest {
            val page = openPage()
            page.capture("Cross")
            runCurrent()
            page.onBindingCaptured("Button100")
            runCurrent()

            page.dropUndo()
            page.undo()
            runCurrent()

            assertNull(page.state.undo)
            assertTrue(calls.none { it == "setPadBinding $PAD_A Cross Button96" })
        }

    // Review Focus 2: by their places in the descriptor table, so the numbers stay the same from one run to the next.
    @Test
    fun sameNamesAreNumbered() =
        runTest {
            api.indices = mapOf(PAD_A to 1, "pad-b" to 0, "pad-c" to 2)
            pads.connected.value =
                listOf(
                    ConnectedController(1, "Wireless Controller", PAD_A),
                    ConnectedController(2, "Wireless Controller", "pad-b"),
                    ConnectedController(3, "Pad C", "pad-c"),
                )
            val page = openPage()

            assertEquals(
                mapOf(PAD_A to "Wireless Controller (2)", "pad-b" to "Wireless Controller (1)", "pad-c" to "Pad C"),
                page.state.labels,
            )

            pads.connected.value = listOf(ConnectedController(1, "Wireless Controller", PAD_A))
            runCurrent()

            assertEquals(mapOf(PAD_A to "Wireless Controller"), page.state.labels)
        }

    // A brand-new pad's place can be asked for before the core has added it: it's asked for once more.
    @Test
    fun aNewPadIsNumberedOnceTheTableHasIt() =
        runTest {
            api.indices = mapOf(PAD_A to 0, "pad-b" to 1)
            api.notYetInTable += "pad-b"
            pads.connected.value =
                listOf(
                    ConnectedController(1, "Wireless Controller", PAD_A),
                    ConnectedController(2, "Wireless Controller", "pad-b"),
                )
            val page = openPage()

            assertEquals(
                mapOf(PAD_A to "Wireless Controller (1)", "pad-b" to "Wireless Controller (2)"),
                page.state.labels,
            )
        }

    // A pad the table never has keeps its bare name, and isn't asked for over and over.
    @Test
    fun aPadTheTableLacksIsAskedForTwice() =
        runTest {
            api.indices = mapOf(PAD_A to 0)
            pads.connected.value =
                listOf(
                    ConnectedController(1, "Wireless Controller", PAD_A),
                    ConnectedController(2, "Wireless Controller", "pad-b"),
                )
            val page = openPage()

            assertEquals("Wireless Controller", page.state.labels["pad-b"])
            assertEquals(2, calls.count { it == "getControllerIndex pad-b" })
        }

    // T7-3: until the new pad's bindings are read, the page knows the ones it has are another's, and edits nothing.
    @Test
    fun aSwitchSaysWhoseBindingsAreShown() =
        runTest {
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", "pad-b"))
            val page = openPage()
            assertEquals(PAD_A, page.state.bindingsFor)

            page.select("pad-b")
            page.capture("Cross")
            page.clear("Cross")

            assertEquals(PAD_A, page.state.bindingsFor)
            assertNull(page.state.capturing)
            runCurrent()
            assertEquals("pad-b", page.state.bindingsFor)
            assertTrue(calls.none { it.startsWith("setPadBinding") || it.startsWith("startBindingCapture") })
        }

    // Review finding: the pad picked earlier coming back mustn't take the page from the one in use, mid-capture.
    @Test
    fun aPickedPadReconnectingKeepsThePadInUse() =
        runTest {
            val padA = ConnectedController(1, "Pad A", PAD_A)
            val padB = ConnectedController(2, "Pad B", "pad-b")
            pads.connected.value = listOf(padA, padB)
            val page = openPage()
            page.select("pad-b")
            runCurrent()
            pads.connected.value = listOf(padA)
            runCurrent()
            assertEquals(PAD_A, page.state.selected)
            page.capture("Cross")
            runCurrent()

            pads.connected.value = listOf(padA, padB)
            runCurrent()

            assertEquals(PAD_A, page.state.selected)
            assertEquals("Cross", page.state.capturing)
        }

    // On opening, the pad picked last is selected again, if it's connected.
    @Test
    fun theOpeningSelectsThePadPickedLast() =
        runTest {
            pads.connected.value =
                listOf(ConnectedController(1, "Pad A", PAD_A), ConnectedController(2, "Pad B", "pad-b"))
            val page = openPage()
            page.select("pad-b")
            runCurrent()

            page.close()
            page.open()
            runCurrent()

            assertEquals("pad-b", page.state.selected)
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
