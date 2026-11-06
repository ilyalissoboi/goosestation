// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.input.ConnectedController
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.MacroSettings
import io.github.ilyalissoboi.goosestation.settings.NameList
import io.github.ilyalissoboi.goosestation.settings.PortSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Controllers page: the [controllers] connected, each with its [labels] entry, by descriptor; the [selected] one's
 * descriptor; and the [bindings] and [hotkeys] of [bindingsFor], null until they're first read, with its port's
 * controller type read with them ([bindingsType], as the core names it). While [bindingsFor] isn't [selected], the
 * selected one's are being read. While [capturing] is a binding's or hotkey's name, the next input on that controller
 * becomes its binding (for a hotkey, the button held with Select), unless [secondsLeft] runs out first. Once a capture
 * lands, [undo] can put back what it replaced.
 */
data class ControllersState(
    val controllers: List<ConnectedController> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val selected: String? = null,
    val bindings: List<PadBinding>? = null,
    val hotkeys: List<PadBinding>? = null,
    val bindingsFor: String? = null,
    val bindingsType: String? = null,
    val capturing: String? = null,
    val secondsLeft: Int = 0,
    val undo: Undo? = null,
    /** Each controller's pad (0-based) and rumble strength in percent, by descriptor, as last read or set. */
    val ports: Map<String, Int> = emptyMap(),
    val rumble: Map<String, Int> = emptyMap(),
    /** The ports a controller can be put on, and any a controller is on that isn't among them, noted. */
    val offered: List<Choice<Int>> = emptyList(),
    /** Each offered pad's controller type, as settings.ini has it. */
    val portTypes: Map<Int, String> = emptyMap(),
    /** Multitap, [PortSettings.MULTITAP]; null until read. */
    val multitap: SettingValues? = null,
    /** Which of the page's views shows. */
    val view: ControllersView = ControllersView.Hub,
    /** The open port's type's settings, and their values; null values until read. */
    val typeSettings: List<TypeSetting> = emptyList(),
    val portValues: SettingValues? = null,
    /** The selected controller's macro triggers on its port, read with its bindings. */
    val macros: List<PadBinding>? = null,
    /** The open macro's settings ([MacroSettings.settings]) and the inputs its port's type has for it to press. */
    val macroValues: SettingValues? = null,
    val macroInputs: List<PadBinding> = emptyList(),
    /** The core's controller types (no pointer ones) and multitap modes, read with the hub. */
    val typeNames: List<Choice<String>> = emptyList(),
    val multitapNames: List<Choice<String>> = emptyList(),
    /** The view Back last left, whose row the view it went to focuses; null on opening. */
    val cameFrom: ControllersView? = null,
)

/** The Controllers page's views: the hub, a port's page, one of a port's macros, and the hotkeys. */
sealed interface ControllersView {
    data object Hub : ControllersView

    data class Port(
        val pad: Int,
    ) : ControllersView

    data class Macro(
        val pad: Int,
        val macro: Int,
    ) : ControllersView

    data object Hotkeys : ControllersView
}

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
    private val settings: CoreSettings,
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

    // The latest reads of the hub, the open port and the open macro, so an older one that comes back later is dropped.
    private var hubRead = 0
    private var portRead = 0
    private var macroRead = 0

    /** The pad [ControllersState.portValues] were read for, while they're shown. */
    private var valuesPad: Int? = null

    /** The core's type and multitap names are shown: set on the main thread, read on [core]. */
    @Volatile private var namesShown = false

    /** Follows the controllers connected while the page is open, from no bindings read yet. */
    fun open() {
        controllersJob?.cancel()
        // The core's names stay: they were read once.
        state = ControllersState(typeNames = state.typeNames, multitapNames = state.multitapNames)
        val input = pads() ?: return
        controllersJob = scope.launch { input.connected.collect(::onControllersChanged) }
    }

    /** Puts the controller with [descriptor] on [pad]; its bindings follow it there. */
    fun setPort(
        descriptor: String,
        pad: Int,
    ) {
        if (capturingInput || state.ports[descriptor] == pad) return
        state = state.copy(ports = state.ports + (descriptor to pad))
        scope.launch {
            withContext(core) { api.setControllerPort(descriptor, pad) }
            readHub()
            // Its bindings are its new port's type's now.
            if (state.selected == descriptor) readBindings(descriptor)
        }
    }

    /** Sets the controller with [descriptor]'s rumble strength, 0 to 100 percent. */
    fun setRumble(
        descriptor: String,
        percent: Int,
    ) {
        if (state.rumble[descriptor] == percent) return
        state = state.copy(rumble = state.rumble + (descriptor to percent))
        scope.launch { withContext(core) { api.setRumbleStrength(descriptor, percent) } }
    }

    /** Saves multitap's change to [values], then reads the ports offered again, as they follow it. */
    fun setMultitap(values: SettingValues) {
        val old = state.multitap ?: return
        if (values == old) return
        state = state.copy(multitap = values)
        scope.launch {
            withContext(core) {
                values.save(settings, old)
                api.applySettings()
            }
            readHub()
        }
    }

    /** Opens [pad]'s page: its type and settings, and the bindings of a controller on it. */
    fun openPort(pad: Int) = show(ControllersView.Port(pad))

    /** Opens [pad]'s [macro] (1 to [MacroSettings.COUNT]). */
    fun openMacro(
        pad: Int,
        macro: Int,
    ) = show(ControllersView.Macro(pad, macro))

    /** Opens the hotkeys, for any connected controller. */
    fun openHotkeys() = show(ControllersView.Hotkeys)

    /** One view up: a macro to its port, a port or the hotkeys to the hub. False on the hub, which the page leaves. */
    fun back(): Boolean {
        val view = state.view
        when (view) {
            ControllersView.Hub -> return false
            is ControllersView.Macro -> show(ControllersView.Port(view.pad))
            else -> show(ControllersView.Hub)
        }
        state = state.copy(cameFrom = view)
        return true
    }

    /** Sets [pad]'s controller [type]; each controller on it gets the type's defaults where it has none. */
    fun setType(
        pad: Int,
        type: String,
    ) {
        if (capturingInput || state.portTypes[pad] == type) return
        state = state.copy(portTypes = state.portTypes + (pad to type), typeSettings = emptyList(), portValues = null)
        portRead++
        scope.launch {
            withContext(core) { api.setPortType(pad, type) }
            readPort(pad)
            state.selected?.let { readBindings(it) }
        }
    }

    /** Saves the open port's change to [values], only the settings that changed. */
    fun changePort(values: SettingValues) {
        val old = state.portValues ?: return
        if (values == old) return
        state = state.copy(portValues = values)
        scope.launch {
            withContext(core) {
                values.save(settings, old)
                api.applySettings()
            }
        }
    }

    /** Saves the open macro's change to [values]. */
    fun changeMacro(values: SettingValues) {
        val old = state.macroValues ?: return
        if (values == old) return
        state = state.copy(macroValues = values)
        scope.launch {
            withContext(core) {
                values.save(settings, old)
                api.applySettings()
            }
        }
    }

    // Shows [view], selecting a controller it covers, and reads what it shows.
    private fun show(view: ControllersView) {
        if (view == state.view) return
        cancelCapture()
        state = state.copy(view = view, undo = null)
        when (view) {
            is ControllersView.Port -> readPort(view.pad)
            is ControllersView.Macro -> readMacro(view.pad, view.macro)
            else -> {}
        }
        reselect()
    }

    // The controller the view shows: the one selected, or the one picked last, or the first, among those it covers.
    private fun reselect() {
        val state = state
        val candidates = candidates(state, state.controllers)
        val selected =
            (
                candidates.firstOrNull { it.descriptor == state.selected }
                    ?: candidates.firstOrNull { it.descriptor == pickedController }
                    ?: candidates.firstOrNull()
            )?.descriptor
        this.state = state.copy(selected = selected)
        if (selected == null) {
            bindingsRead++
            this.state =
                this.state.copy(bindings = emptyList(), hotkeys = emptyList(), macros = emptyList(), bindingsFor = null)
        } else if (selected != state.selected || state.bindingsFor != selected) {
            readBindings(selected)
        }
    }

    // A port's views cover the controllers on its pad; the hub and the hotkeys cover them all.
    private fun candidates(
        state: ControllersState,
        connected: List<ConnectedController>,
    ): List<ConnectedController> {
        val pad =
            when (val view = state.view) {
                is ControllersView.Port -> view.pad
                is ControllersView.Macro -> view.pad
                else -> return connected
            }
        return connected.filter { (state.ports[it.descriptor] ?: 0) == pad }
    }

    // Reads each controller's port and rumble, the ports offered, their types and multitap.
    private fun readHub() {
        val read = ++hubRead
        val descriptors = state.controllers.map { it.descriptor }
        scope.launch {
            val hub =
                withContext(core) {
                    val ports = descriptors.mapNotNull { d -> api.getControllerPort(d)?.let { d to it } }.toMap()
                    val rumble = descriptors.mapNotNull { d -> api.getRumbleStrength(d)?.let { d to it } }.toMap()
                    val offered = api.getOfferedPorts()
                    val pads = (offered.map { it.value } + ports.values).distinct()
                    val types = pads.mapNotNull { pad -> api.getPortType(pad)?.let { pad to it } }.toMap()
                    val multitap = SettingValues.read(settings, listOf(PortSettings.MULTITAP))
                    // They don't change: read until a read that has them is shown, null after.
                    val names = !namesShown
                    val typeNames = if (names) api.getSettingNames(NameList.ControllerType.kind) else null
                    val modes = if (names) api.getSettingNames(NameList.MultitapMode.kind) else null
                    Hub(ports, rumble, offered, types, multitap, typeNames, modes)
                }
            if (read != hubRead) return@launch
            val lost =
                hub.ports.values
                    .distinct()
                    .filter { pad -> hub.offered.none { it.value == pad } }
                    .map { Choice(it, portLabel(it), NOT_USED) }
            state =
                state.copy(
                    ports = hub.ports,
                    rumble = hub.rumble,
                    // The core's order, a lost port in its place in it.
                    offered = (hub.offered + lost).sortedBy { PORT_ORDER.indexOf(it.value) },
                    portTypes = hub.types,
                    multitap = hub.multitap,
                    typeNames = hub.typeNames ?: state.typeNames,
                    multitapNames = hub.multitapNames ?: state.multitapNames,
                )
            if (hub.typeNames != null) namesShown = true
            // A port's view covers the controllers on it, which the read may have changed.
            reselect()
        }
    }

    // Reads [pad]'s type and its settings, for its page.
    private fun readPort(pad: Int) {
        val read = ++portRead
        // Another port's values hold another port's settings: none show until this one's are read. The same port's
        // stay meanwhile (back from one of its macros), so its page keeps its rows and focus.
        if (valuesPad != pad) {
            valuesPad = null
            state = state.copy(typeSettings = emptyList(), portValues = null)
        }
        scope.launch {
            val (type, info, values) =
                withContext(core) {
                    val type = api.getPortType(pad).orEmpty()
                    val info = api.getTypeSettings(type)
                    // With each macro's inputs, for the macros' rows.
                    val macros = (1..MacroSettings.COUNT).map { MacroSettings.binds(pad, it) }
                    Triple(type, info, SettingValues.read(settings, PortSettings.settings(pad, info) + macros))
                }
            if (read != portRead || state.view != ControllersView.Port(pad)) return@launch
            valuesPad = pad
            state = state.copy(portTypes = state.portTypes + (pad to type), typeSettings = info, portValues = values)
        }
    }

    // Reads [pad]'s [macro]'s settings and the inputs its type has.
    private fun readMacro(
        pad: Int,
        macro: Int,
    ) {
        val read = ++macroRead
        state = state.copy(macroValues = null)
        scope.launch {
            val (values, inputs) =
                withContext(core) {
                    SettingValues.read(settings, MacroSettings.settings(pad, macro)) to
                        api.getTypeInputs(api.getPortType(pad).orEmpty())
                }
            if (read != macroRead || state.view != ControllersView.Macro(pad, macro)) return@launch
            state = state.copy(macroValues = values, macroInputs = inputs)
        }
    }

    private data class Hub(
        val ports: Map<String, Int>,
        val rumble: Map<String, Int>,
        val offered: List<Choice<Int>>,
        val types: Map<Int, String>,
        val multitap: SettingValues,
        val typeNames: List<Choice<String>>?,
        val multitapNames: List<Choice<String>>?,
    )

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
        if (descriptor == state.selected || candidates(state, state.controllers).none { it.descriptor == descriptor }) {
            return
        }
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
        val list = if (hotkey) state.hotkeys else state.bindings.orEmpty() + state.macros.orEmpty()
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
            val previous =
                (if (hotkey) state.hotkeys else state.bindings.orEmpty() + state.macros.orEmpty())
                    ?.firstOrNull { it.name == name }
                    ?.binding
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
        val candidates = candidates(old, connected)
        val selected =
            (
                candidates.firstOrNull { it.descriptor == old.selected }
                    ?: candidates.firstOrNull { it.descriptor == pickedController }
                    ?: candidates.firstOrNull()
            )?.descriptor
        if (selected != old.selected) cancelCapture()
        state = state.copy(controllers = connected, labels = labelsFor(connected, old.labels), selected = selected)
        if (selected != old.selected) state = state.copy(undo = null)
        readLabels(connected)
        when {
            selected == null -> {
                bindingsRead++
                state =
                    state.copy(bindings = emptyList(), hotkeys = emptyList(), macros = emptyList(), bindingsFor = null)
            }
            selected != old.selected -> readBindings(selected)
        }
        // New controllers' ports and strengths, or the first read.
        if (connected.map { it.descriptor }.toSet() != old.controllers.map { it.descriptor }.toSet() ||
            old.multitap == null
        ) {
            readHub()
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
            val (loaded, hotkeys, macros) =
                withContext(core) {
                    change?.invoke()
                    Triple(
                        api.getPadBindings(descriptor),
                        api.getHotkeyBindings(descriptor),
                        api.getMacroBindings(descriptor),
                    )
                }
            val type = withContext(core) { api.getControllerPort(descriptor)?.let { api.getPortType(it) } }
            if (read != bindingsRead || state.selected != descriptor) return@launch
            state =
                state.copy(
                    bindings = loaded.orEmpty(),
                    hotkeys = hotkeys.orEmpty(),
                    macros = macros.orEmpty(),
                    bindingsFor = descriptor,
                    bindingsType = type,
                )
        }
    }

    // Shows [name] bound to [binding] now, until the bindings are read again.
    private fun setBindingShown(
        name: String,
        binding: String?,
    ) {
        fun List<PadBinding>.shown() = map { if (it.name == name) it.copy(binding = binding) else it }
        state =
            state.copy(
                bindings = state.bindings?.shown(),
                hotkeys = state.hotkeys?.shown(),
                macros = state.macros?.shown(),
            )
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

        /** The order the core offers the pads in ([NativeLibrary.getOfferedPorts], desktop's): a port's slots together. */
        val PORT_ORDER = listOf(0, 2, 3, 4, 1, 5, 6, 7)

        /** The note on a port a controller is on that no game's multitap offers. */
        const val NOT_USED = "No game uses this port"

        /**
         * A multitap slot's label, for a port the core no longer offers, as `Controller::ConvertPadToPortAndSlot` lays
         * the pads out: 0 and 1 are Ports 1 and 2, 2 to 4 are 1B to 1D, 5 to 7 are 2B to 2D.
         */
        fun portLabel(pad: Int): String =
            when (pad) {
                0, 1 -> "Port ${pad + 1}"
                in 2..4 -> "Port 1${'A' + (pad - 1)}"
                else -> "Port 2${'A' + (pad - 4)}"
            }
    }
}
