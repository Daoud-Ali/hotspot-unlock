package com.example.hotspotunlock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var hidDevice: BluetoothHidDevice? = null
    private var targetDevice: BluetoothDevice? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val MODIFIER_NONE: Byte = 0
        private const val KEY_BACKSPACE: Byte = 0x2A
        private const val KEY_ENTER: Byte = 0x28
        private const val KEY_SPACE: Byte = 0x2C
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                Log.d(TAG, "Bluetooth HID Device Profile Connected")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                Log.d(TAG, "Bluetooth HID Device Profile Disconnected")
            }
        }
    }

    init {
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun setTargetDevice(address: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device != null) {
            targetDevice = device
            return true
        }
        return false
    }

    fun isConnected(): Boolean {
        return hidDevice != null && targetDevice != null
    }

    fun getTargetDeviceName(): String {
        return targetDevice?.name ?: "No Device Selected"
    }

    /**
     * Executes unlock sequence safely off the main thread:
     * 1. 300ms wake delay
     * 2. Exactly TWO raw HID Backspaces (0x2A)
     * 3. Transmits raw password string (preserving all digits/spaces)
     * 4. 100ms pause
     * 5. Enter (0x28)
     */
    fun sendUnlockSequence(rawPassword: String, onComplete: (Boolean) -> Unit) {
        val dev = targetDevice
        val hid = hidDevice

        if (dev == null || hid == null) {
            Log.e(TAG, "Target device or HID service unavailable")
            onComplete(false)
            return
        }

        executor.execute {
            try {
                // 1. 300ms Wake Delay
                Thread.sleep(300)

                // 2. Transmit EXACTLY two raw HID Backspaces (0x2A)
                sendRawKeycode(hid, dev, KEY_BACKSPACE)
                Thread.sleep(10)
                sendRawKeycode(hid, dev, KEY_BACKSPACE)
                Thread.sleep(10)

                // 3. Process raw password (DO NOT CALL .trim())
                for (char in rawPassword) {
                    val keycode = charToHidKeycode(char)
                    if (keycode != 0.toByte()) {
                        sendRawKeycode(hid, dev, keycode)
                    }
                }

                // 4. 100ms Pause before Enter
                Thread.sleep(100)

                // 5. Transmit Enter keycode (0x28)
                sendRawKeycode(hid, dev, KEY_ENTER)

                mainHandler.post { onComplete(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error transmitting keystrokes", e)
                mainHandler.post { onComplete(false) }
            }
        }
    }

    private fun sendRawKeycode(hid: BluetoothHidDevice, device: BluetoothDevice, keycode: Byte) {
        val keyDownReport = byteArrayOf(MODIFIER_NONE, 0x00, keycode, 0x00, 0x00, 0x00, 0x00, 0x00)
        val keyUpReport = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        hid.sendReport(device, 1, keyDownReport)
        Thread.sleep(15)
        hid.sendReport(device, 1, keyUpReport)
        Thread.sleep(15)
    }

    private fun charToHidKeycode(c: Char): Byte {
        return when (c) {
            '1' -> 0x1E
            '2' -> 0x1F
            '3' -> 0x20
            '4' -> 0x21
            '5' -> 0x22
            '6' -> 0x23
            '7' -> 0x24
            '8' -> 0x25
            '9' -> 0x26
            '0' -> 0x27
            ' ' -> KEY_SPACE
            else -> 0x00
        }
    }
}
