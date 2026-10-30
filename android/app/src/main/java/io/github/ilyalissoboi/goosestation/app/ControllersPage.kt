// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Controllers page: the [controllers] connected, the [selected] one's descriptor, and its [bindings], null until
 * they're first read. While [capturing] is a binding's name, the next input on that controller becomes its binding,
 * unless [secondsLeft] runs out first.
 */
data class ControllersState(
    val controllers: List<ConnectedController> = emptyList(),
    val selected: String? = null,
    val bindings: List<PadBinding>? = null,
    val capturing: String? = null,
    val secondsLeft: Int = 0,
)

/**
 * The Controllers page's controllers, bindings and capture, from [open] to [close]. Main thread only, but for
 * [onBindingCaptured]. Its [api] calls run on [core], one at a time; [pads] are the controllers, null before they're
 * made, and [isOpen] says the page shows, as a capture only starts there.
 */
class ControllersPage(
    private val scope: CoroutineScope,
    private val core: CoroutineDispatcher,
    private val api: CoreApi,
    private val pads: () -> PadPort?,
    private val isOpen: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    /** The page's controllers, bindings and capture. */
    var state by mutableStateOf(ControllersState())
        private set

    /** A capture runs: the controllers' input goes to the core, which takes the next one as a binding. */
    val capturingInput: Boolean get() = state.capturing != null

    /** The controller last picked on the page, which it selects whenever it's connected. */
    private var pickedController: String? = null
    private var controllersJob: Job? = null
    private var captureTimer: Job? = null

    /** The latest bindings read, so an older read that comes back later is dropped. */
    private var bindingsRead = 0

    /** Changes as each capture starts or ends, so a report the main thread gets late is dropped. */
    private var captureId = 0

    /** [captureId] of the capture the core runs: set on [core], read where the core reports. */
    @Volatile private var nativeCaptureId = 0

    /** Follows the controllers connected while the page is open, from no bindings read yet. */
    fun open() {
        controllersJob?.cancel()
        state = ControllersState()
        val input = pads() ?: return
        controllersJob = scope.launch { input.connected.collect(::onControllersChanged) }
    }

    /** The page is left: a capture running ends, and the controllers aren't followed any more. */
    fun close() {
        cancelCapture()
        controllersJob?.cancel()
        controllersJob = null
    }

    /** Shows the controller with [descriptor]'s bindings, and selects it whenever it's connected from now on. */
    fun select(descriptor: String) {
        pickedController = descriptor
        val state = state
        if (descriptor == state.selected || state.controllers.none { it.descriptor == descriptor }) return
        cancelCapture()
        this.state = this.state.copy(selected = descriptor)
        readBindings(descriptor)
    }

    /**
     * Captures the selected controller's next input as [name]'s binding ([onBindingCaptured]): for 5 s, counting down
     * once a second, and then it's cancelled. Its controllers' input goes to the core meanwhile ([capturingInput]).
     */
    fun capture(name: String) {
        val state = state
        val descriptor = state.selected ?: return
        if (!isOpen() || state.bindings?.any { it.name == name } != true) return
        val id = ++captureId
        captureTimer?.cancel()
        this.state = state.copy(capturing = name, secondsLeft = CAPTURE_SECONDS)
        scope.launch {
            // Nothing from an earlier capture comes once the cancel returns: what's reported next is this one's.
            withContext(core) { api.cancelBindingCapture() }
            if (id != captureId) return@launch
            // Here, on the main thread, after the releaseAll() an earlier capture's end runs once its cancel returns,
            // which was before this one's: the pad's current axes, so the capture knows which are at rest. The core
            // takes in the input sent before the start.
            pads()?.sendAxes(descriptor)
            withContext(core) {
                nativeCaptureId = id
                api.startBindingCapture(descriptor)
            }
        }
        captureTimer =
            scope.launch {
                for (left in CAPTURE_SECONDS downTo 1) {
                    this@ControllersPage.state = this@ControllersPage.state.copy(secondsLeft = left)
                    delay(1_000)
                }
                cancelCapture()
            }
    }

    /** Ends the capture running, if any, leaving the binding as it was. */
    fun cancelCapture() {
        if (state.capturing == null) return
        endCapture()
        scope.launch {
            withContext(core) { api.cancelBindingCapture() }
            // Once the capture is gone, as it would swallow the releases too.
            pads()?.releaseAll()
        }
    }

    /** Leaves [name] unbound on the selected controller. */
    fun clear(name: String) {
        val descriptor = state.selected ?: return
        if (capturingInput) return
        setBindingShown(name, null)
        readBindings(descriptor) { api.setPadBinding(descriptor, name, null) }
    }

    /** Gives the selected controller its default bindings again. */
    fun reset() {
        val descriptor = state.selected ?: return
        if (capturingInput) return
        readBindings(descriptor) { api.resetPadBindings(descriptor) }
    }

    /**
     * The core took [binding] as the capture's input. Called on the core's thread, as it reports it: the capture is
     * known there, and the rest runs on the main thread.
     */
    fun onBindingCaptured(binding: String) {
        // The capture's, as the core reports it: a later one only starts once this one can't report any more.
        val id = nativeCaptureId
        scope.launch {
            val state = state
            val name = state.capturing
            val descriptor = state.selected
            // Cancelled, timed out or replaced since it was posted.
            if (id != captureId || name == null || descriptor == null) return@launch
            log("Captured $binding for $name")
            endCapture()
            // The core removed the capture as it took the input, so the releases reach it.
            pads()?.releaseAll()
            setBindingShown(name, binding)
            readBindings(descriptor) { api.setPadBinding(descriptor, name, binding) }
        }
    }

    /**
     * The page lists [connected], and selects the controller last picked if it's among them, or else the first. A
     * capture on a controller that's gone is cancelled.
     */
    private fun onControllersChanged(connected: List<ConnectedController>) {
        val old = state
        val selected =
            (connected.firstOrNull { it.descriptor == pickedController } ?: connected.firstOrNull())?.descriptor
        if (selected != old.selected) cancelCapture()
        state = state.copy(controllers = connected, selected = selected)
        when {
            selected == null -> {
                bindingsRead++
                state = state.copy(bindings = emptyList())
            }
            selected != old.selected -> readBindings(selected)
        }
    }

    /**
     * Runs [change], if any, then reads [descriptor]'s bindings, on [core]; they show if it's still selected and no
     * later read was asked for.
     */
    private fun readBindings(
        descriptor: String,
        change: (() -> Unit)? = null,
    ) {
        val read = ++bindingsRead
        scope.launch {
            val loaded =
                withContext(core) {
                    change?.invoke()
                    api.getPadBindings(descriptor)
                }
            if (read != bindingsRead || state.selected != descriptor) return@launch
            state = state.copy(bindings = loaded.orEmpty())
        }
    }

    // Shows [name] bound to [binding] now, until the bindings are read again.
    private fun setBindingShown(
        name: String,
        binding: String?,
    ) {
        val bindings = state.bindings ?: return
        state = state.copy(bindings = bindings.map { if (it.name == name) it.copy(binding = binding) else it })
    }

    private fun endCapture() {
        captureId++
        captureTimer?.cancel()
        captureTimer = null
        state = state.copy(capturing = null, secondsLeft = 0)
    }

    private companion object {
        const val CAPTURE_SECONDS = 5
    }
}
