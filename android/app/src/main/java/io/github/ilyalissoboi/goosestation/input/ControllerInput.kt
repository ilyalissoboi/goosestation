// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** A controller as the Controllers page lists it: its first device ID, its name, and its [descriptor], its key. */
data class ConnectedController(
    val deviceId: Int,
    val name: String,
    val descriptor: String,
)

/**
 * Passes controllers and their input to the core. Every device is reported with [NativeLibrary.onDeviceConnected]
 * before any of its events: those present at [start], those connected later, and any other device whose gamepad events
 * arrive first. One instance lives as long as the process, listening from [start] on. Call everything on the main
 * thread.
 */
class ControllerInput(context: Context) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(InputManager::class.java)

    // The devices reported to the core, in the order they were, with the last value sent for each of AXES.
    private val devices = LinkedHashMap<Int, Device>()
    private val keys = PadKeys()
    private val axisHold = AxisHold()
    private val axisMemory = AxisMemory(AXES)
    private val handler = Handler(Looper.getMainLooper())

    // For the keys' next deadline: a hold's end, or a late key-up's.
    private val timer = Runnable { onTimer() }
    private val mutableConnected = MutableStateFlow(emptyList<ConnectedController>())

    /** The controllers reported to the core, one per descriptor (its first device), in the order they connected. */
    val connected: StateFlow<List<ConnectedController>> = mutableConnected.asStateFlow()

    /** Called when a controller asks for the game's menu: Mode, or Select and Start held together ([MenuTrigger]). */
    var onMenuRequested: (() -> Unit)? = null

    fun start() {
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        for (id in InputDevice.getDeviceIds()) {
            InputDevice.getDevice(id)?.takeIf { isController(it) }?.let { connect(it) }
        }
    }

    /**
     * Sends a controller's gamepad button or D-pad key to the core while [padInput] (a game or a capture takes the
     * pads), and the rest of any press whose first key-down went there, whatever [padInput] is now. The menu's
     * triggers are taken out first, a press they send late is held down a moment, and a cancelled key-up (the window
     * lost focus) never sends a press held back ([PadKeys]). Returns false for any other key, such as Back, volume or
     * media keys, or any key while the pads are the app's, so that Compose or the system handles it.
     */
    fun onKeyEvent(
        event: KeyEvent,
        padInput: Boolean,
    ): Boolean {
        val toCore = padInput && isControllerEvent(event.source) && isPadKey(event.keyCode)
        val pressed =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return toCore
            }
        val outcome =
            keys.onKey(
                deviceId = event.deviceId,
                keyCode = event.keyCode,
                pressed = pressed,
                repeat = pressed && event.repeatCount > 0,
                canceled = (event.flags and KeyEvent.FLAG_CANCELED) != 0,
                toCore = toCore,
                // Both are uptimeMillis(), as the handler's times are.
                eventMs = event.eventTime,
                nowMs = SystemClock.uptimeMillis(),
            )
        if (outcome.send.isNotEmpty() && ensureConnected(event.deviceId)) {
            for (press in outcome.send) NativeLibrary.onKey(event.deviceId, press.keyCode, press.pressed)
        }
        schedule()
        if (outcome.openMenu) onMenuRequested?.invoke()
        return outcome.toCore
    }

    // Late presses whose time is up are let go. Select and Start held together long enough: the menu opens.
    private fun onTimer() {
        val tick = keys.onTime(SystemClock.uptimeMillis())
        sendReleases(tick.releases)
        schedule()
        if (tick.openMenu) onMenuRequested?.invoke()
    }

    private fun schedule() {
        handler.removeCallbacks(timer)
        keys.deadline()?.let { handler.postAtTime(timer, it) }
    }

    private fun sendReleases(releases: List<LateRelease.Release>) {
        for (release in releases) {
            if (devices.containsKey(release.deviceId)) NativeLibrary.onKey(release.deviceId, release.keyCode, false)
        }
    }

    /**
     * Sends a controller's changed axes to the core while [padInput], and after that while a stick it was given stays
     * pushed ([AxisHold]). Returns false for an event that isn't a controller's, or that the app takes.
     */
    fun onMotionEvent(
        event: MotionEvent,
        padInput: Boolean,
    ): Boolean {
        if (!isControllerEvent(event.source)) return false
        // Every motion, forwarded or not, for sendAxes().
        if (event.action == MotionEvent.ACTION_MOVE) axisMemory.record(event.deviceId) { event.getAxisValue(it) }
        val bar = devices[event.deviceId]?.bar ?: AxisHold.barFor(0f)
        val route = axisHold.route(event.deviceId, padInput, deflection(event), bar)
        if (route == AxisHold.Route.App) return false
        if (event.action != MotionEvent.ACTION_MOVE || !ensureConnected(event.deviceId)) return true

        val last = devices.getValue(event.deviceId).axes
        for ((index, axis) in AXES.withIndex()) {
            val value = event.getAxisValue(axis)
            if (value != last[index]) {
                last[index] = value
                NativeLibrary.onAxis(event.deviceId, axis, value)
            }
        }
        // The hold ends under the bar, not at 0: the core is told the stick is at rest, so a game resumed with the
        // stick untouched doesn't see it pushed.
        if (route == AxisHold.Route.CoreThenRest) {
            for ((index, axis) in AXES.withIndex()) {
                if (axis in DPAD_AXES && last[index] != 0f) {
                    last[index] = 0f
                    NativeLibrary.onAxis(event.deviceId, axis, 0f)
                }
            }
        }
        return true
    }

    /**
     * Sends the core each of [descriptor]'s devices' latest axis values, whether they were forwarded or not, so that a
     * binding capture starting next knows which axes are at rest, and takes a centred stick's first push (a flick).
     * Call it after any [releaseAll] before the capture, which forgets them in the core. A device that hasn't moved
     * since it connected sends nothing: its values aren't known.
     */
    fun sendAxes(descriptor: String) {
        axisMemory.replay(descriptor, devices) { deviceId, axis, value -> NativeLibrary.onAxis(deviceId, axis, value) }
    }

    /**
     * Lets go of everything the controllers hold, in the core and here, so the next value of each axis is sent even if
     * it's the last one. Android sends no axis events when the app loses focus, nor any input on the game list. The
     * keys held stay where they were pressed: their key-ups still go to the core, which has nothing left to release.
     * A press held back for the menu is dropped: it never reached the core, so the rest of it goes to Compose. A late
     * press still held down a moment is let go, as the menu opens too ([PadKeys]).
     */
    fun releaseAll() {
        sendReleases(keys.releaseAll())
        schedule()
        forgetAxes()
        NativeLibrary.releaseAllInputs()
    }

    private fun forgetAxes() {
        for (device in devices.values) device.axes.fill(0f)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        InputDevice.getDevice(deviceId)?.takeIf { isController(it) }?.let { connect(it) }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        // Even one never reported to the core: its motion is remembered all the same.
        axisMemory.forget(deviceId)
        axisHold.forget(deviceId)
        if (devices.remove(deviceId) == null) return
        for (keyCode in keys.forget(deviceId)) NativeLibrary.onKey(deviceId, keyCode, false)
        schedule()
        NativeLibrary.onDeviceDisconnected(deviceId)
        publishConnected()
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    // Reports a device sending controller events that wasn't reported yet. False if it's gone already.
    private fun ensureConnected(deviceId: Int): Boolean {
        if (devices.containsKey(deviceId)) return true
        val device = InputDevice.getDevice(deviceId) ?: return false
        connect(device)
        return true
    }

    private fun connect(device: InputDevice) {
        if (devices.containsKey(device.id)) return
        val bar = AxisHold.barFor(dpadFlat(device))
        devices[device.id] = Device(device.name, device.descriptor, FloatArray(AXES.size), bar)
        NativeLibrary.onDeviceConnected(device.id, device.name, device.descriptor)
        publishConnected()
    }

    // How far the stick or hat Android would turn into D-pad keys is from rest. Other axes give the app no input, and
    // can rest away from 0 (a trigger on Z rests at -1), which would hold the device for good.
    private fun deflection(event: MotionEvent): Float = DPAD_AXES.maxOf { abs(event.getAxisValue(it)) }

    // The largest dead zone the device declares for those axes, or 0 when it declares none.
    private fun dpadFlat(device: InputDevice): Float =
        device.motionRanges.filter { it.axis in DPAD_AXES }.maxOfOrNull { it.flat } ?: 0f

    private fun publishConnected() {
        mutableConnected.value =
            devices.entries
                .distinctBy { it.value.descriptor }
                .map { (id, device) -> ConnectedController(id, device.name, device.descriptor) }
    }

    private class Device(
        val name: String,
        override val descriptor: String,
        override val axes: FloatArray,
        // Where its held stick counts as back at rest (AxisHold.barFor).
        val bar: Float,
    ) : SentAxes

    private companion object {
        val AXES =
            intArrayOf(
                MotionEvent.AXIS_X,
                MotionEvent.AXIS_Y,
                MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_LTRIGGER,
                MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_BRAKE,
                MotionEvent.AXIS_GAS,
                MotionEvent.AXIS_HAT_X,
                MotionEvent.AXIS_HAT_Y,
            )

        val DPAD_AXES =
            intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)

        // KeyEvent.isGamepadButton() covers BUTTON_A to BUTTON_MODE and BUTTON_1 to BUTTON_16. Back is neither that nor
        // a D-pad key, so it stays the app's.
        fun isPadKey(keyCode: Int): Boolean =
            KeyEvent.isGamepadButton(keyCode) ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER

        fun isControllerEvent(source: Int): Boolean =
            hasSource(source, InputDevice.SOURCE_GAMEPAD) ||
                hasSource(source, InputDevice.SOURCE_JOYSTICK) ||
                hasSource(source, InputDevice.SOURCE_DPAD)

        fun isController(device: InputDevice): Boolean =
            !device.isVirtual &&
                (hasSource(device.sources, InputDevice.SOURCE_GAMEPAD) ||
                    hasSource(device.sources, InputDevice.SOURCE_JOYSTICK))

        fun hasSource(sources: Int, source: Int): Boolean = (sources and source) == source
    }
}
