package com.example.hotspotunlock

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bluetoothManager: BluetoothHidManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val PREFS_NAME = "HotspotUnlockPrefs"
        private const val KEY_PASSWORD = "windows_password"
        private const val KEY_PIN = "app_pin"
        private const val KEY_DEVICE_ADDR = "target_device_addr"
        private const val PRIMARY_URL = "https://hotspot-unlock.pages.dev"
        private const val FALLBACK_URL = "file:///android_asset/index.html"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
setContentView(webView)

prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
bluetoothManager = BluetoothHidManager(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.clearCache(true)
        webView.addJavascriptInterface(WebAppInterface(), "AndroidInterface")

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                // Fallback to local assets if Cloudflare network fails
                if (failingUrl == PRIMARY_URL) {
                    webView.loadUrl(FALLBACK_URL)
                }
            }
        }

        webView.loadUrl(PRIMARY_URL)

        // Restore saved target device
        val savedAddr = prefs.getString(KEY_DEVICE_ADDR, null)
        if (!savedAddr.isNullOrEmpty()) {
            bluetoothManager.setTargetDevice(savedAddr)
        }
    }

    inner class WebAppInterface {

        @JavascriptInterface
        fun savePassword(rawPassword: String) {
            mainHandler.post {
                // Preserve exact raw input (do not call .trim())
                prefs.edit().putString(KEY_PASSWORD, rawPassword).apply()
                Toast.makeText(this@MainActivity, "Password saved locally", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getPassword(): String {
            return prefs.getString(KEY_PASSWORD, "") ?: ""
        }

        @JavascriptInterface
        fun setAppPin(pin: String) {
            mainHandler.post {
                prefs.edit().putString(KEY_PIN, pin).apply()
                Toast.makeText(this@MainActivity, "App PIN updated", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getAppPin(): String {
            return prefs.getString(KEY_PIN, "") ?: ""
        }

        @JavascriptInterface
        fun getPairedDevices(): String {
            val devices = bluetoothManager.getPairedDevices()
            val jsonArray = JSONArray()
            for (dev in devices) {
                @SuppressLint("MissingPermission")
                val name = dev.name ?: "Unknown Device"
                val obj = JSONObject()
                obj.put("name", name)
                obj.put("address", dev.address)
                jsonArray.put(obj)
            }
            return jsonArray.toString()
        }

        @JavascriptInterface
        fun setSelectedDevice(address: String) {
            mainHandler.post {
                if (bluetoothManager.setTargetDevice(address)) {
                    prefs.edit().putString(KEY_DEVICE_ADDR, address).apply()
                    Toast.makeText(this@MainActivity, "Target device updated", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun getBluetoothStatus(): String {
            return if (bluetoothManager.isConnected()) {
                "Connected to " + bluetoothManager.getTargetDeviceName()
            } else {
                "Disconnected"
            }
        }

        @JavascriptInterface
        fun triggerFingerprintUnlock() {
            mainHandler.post {
                showBiometricPrompt()
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    executeUnlockTransmission()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(applicationContext, "Biometric error: $errString", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Windows Laptop")
            .setSubtitle("Authenticate using fingerprint to send HID sequence")
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun executeUnlockTransmission() {
        val password = prefs.getString(KEY_PASSWORD, "") ?: ""
        if (password.isEmpty()) {
            Toast.makeText(this, "No password saved in Settings!", Toast.LENGTH_LONG).show()
            return
        }

        bluetoothManager.sendUnlockSequence(password) { success ->
            if (success) {
                Toast.makeText(this, "Unlock signal sent!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to send keystrokes. Check Bluetooth connection.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
