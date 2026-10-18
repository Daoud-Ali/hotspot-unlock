package com.example.hotspotunlock

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PRIMARY_URL = "https://hotspot-unlock.pages.dev"
        private const val FALLBACK_URL = "file:///android_asset/index.html"
        private const val PREFS_NAME = "HotspotUnlockPrefs"
        private const val KEY_PIN = "app_pin"
        private const val KEY_PASSWORD = "windows_password"
        private const val KEY_DEVICE_ADDR = "target_device_addr"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothManager: BluetoothHidManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var unlockInProgress = false

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // The constructor no longer touches Bluetooth, so this is safe before permissions exist.
        bluetoothManager = BluetoothHidManager.get(this) // shared with HidService
        prefs.getString(KEY_DEVICE_ADDR, null)
            ?.takeIf { it.isNotEmpty() }
            ?.let { bluetoothManager.setTargetDevice(it) }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        // Without a WebChromeClient, JavaScript alert() dialogs are silently ignored.
        webView.webChromeClient = WebChromeClient()
        webView.clearCache(true)
        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // Cloudflare unreachable -> use the copy bundled inside the APK.
                if (request.isForMainFrame && !request.url.toString().startsWith("file:")) {
                    view.loadUrl(FALLBACK_URL)
                }
            }
        }
        webView.loadUrl(PRIMARY_URL)

        // Permissions FIRST, Bluetooth start() only once they are granted.
        ensurePermissionsThenStart()
    }

    override fun onResume() {
        super.onResume()
        if (missingPermissions().isEmpty()) startBluetooth()
    }

    // NOTE: no stop() in onDestroy any more. HidService keeps the connection alive in the
    // background; use the "Stop" button in its notification to shut it down.

    /** Starts the HID profile and the background keep-alive / auto-reconnect service. */
    private fun startBluetooth() {
        bluetoothManager.start()
        ContextCompat.startForegroundService(this, Intent(this, HidService::class.java))
    }

    // ------------------------------------------------------------------ permissions

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyList() // BLUETOOTH / BLUETOOTH_ADMIN are install-time permissions below Android 12
        }

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionsThenStart() {
        val toRequest = missingPermissions().toMutableList()
        if (toRequest.isEmpty()) startBluetooth()
        // Android 13+: lets the "connection ready" notification (with its Stop button) show.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (missingPermissions().isEmpty()) {
            startBluetooth() // <- Bluetooth only starts once permissions exist
            toast("Bluetooth ready. Tap Refresh Devices in Settings.")
        } else {
            toast("Bluetooth permissions are required for the app to work.")
        }
    }

    // ------------------------------------------------------------------ biometric + unlock

    private fun showBiometricPrompt() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    executeUnlockTransmission()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        toast("Biometric error: $errString")
                    }
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Windows Laptop")
            .setSubtitle("Authenticate using fingerprint to send HID sequence")
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }

    private fun executeUnlockTransmission() {
        val password = prefs.getString(KEY_PASSWORD, "") ?: ""
        if (password.isEmpty()) {
            toast("No password saved in Settings!")
            return
        }
        if (unlockInProgress) return
        unlockInProgress = true
        toast("Connecting to laptop...")

        bluetoothManager.sendUnlockSequence(password) { success, message ->
            unlockInProgress = false
            toast(message)
            val js = "window.onUnlockResult && window.onUnlockResult($success, ${JSONObject.quote(message)})"
            webView.evaluateJavascript(js, null)
        }
    }

    // ------------------------------------------------------------------ pairing helpers

    private fun requestDiscoverable() {
        if (missingPermissions().isNotEmpty()) {
            ensurePermissionsThenStart()
            return
        }
        startBluetooth() // HID service record must be registered BEFORE Windows pairs
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(intent)
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ JS bridge
    // Method names / signatures are unchanged so the existing Cloudflare script.js keeps working.

    inner class WebAppInterface {

        @JavascriptInterface fun getAppPin(): String = prefs.getString(KEY_PIN, "") ?: ""

        @JavascriptInterface fun setAppPin(pin: String) {
            prefs.edit().putString(KEY_PIN, pin).apply()
            toast("App PIN updated")
        }

        @JavascriptInterface fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

        @JavascriptInterface fun savePassword(rawPassword: String) {
            prefs.edit().putString(KEY_PASSWORD, rawPassword).apply() // raw: spaces preserved
            toast("Password saved locally")
        }

        @JavascriptInterface fun getSelectedDeviceAddress(): String =
            prefs.getString(KEY_DEVICE_ADDR, "") ?: ""

        @JavascriptInterface fun saveSelectedDeviceAddress(address: String) {
            prefs.edit().putString(KEY_DEVICE_ADDR, address).apply()
        }

        @JavascriptInterface fun setSelectedDevice(address: String) {
            bluetoothManager.setTargetDevice(address)
            toast("Target device updated")
        }

        @JavascriptInterface fun getPairedDevices(): String {
            val array = JSONArray()
            bluetoothManager.getPairedDevices().forEach { (name, address) ->
                array.put(JSONObject().put("name", name).put("address", address))
            }
            return array.toString()
        }

        @JavascriptInterface fun getBluetoothStatus(): String = bluetoothManager.statusText()

        @JavascriptInterface fun getDiagnostics(): String = bluetoothManager.diagnostics()

        @JavascriptInterface fun triggerFingerprintUnlock() {
            mainHandler.post { showBiometricPrompt() }
        }

        @JavascriptInterface fun makeDiscoverable() {
            mainHandler.post { requestDiscoverable() }
        }

        @JavascriptInterface fun openBluetoothSettings() {
            mainHandler.post { this@MainActivity.openBluetoothSettings() }
        }
    }
}
