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
 * The Controllers page: the [controllers] connected, each with its [labels] entry, by descriptor; the [selected] one's
 * descriptor; and the [bindings] and [hotkeys] of [bindingsFor], null until they're first read. While [bindingsFor]
 * isn't [selected], the selected one's are being read. While [capturing] is a binding's or hotkey's name, the next
 * input on that controller becomes its binding (for a hotkey, the button held with Select), unless [secondsLeft] runs
 * out first. Once a capture lands, [undo] can put back what it replaced.
 */
data class ControllersState(
    val controllers: List<ConnectedController> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val selected: String? = null,
    val bindings: List<PadBinding>? = null,
    val hotkeys: List<PadBinding>? = null,
    val bindingsFor: String? = null,
    val capturing: String? = null,
    val secondsLeft: Int = 0,
    val undo: Undo? = null,
)

/** The binding a capture replaced on the input [name]: [previous], or none. */
data class Undo(
    val name: String,
    val previous: String?,
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

    /** The latest labels read, likewise. */
    private var labelsRead = 0

    /** Changes as each capture starts or ends, so a report the main thread gets late is dropped. */
    private var captureId = 0

    /** [captureId] of the capture the core runs: set on [core], read where the core reports. */
    @Volatile private var nativeCaptureId = 0

    /** The capture running is a hotkey's, a Select chord. */
    private var capturingHotkey = false

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
        this.state = this.state.copy(selected = descriptor, undo = null)
        readBindings(descriptor)
    }

    /**
     * Captures the selected controller's next input as [name]'s binding ([onBindingCaptured]): for 5 s, counting down
     * once a second, and then it's cancelled. Its controllers' input goes to the core meanwhile ([capturingInput]).
     */
    fun capture(name: String) = startCapture(name, hotkey = false)

    /**
     * Captures the button the selected controller presses with Select as the hotkey [name]'s ([onBindingCaptured]), as
     * [capture] does a binding. Select and Start aren't taken.
     */
    fun captureHotkey(name: String) = startCapture(name, hotkey = true)

    private fun startCapture(
        name: String,
        hotkey: Boolean,
    ) {
        val state = state
        val descriptor = state.selected ?: return
        val list = if (hotkey) state.hotkeys else state.bindings
        if (!isOpen() || !showsOwnBindings(state) || list?.any { it.name == name } != true) return
        val id = ++captureId
        captureTimer?.cancel()
        capturingHotkey = hotkey
        this.state = state.copy(capturing = name, secondsLeft = CAPTURE_SECONDS, undo = null)
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
                api.startBindingCapture(descriptor, hotkey)
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

    /** Leaves the binding or hotkey [name] unbound on the selected controller. */
    fun clear(name: String) {
        val descriptor = state.selected ?: return
        if (capturingInput || !showsOwnBindings(state)) return
        val hotkey = isHotkey(name)
        setBindingShown(name, null)
        readBindings(descriptor) { set(descriptor, name, null, hotkey) }
    }

    /** Gives the selected controller its default bindings again. */
    fun reset() {
        val descriptor = state.selected ?: return
        if (capturingInput || !showsOwnBindings(state)) return
        state = state.copy(undo = null)
        readBindings(descriptor) { api.resetPadBindings(descriptor) }
    }

    /** Puts back the binding the last capture replaced, as [ControllersState.undo] says. */
    fun undo() {
        val undo = state.undo ?: return
        val descriptor = state.selected ?: return
        if (capturingInput || !showsOwnBindings(state)) return
        state = state.copy(undo = null)
        setBindingShown(undo.name, undo.previous)
        // A hotkey's binding is the chord: the button is its last part.
        val hotkey = isHotkey(undo.name)
        val previous = if (hotkey) undo.previous?.substringAfterLast('/') else undo.previous
        readBindings(descriptor) { set(descriptor, undo.name, previous, hotkey) }
    }

    /** The last capture can't be undone any more: the focus left its row. */
    fun dropUndo() {
        if (state.undo != null) state = state.copy(undo = null)
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
            val hotkey = capturingHotkey
            val previous = (if (hotkey) state.hotkeys else state.bindings)?.firstOrNull { it.name == name }?.binding
            endCapture()
            // The core removed the capture as it took the input, so the releases reach it.
            pads()?.releaseAll()
            this@ControllersPage.state = this@ControllersPage.state.copy(undo = Undo(name, previous))
            // Until the read lands, a hotkey shows as its chord with Select, without the device.
            setBindingShown(name, if (hotkey) "Button109 & $binding" else binding)
            readBindings(descriptor) { set(descriptor, name, binding, hotkey) }
        }
    }

    /**
     * The page lists [connected], and keeps the controller selected while it's among them. On opening, or once it's
     * gone, it selects the controller last picked if it's among them, or else the first: a picked controller coming
     * back doesn't take the page from the one in use. A capture on a controller that's gone is cancelled.
     */
    private fun onControllersChanged(connected: List<ConnectedController>) {
        val old = state
        val selected =
            (
                connected.firstOrNull { it.descriptor == old.selected }
                    ?: connected.firstOrNull { it.descriptor == pickedController }
                    ?: connected.firstOrNull()
            )?.descriptor
        if (selected != old.selected) cancelCapture()
        state = state.copy(controllers = connected, labels = labelsFor(connected, old.labels), selected = selected)
        if (selected != old.selected) state = state.copy(undo = null)
        readLabels(connected)
        when {
            selected == null -> {
                bindingsRead++
                state = state.copy(bindings = emptyList(), hotkeys = emptyList(), bindingsFor = null)
            }
            selected != old.selected -> readBindings(selected)
        }
    }

    /**
     * Numbers the [connected] controllers that share a name, by their places in the core's table, so the numbers stay
     * the same from one run to the next; until they're read, each is labelled with its name. One the table doesn't
     * have yet is asked for once more, as a new pad's descriptor is added a moment after it connects; one still missing
     * then keeps its name.
     */
    private fun readLabels(connected: List<ConnectedController>) {
        val read = ++labelsRead
        val shared =
            connected
                .groupBy { it.name }
                .values
                .filter { it.size > 1 }
                .flatten()
        if (shared.isEmpty()) return
        scope.launch {
            val indices =
                withContext(core) {
                    val first = shared.associate { it.descriptor to api.getControllerIndex(it.descriptor) }
                    // The core adds it after the work queued before it, this lookup's included.
                    first.mapValues { (descriptor, index) -> index ?: api.getControllerIndex(descriptor) }
                }
            if (read != labelsRead) return@launch
            val numbered =
                shared.associate { pad ->
                    pad.descriptor to
                        (indices[pad.descriptor]?.let { "${pad.name} (${it + 1})" } ?: pad.name)
                }
            state = state.copy(labels = state.labels + numbered)
        }
    }

    // Each pad's name, but a pad that still shares its name keeps the number it had, until readLabels() lands.
    private fun labelsFor(
        connected: List<ConnectedController>,
        old: Map<String, String>,
    ): Map<String, String> {
        val counts = connected.groupingBy { it.name }.eachCount()
        return connected.associate { pad ->
            pad.descriptor to (old[pad.descriptor]?.takeIf { counts.getValue(pad.name) > 1 } ?: pad.name)
        }
    }

    // The bindings shown are the selected controller's, not those of the one selected before it.
    private fun showsOwnBindings(state: ControllersState): Boolean = state.bindingsFor == state.selected

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
            val (loaded, hotkeys) =
                withContext(core) {
                    change?.invoke()
                    api.getPadBindings(descriptor) to api.getHotkeyBindings(descriptor)
                }
            if (read != bindingsRead || state.selected != descriptor) return@launch
            state = state.copy(bindings = loaded.orEmpty(), hotkeys = hotkeys.orEmpty(), bindingsFor = descriptor)
        }
    }

    // Shows [name] bound to [binding] now, until the bindings are read again.
    private fun setBindingShown(
        name: String,
        binding: String?,
    ) {
        fun List<PadBinding>.shown() = map { if (it.name == name) it.copy(binding = binding) else it }
        state = state.copy(bindings = state.bindings?.shown(), hotkeys = state.hotkeys?.shown())
    }

    private fun isHotkey(name: String) = state.hotkeys?.any { it.name == name } == true

    // On [core]: a [hotkey]'s button, or a binding.
    private fun set(
        descriptor: String,
        name: String,
        binding: String?,
        hotkey: Boolean,
    ) {
        if (hotkey) api.setHotkeyBinding(descriptor, name, binding) else api.setPadBinding(descriptor, name, binding)
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
