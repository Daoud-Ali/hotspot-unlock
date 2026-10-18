package com.example.hotspotunlock

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Turns the phone into a Bluetooth HID keyboard and types the unlock sequence.
 *
 * Fixes compared with the previous version:
 *  1. Nothing touches Bluetooth in the constructor. Call [start] only AFTER the
 *     Android 12+ runtime permissions are granted (previously the HID proxy was
 *     requested before permissions existed, so registerApp() never succeeded).
 *  2. HID callbacks run on their own executor. Before, the same single thread ran the
 *     callbacks AND the sleeping unlock task, so Windows' GET_REPORT / SET_REPORT
 *     requests were never answered while typing.
 *  3. onGetReport / onSetReport are answered (Windows' HID driver expects replies).
 *  4. The descriptor now includes the standard LED output report.
 *  5. The unlock sequence waits for a real STATE_CONNECTED, retries connect(),
 *     checks every sendReport() result and reports real success / failure.
 */
@SuppressLint("MissingPermission")
class BluetoothHidManager private constructor(context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"

        @Volatile private var instance: BluetoothHidManager? = null

        /** One manager per process, shared by the Activity and the keep-alive service. */
        fun get(context: Context): BluetoothHidManager =
            instance ?: synchronized(this) {
                instance ?: BluetoothHidManager(context).also { instance = it }
            }
        private const val REPORT_ID = 1

        const val MODIFIER_NONE: Byte = 0x00
        const val MODIFIER_SHIFT: Byte = 0x02
        const val KEY_ENTER: Byte = 0x28
        const val KEY_BACKSPACE: Byte = 0x2A
        const val KEY_SPACE: Byte = 0x2C

        /** Pause between the two Backspace presses that come before the password. */
        private const val BACKSPACE_GAP_MS = 120L
        /** Time we give the laptop to show the password box after the Backspaces (tune if needed). */
        private const val WAKE_DELAY_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val KEY_HOLD_MS = 20L
        private const val KEY_GAP_MS = 20L
        /** How often the phone retries connecting while the laptop is not connected. */
        private const val RECONNECT_INTERVAL_MS = 6000L
        /** A connect attempt stuck "connecting" longer than this is cancelled and retried. */
        private const val CONNECT_STALL_MS = 10000L

        // Standard boot-compatible keyboard, Report ID 1:
        //   Input : 1 byte modifiers, 1 byte reserved, 6 bytes key codes  (8 bytes)
        //   Output: 5 bits LEDs + 3 bits padding                          (1 byte)
        private val DESCRIPTOR_INTS = intArrayOf(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, REPORT_ID,  //   Report ID (1)
            0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0,       //   Usage Minimum (Left Control)
            0x29, 0xE7,       //   Usage Maximum (Right GUI)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x08,       //   Report Count (8)
            0x81, 0x02,       //   Input (Data, Variable, Absolute)  - modifiers
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x08,       //   Report Size (8)
            0x81, 0x01,       //   Input (Constant)                  - reserved byte
            0x95, 0x05,       //   Report Count (5)
            0x75, 0x01,       //   Report Size (1)
            0x05, 0x08,       //   Usage Page (LEDs)
            0x19, 0x01,       //   Usage Minimum (Num Lock)
            0x29, 0x05,       //   Usage Maximum (Kana)
            0x91, 0x02,       //   Output (Data, Variable, Absolute) - LEDs
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x03,       //   Report Size (3)
            0x91, 0x01,       //   Output (Constant)                 - LED padding
            0x95, 0x06,       //   Report Count (6)
            0x75, 0x08,       //   Report Size (8)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x65,       //   Logical Maximum (101)
            0x05, 0x07,       //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,       //   Usage Minimum (0)
            0x29, 0x65,       //   Usage Maximum (101)
            0x81, 0x00,       //   Input (Data, Array)               - 6 key slots
            0xC0              // End Collection
        )
        val HID_DESCRIPTOR: ByteArray = ByteArray(DESCRIPTOR_INTS.size) { DESCRIPTOR_INTS[it].toByte() }
    }

    private val appContext: Context = context.applicationContext
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // IMPORTANT: two separate executors. The callback executor must never be blocked.
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile var isAppRegistered: Boolean = false
        private set
    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var proxyRequested = false
    @Volatile private var lastError: String? = null
    private var receiverRegistered = false
    @Volatile private var unlocking = false
    @Volatile private var reconnectScheduled = false
    private var tickConnectingSince = 0L

    // ------------------------------------------------------------------ lifecycle

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Safe to call repeatedly (onCreate, onResume, after permissions are granted,
     * when Bluetooth is switched on). Does nothing until permissions + Bluetooth are ready.
     */
    @Synchronized
    fun start() {
        val bt = adapter
        if (bt == null) {
            lastError = "This phone has no Bluetooth adapter"
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            lastError = "Bluetooth HID Device needs Android 9 or newer"
            return
        }
        registerStateReceiver()
        if (!hasConnectPermission() || !bt.isEnabled) return

        if (hidDevice != null) {
            if (!isAppRegistered) registerHidApp()
            return
        }
        if (proxyRequested) return

        proxyRequested = bt.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        if (!proxyRequested) lastError = "This phone does not expose the Bluetooth HID Device profile"
    }

    @Synchronized
    fun stop() {
        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "unregisterApp failed", e)
        }
        try {
            hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        } catch (e: Exception) {
            Log.w(TAG, "closeProfileProxy failed", e)
        }
        hidDevice = null
        isAppRegistered = false
        proxyRequested = false
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(stateReceiver) } catch (e: Exception) { Log.w(TAG, "unregisterReceiver failed", e) }
            receiverRegistered = false
        }
        callbackExecutor.shutdown()
        sendExecutor.shutdown()
        reconnectExecutor.shutdown()
        reconnectScheduled = false
        synchronized(Companion) { if (instance === this) instance = null }
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            stateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> start()
                BluetoothAdapter.STATE_OFF -> {
                    hidDevice = null
                    isAppRegistered = false
                    proxyRequested = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ HID profile

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            Log.d(TAG, "Bluetooth HID Device proxy connected")
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            Log.d(TAG, "Bluetooth HID Device proxy disconnected")
            hidDevice = null
            isAppRegistered = false
            proxyRequested = false
        }
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
        val accepted = hid.registerApp(sdp, null, null, callbackExecutor, hidCallback)
        Log.d(TAG, "registerApp() accepted = $accepted")
        if (!accepted) {
            lastError = "registerApp() was rejected (another app may be using the HID profile)"
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "HID app registered = $registered")
            isAppRegistered = registered
            if (registered) {
                lastError = null
                // Pre-connect to the chosen laptop (harmless if it is switched off).
                connectTargetAsync()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.d(TAG, "Connection state = $state for ${device?.name}")
        }

        // Windows asks for reports while enumerating the device. It must get an answer.
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val hid = hidDevice ?: return
            if (device == null) return
            when (type) {
                BluetoothHidDevice.REPORT_TYPE_INPUT ->
                    hid.replyReport(device, type, id, ByteArray(8))
                BluetoothHidDevice.REPORT_TYPE_OUTPUT ->
                    hid.replyReport(device, type, id, ByteArray(1))
                else ->
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        // Windows sends the LED state (Num/Caps lock). Acknowledge it.
        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            val hid = hidDevice ?: return
            if (device == null) return
            hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            Log.d(TAG, "Set protocol = $protocol")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.d(TAG, "Virtual cable unplugged by ${device?.name}")
        }
    }

    // ------------------------------------------------------------------ devices / status

    fun getPairedDevices(): List<Pair<String, String>> {
        val bt = adapter ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        return try {
            bt.bondedDevices.map { (it.name ?: "Unknown Device") to it.address }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun setTargetDevice(address: String) {
        if (targetDevice?.address == address) return
        targetDevice = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (isAppRegistered) connectTargetAsync()
    }

    fun getTargetDeviceName(): String =
        try { targetDevice?.name ?: "No Device Selected" } catch (e: SecurityException) { "Unknown Device" }

    fun isConnected(): Boolean {
        val hid = hidDevice ?: return false
        val dev = targetDevice ?: return false
        return try {
            hid.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Short status for the UI. Only the connected state contains the word "connect"
     * so the old script.js (which checks includes("connect")) still colours it correctly.
     */
    fun statusText(): String {
        val bt = adapter ?: return "No Bluetooth adapter"
        return when {
            !hasConnectPermission() -> "Bluetooth permission needed"
            !bt.isEnabled -> "Bluetooth is off"
            hidDevice == null -> "HID service starting"
            !isAppRegistered -> "HID not registered"
            isConnected() -> "Connected to " + getTargetDeviceName()
            else -> "Ready - waiting for laptop"
        }
    }

    fun diagnostics(): String {
        val bt = adapter
        val hid = hidDevice
        val target = targetDevice
        val state = try {
            if (hid != null && target != null) hid.getConnectionState(target).toString() else "n/a"
        } catch (e: SecurityException) { "no permission" }
        return buildString {
            appendLine("Android API: ${Build.VERSION.SDK_INT}  (${Build.MANUFACTURER} ${Build.MODEL})")
            appendLine("Bluetooth adapter: ${if (bt == null) "none" else if (bt.isEnabled) "on" else "off"}")
            appendLine("Connect permission: ${hasConnectPermission()}")
            appendLine("HID proxy: ${hid != null}")
            appendLine("HID app registered: $isAppRegistered")
            appendLine("Target: ${if (target == null) "none" else getTargetDeviceName()}")
            appendLine("Connection state (2=connected): $state")
            appendLine("Last error: ${lastError ?: "none"}")
        }
    }

    // ------------------------------------------------------------------ connecting

    private fun connectTargetAsync() {
        val hid = hidDevice ?: return
        val dev = targetDevice ?: return
        try {
            if (hid.getConnectionState(dev) == BluetoothProfile.STATE_DISCONNECTED) {
                hid.connect(dev)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "connect() denied", e)
        }
    }

    /**
     * Starts the background reconnect loop (called by the keep-alive service). While the laptop is
     * not connected, the phone retries every few seconds, so the link comes back by itself after
     * the laptop was shut down / restarted / woken from sleep.
     */
    @Synchronized
    fun enableAutoReconnect() {
        if (reconnectScheduled) return
        reconnectScheduled = true
        reconnectExecutor.scheduleWithFixedDelay(
            { reconnectTick() }, 2000L, RECONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun reconnectTick() {
        try {
            if (unlocking || !isAppRegistered) return
            val hid = hidDevice ?: return
            val dev = targetDevice ?: return
            when (hid.getConnectionState(dev)) {
                BluetoothProfile.STATE_CONNECTED -> tickConnectingSince = 0L
                BluetoothProfile.STATE_CONNECTING -> {
                    val now = SystemClock.elapsedRealtime()
                    if (tickConnectingSince == 0L) {
                        tickConnectingSince = now
                    } else if (now - tickConnectingSince > CONNECT_STALL_MS) {
                        Log.d(TAG, "connect stalled, cancelling and retrying")
                        hid.disconnect(dev)
                        tickConnectingSince = 0L
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    tickConnectingSince = 0L
                    Log.d(TAG, "auto-reconnect: connect() to ${dev.name}")
                    hid.connect(dev)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconnect tick failed", e)
        }
    }

    /** Blocks (on the send thread) until the laptop is really connected, retrying connect(). */
    private fun ensureConnected(hid: BluetoothHidDevice, dev: BluetoothDevice): Boolean {
        // Already connected (kept alive by the service): no waiting at all.
        if (hid.getConnectionState(dev) == BluetoothProfile.STATE_CONNECTED) return true

        val start = SystemClock.elapsedRealtime()
        var lastAttempt = 0L
        var connectingSince = 0L
        while (SystemClock.elapsedRealtime() - start < CONNECT_TIMEOUT_MS) {
            val now = SystemClock.elapsedRealtime()
            when (hid.getConnectionState(dev)) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Thread.sleep(300) // let Windows finish bringing the HID device up
                    return true
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectingSince = 0L
                    if (now - lastAttempt > 3000) {
                        Log.d(TAG, "connect() to ${dev.name}")
                        hid.connect(dev)
                        lastAttempt = now
                    }
                }
                else -> { // connecting
                    if (connectingSince == 0L) {
                        connectingSince = now
                    } else if (now - connectingSince > 6000) {
                        hid.disconnect(dev) // stuck: cancel, the loop will retry
                        connectingSince = 0L
                        lastAttempt = 0L
                    }
                }
            }
            Thread.sleep(100)
        }
        return false
    }

    // ------------------------------------------------------------------ typing

    /** Returns (modifier, usage id) for a US-layout character, or null if unsupported. */
    private fun charToHid(c: Char): Pair<Byte, Byte>? {
        val none = MODIFIER_NONE
        val shift = MODIFIER_SHIFT
        return when (c) {
            in 'a'..'z' -> none to (0x04 + (c - 'a')).toByte()
            in 'A'..'Z' -> shift to (0x04 + (c - 'A')).toByte()
            in '1'..'9' -> none to (0x1E + (c - '1')).toByte()
            '0' -> none to 0x27.toByte()
            '!' -> shift to 0x1E.toByte()
            '@' -> shift to 0x1F.toByte()
            '#' -> shift to 0x20.toByte()
            '$' -> shift to 0x21.toByte()
            '%' -> shift to 0x22.toByte()
            '^' -> shift to 0x23.toByte()
            '&' -> shift to 0x24.toByte()
            '*' -> shift to 0x25.toByte()
            '(' -> shift to 0x26.toByte()
            ')' -> shift to 0x27.toByte()
            ' ' -> none to KEY_SPACE
            '-' -> none to 0x2D.toByte()
            '_' -> shift to 0x2D.toByte()
            '=' -> none to 0x2E.toByte()
            '+' -> shift to 0x2E.toByte()
            '[' -> none to 0x2F.toByte()
            '{' -> shift to 0x2F.toByte()
            ']' -> none to 0x30.toByte()
            '}' -> shift to 0x30.toByte()
            '\\' -> none to 0x31.toByte()
            '|' -> shift to 0x31.toByte()
            ';' -> none to 0x33.toByte()
            ':' -> shift to 0x33.toByte()
            '\'' -> none to 0x34.toByte()
            '"' -> shift to 0x34.toByte()
            '`' -> none to 0x35.toByte()
            '~' -> shift to 0x35.toByte()
            ',' -> none to 0x36.toByte()
            '<' -> shift to 0x36.toByte()
            '.' -> none to 0x37.toByte()
            '>' -> shift to 0x37.toByte()
            '/' -> none to 0x38.toByte()
            '?' -> shift to 0x38.toByte()
            else -> null
        }
    }

    /** One key press + release. Report payload = [modifier, 0, key, 0, 0, 0, 0, 0] with Report ID 1. */
    private fun sendKey(hid: BluetoothHidDevice, dev: BluetoothDevice, modifier: Byte, keycode: Byte): Boolean {
        val down = byteArrayOf(modifier, 0, keycode, 0, 0, 0, 0, 0)
        val up = ByteArray(8)
        val okDown = hid.sendReport(dev, REPORT_ID, down)
        Thread.sleep(KEY_HOLD_MS)
        val okUp = hid.sendReport(dev, REPORT_ID, up)
        Thread.sleep(KEY_GAP_MS)
        if (!okDown || !okUp) Log.e(TAG, "sendReport failed (down=$okDown, up=$okUp)")
        return okDown && okUp
    }

    /**
     * Wakes the lock screen, types [rawPassword] exactly as given (spaces preserved) and presses Enter.
     * [onComplete] is always invoked on the main thread with (success, message).
     */
    fun sendUnlockSequence(rawPassword: String, onComplete: (Boolean, String) -> Unit) {
        fun finish(ok: Boolean, msg: String) = mainHandler.post { onComplete(ok, msg) }

        val hid = hidDevice
        val dev = targetDevice
        when {
            hid == null || !isAppRegistered -> {
                finish(false, "HID service is not ready. Check Bluetooth permissions, then reopen the app.")
                return
            }
            dev == null -> {
                finish(false, "No laptop selected. Pick it in Settings.")
                return
            }
        }
        val bad = rawPassword.firstOrNull { charToHid(it) == null }
        if (bad != null) {
            finish(false, "Password contains a character that can't be typed over HID: '$bad'")
            return
        }

        sendExecutor.execute {
            unlocking = true
            try {
                if (!ensureConnected(hid!!, dev!!)) {
                    finish(
                        false,
                        "Could not connect to ${dev.name}. Make sure the laptop is on and that Windows " +
                            "has been paired to this phone as a keyboard (see Settings > Pair as keyboard)."
                    )
                    return@execute
                }

                // Always press Backspace twice first (wakes the screen / clears the field), then type.
                sendKey(hid, dev, MODIFIER_NONE, KEY_BACKSPACE)
                Thread.sleep(BACKSPACE_GAP_MS)
                sendKey(hid, dev, MODIFIER_NONE, KEY_BACKSPACE)
                Thread.sleep(WAKE_DELAY_MS)

                for (ch in rawPassword) {
                    val (mod, key) = charToHid(ch)!!
                    if (!sendKey(hid, dev, mod, key)) {
                        finish(false, "Bluetooth link dropped while typing.")
                        return@execute
                    }
                }

                Thread.sleep(100)
                if (!sendKey(hid, dev, MODIFIER_NONE, KEY_ENTER)) {
                    finish(false, "Bluetooth link dropped before Enter was sent.")
                    return@execute
                }
                finish(true, "Unlock signal sent!")
            } catch (e: Exception) {
                Log.e(TAG, "Error transmitting keystrokes", e)
                finish(false, "Error sending keystrokes: ${e.message}")
            } finally {
                unlocking = false
            }
        }
    }
}
