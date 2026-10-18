package com.example.hotspotunlock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
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
    @Volatile private var isAppRegistered = false
    @Volatile private var isConnectedState = false

    companion object {
        private const val TAG = "BluetoothHidManager"
        private const val MODIFIER_NONE: Byte = 0x00
        private const val MODIFIER_SHIFT: Byte = 0x02
        private const val KEY_BACKSPACE: Byte = 0x2A
        private const val KEY_ENTER: Byte = 0x28
        private const val KEY_SPACE: Byte = 0x2C

        // Standard Keyboard HID Report Descriptor (No Report ID)
        private val HID_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // USAGE_PAGE (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // USAGE (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // COLLECTION (Application)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard)
            0x19.toByte(), 0xE0.toByte(), //   USAGE_MINIMUM (KB LeftControl)
            0x29.toByte(), 0xE7.toByte(), //   USAGE_MAXIMUM (KB Right GUI)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x01.toByte(), //   LOGICAL_MAXIMUM (1)
            0x75.toByte(), 0x01.toByte(), //   REPORT_SIZE (1)
            0x95.toByte(), 0x08.toByte(), //   REPORT_COUNT (8)
            0x81.toByte(), 0x02.toByte(), //   INPUT (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //   REPORT_COUNT (1)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x81.toByte(), 0x01.toByte(), //   INPUT (Cnst,Ary,Abs)
            0x95.toByte(), 0x06.toByte(), //   REPORT_COUNT (6)
            0x75.toByte(), 0x08.toByte(), //   REPORT_SIZE (8)
            0x15.toByte(), 0x00.toByte(), //   LOGICAL_MINIMUM (0)
            0x25.toByte(), 0x65.toByte(), //   LOGICAL_MAXIMUM (101)
            0x05.toByte(), 0x07.toByte(), //   USAGE_PAGE (Keyboard)
            0x19.toByte(), 0x00.toByte(), //   USAGE_MINIMUM (0)
            0x29.toByte(), 0x65.toByte(), //   USAGE_MAXIMUM (101)
            0x81.toByte(), 0x00.toByte(), //   INPUT (Data,Ary,Abs)
            0xC0.toByte()                  // END_COLLECTION
        )
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(device, registered)
            isAppRegistered = registered
            Log.d(TAG, "HID App Status Registered: $registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            isConnectedState = (state == BluetoothProfile.STATE_CONNECTED)
            Log.d(TAG, "Connection State Changed: $state for ${device?.name}")
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidApp()
                Log.d(TAG, "Bluetooth HID Device Profile Connected")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                isAppRegistered = false
                isConnectedState = false
                Log.d(TAG, "Bluetooth HID Device Profile Disconnected")
            }
        }
    }

    init {
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Hotspot Unlocker",
            "Android HID Keyboard",
            "Android",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HID_DESCRIPTOR
        )
        
        // Register SDP profile with callback handler
        hid.registerApp(sdp, null, null, executor, callback)
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun setTargetDevice(address: String): Boolean {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device != null) {
            targetDevice = device
            hidDevice?.connect(device)
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
                // Ensure active HID connection
                hid.connect(dev)
                Thread.sleep(300)

                // 1. Wake & Clear prompt
                sendRawKeycode(hid, dev, MODIFIER_NONE, KEY_BACKSPACE)
                Thread.sleep(30)
                sendRawKeycode(hid, dev, MODIFIER_NONE, KEY_BACKSPACE)
                Thread.sleep(30)

                // 2. Transmit characters
                for (char in rawPassword) {
                    val (modifier, keycode) = charToHidKeycode(char)
                    if (keycode != 0.toByte()) {
                        sendRawKeycode(hid, dev, modifier, keycode)
                    }
                }

                // 3. Pause & Enter
                Thread.sleep(100)
                sendRawKeycode(hid, dev, MODIFIER_NONE, KEY_ENTER)

                mainHandler.post { onComplete(true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error transmitting keystrokes", e)
                mainHandler.post { onComplete(false) }
            }
        }
    }

    private fun sendRawKeycode(hid: BluetoothHidDevice, device: BluetoothDevice, modifier: Byte, keycode: Byte) {
        val keyDownReport = byteArrayOf(modifier, 0x00, keycode, 0x00, 0x00, 0x00, 0x00, 0x00)
        val keyUpReport = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

        // Use report ID 0 for descriptors without an explicit REPORT_ID declaration
        val sentDown = hid.sendReport(device, 0, keyDownReport)
        Log.d(TAG, "sendReport keyDown result: $sentDown")
        Thread.sleep(20)

        val sentUp = hid.sendReport(device, 0, keyUpReport)
        Log.d(TAG, "sendReport keyUp result: $sentUp")
        Thread.sleep(20)
    }

    private fun charToHidKeycode(c: Char): Pair<Byte, Byte> {
        return when (c) {
            in 'a'..'z' -> Pair(MODIFIER_NONE, (0x04 + (c - 'a')).toByte())
            in 'A'..'Z' -> Pair(MODIFIER_SHIFT, (0x04 + (c - 'A')).toByte())
            '1' -> Pair(MODIFIER_NONE, 0x1E)
            '!' -> Pair(MODIFIER_SHIFT, 0x1E)
            '2' -> Pair(MODIFIER_NONE, 0x1F)
            '@' -> Pair(MODIFIER_SHIFT, 0x1F)
            '3' -> Pair(MODIFIER_NONE, 0x20)
            '#' -> Pair(MODIFIER_SHIFT, 0x20)
            '4' -> Pair(MODIFIER_NONE, 0x21)
            '$' -> Pair(MODIFIER_SHIFT, 0x21)
            '5' -> Pair(MODIFIER_NONE, 0x22)
            '%' -> Pair(MODIFIER_SHIFT, 0x22)
            '6' -> Pair(MODIFIER_NONE, 0x23)
            '^' -> Pair(MODIFIER_SHIFT, 0x23)
            '7' -> Pair(MODIFIER_NONE, 0x24)
            '&' -> Pair(MODIFIER_SHIFT, 0x24)
            '8' -> Pair(MODIFIER_NONE, 0x25)
            '*' -> Pair(MODIFIER_SHIFT, 0x25)
            '9' -> Pair(MODIFIER_NONE, 0x26)
            '(' -> Pair(MODIFIER_SHIFT, 0x26)
            '0' -> Pair(MODIFIER_NONE, 0x27)
            ')' -> Pair(MODIFIER_SHIFT, 0x27)
            ' ' -> Pair(MODIFIER_NONE, KEY_SPACE)
            '-' -> Pair(MODIFIER_NONE, 0x2D)
            '_' -> Pair(MODIFIER_SHIFT, 0x2D)
            '=' -> Pair(MODIFIER_NONE, 0x2E)
            '+' -> Pair(MODIFIER_SHIFT, 0x2E)
            else -> Pair(MODIFIER_NONE, 0x00)
        }
    }
}
