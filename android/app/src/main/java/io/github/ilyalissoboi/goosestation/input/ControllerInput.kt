// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.ilyalissoboi.goosestation.core.NativeLibrary

/**
 * Passes controllers and their input to the core. Every device is reported with [NativeLibrary.onDeviceConnected]
 * before any of its events: those present at [start], those connected later, and any other device whose gamepad events
 * arrive first. One instance lives as long as the process, listening from [start] on. Call everything on the main
 * thread.
 */
class ControllerInput(context: Context) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(InputManager::class.java)

    // The devices reported to the core, with the last value sent for each of AXES.
    private val devices = HashMap<Int, FloatArray>()

    fun start() {
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        for (id in InputDevice.getDeviceIds()) {
            InputDevice.getDevice(id)?.takeIf { isController(it) }?.let { connect(it) }
        }
    }

    /**
     * Sends a controller's gamepad button or D-pad key to the core. Returns false for any other key, such as Back,
     * volume or media keys, so that the system handles it.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isControllerEvent(event.source) || !isPadKey(event.keyCode)) return false
        val pressed =
            when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return true
            }

        // Auto-repeat changes nothing for the pad.
        if (pressed && event.repeatCount > 0) return true
        if (ensureConnected(event.deviceId)) NativeLibrary.onKey(event.deviceId, event.keyCode, pressed)
        return true
    }

    /** Sends a controller's changed axes to the core. Returns false for an event that isn't a controller's. */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!isControllerEvent(event.source)) return false
        if (event.action != MotionEvent.ACTION_MOVE || !ensureConnected(event.deviceId)) return true

        val last = devices.getValue(event.deviceId)
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
     * it's the last one. Android sends no axis events when the app loses focus, nor any input on the game list.
     */
    fun releaseAll() {
        forgetAxes()
        NativeLibrary.releaseAllInputs()
    }

    private fun forgetAxes() {
        for (last in devices.values) last.fill(0f)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        InputDevice.getDevice(deviceId)?.takeIf { isController(it) }?.let { connect(it) }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (devices.remove(deviceId) != null) NativeLibrary.onDeviceDisconnected(deviceId)
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
        devices[device.id] = FloatArray(AXES.size)
        NativeLibrary.onDeviceConnected(device.id, device.name, device.descriptor)
    }

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
