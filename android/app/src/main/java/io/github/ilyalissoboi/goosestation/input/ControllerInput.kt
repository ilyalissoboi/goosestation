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
    private val router = KeyRouter()
    private val axisHold = AxisHold()
    private val menuTrigger = MenuTrigger()
    private val lateRelease = LateRelease()
    private val handler = Handler(Looper.getMainLooper())

    // For MenuTrigger's and LateRelease's deadlines, whichever comes first.
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
     * pads), and the rest of any press whose first key-down went there, whatever [padInput] is now ([KeyRouter]). The
     * menu's triggers are taken out first ([MenuTrigger]), and a press they send late is held down a moment
     * ([LateRelease]). Returns false for any other key, such as Back, volume or media keys, or any key while the pads
     * are the app's, so that Compose or the system handles it.
     */
    fun onKeyEvent(
        event: KeyEvent,
        padInput: Boolean,
    ): Boolean {
        // A late press of this key still waiting to be let go ends before anything else happens to the key.
        if (lateRelease.flush(event.deviceId, event.keyCode)) {
            NativeLibrary.onKey(event.deviceId, event.keyCode, false)
            schedule()
        }
        val toCore = padInput && isControllerEvent(event.source) && isPadKey(event.keyCode)
        val pressed =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return toCore
            }
        val repeat = pressed && event.repeatCount > 0
        if (!router.route(event.deviceId, event.keyCode, pressed, toCore, repeat)) return false

        // Auto-repeat changes nothing for the pad.
        if (repeat) return true
        val deviceId = event.deviceId
        val result = menuTrigger.onKey(deviceId, event.keyCode, pressed, event.eventTime)
        if (pressed && !result.openMenu && MenuTrigger.KeyPress(event.keyCode, true) !in result.send) {
            router.markHeldBack(deviceId, event.keyCode)
        }
        if (ensureConnected(deviceId)) {
            for (press in lateRelease.plan(deviceId, result.send, event.eventTime)) {
                if (press.pressed) router.markSent(deviceId, press.keyCode)
                NativeLibrary.onKey(deviceId, press.keyCode, press.pressed)
            }
        }
        schedule()
        if (result.openMenu) onMenuRequested?.invoke()
        return true
    }

    // Late presses whose time is up are let go. Select and Start held together long enough: the press held back is
    // dropped, and the menu opens.
    private fun onTimer() {
        val now = SystemClock.uptimeMillis()
        sendReleases(lateRelease.due(now))
        if (menuTrigger.onTime(now)) {
            dropHeldBack()
            onMenuRequested?.invoke()
        }
        schedule()
    }

    // Key events' times are uptimeMillis(), as the handler's are.
    private fun schedule() {
        handler.removeCallbacks(timer)
        listOfNotNull(menuTrigger.deadline(), lateRelease.deadline()).minOrNull()?.let { handler.postAtTime(timer, it) }
    }

    // Every press held back is dropped, on every device, so none is sent late after the menu or a release.
    private fun dropHeldBack() {
        menuTrigger.reset()
        router.dropHeldBack()
        schedule()
    }

    private fun sendReleases(releases: List<LateRelease.Release>) {
        for (release in releases) NativeLibrary.onKey(release.deviceId, release.keyCode, false)
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
        if (!axisHold.route(event.deviceId, padInput, deflection(event))) return false
        if (event.action != MotionEvent.ACTION_MOVE || !ensureConnected(event.deviceId)) return true

        val last = devices.getValue(event.deviceId).axes
        for ((index, axis) in AXES.withIndex()) {
            val value = event.getAxisValue(axis)
            if (value != last[index]) {
                last[index] = value
                NativeLibrary.onAxis(event.deviceId, axis, value)
            }
        }
        return true
    }

    /**
     * Lets go of everything the controllers hold, in the core and here, so the next value of each axis is sent even if
     * it's the last one. Android sends no axis events when the app loses focus, nor any input on the game list. The
     * keys held stay where they were pressed: their key-ups still go to the core, which has nothing left to release.
     * A press held back for the menu ([MenuTrigger]) is dropped: it never reached the core, so the rest of it goes to
     * Compose. A late press still held down a moment ([LateRelease]) is let go, as the menu opens too.
     */
    fun releaseAll() {
        sendReleases(lateRelease.flushAll())
        dropHeldBack()
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
        if (devices.remove(deviceId) == null) return
        for (keyCode in lateRelease.flushDevice(deviceId)) NativeLibrary.onKey(deviceId, keyCode, false)
        schedule()
        router.forget(deviceId)
        axisHold.forget(deviceId)
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
        devices[device.id] = Device(device.name, device.descriptor, FloatArray(AXES.size))
        NativeLibrary.onDeviceConnected(device.id, device.name, device.descriptor)
        publishConnected()
    }

    // How far the stick or hat Android would turn into D-pad keys is from rest. Other axes give the app no input, and
    // can rest away from 0 (a trigger on Z rests at -1), which would hold the device for good.
    private fun deflection(event: MotionEvent): Float = DPAD_AXES.maxOf { abs(event.getAxisValue(it)) }

    private fun publishConnected() {
        mutableConnected.value =
            devices.entries
                .distinctBy { it.value.descriptor }
                .map { (id, device) -> ConnectedController(id, device.name, device.descriptor) }
    }

    private class Device(
        val name: String,
        val descriptor: String,
        val axes: FloatArray,
    )

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
