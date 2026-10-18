document.addEventListener("DOMContentLoaded", () => {
  // Elements
  const pinOverlay = document.getElementById("pin-overlay");
  const pinInput = document.getElementById("pin-input");
  const pinBtn = document.getElementById("pin-btn");
  const pinError = document.getElementById("pin-error");
  const pinInstruction = document.getElementById("pin-instruction");
  const mainApp = document.getElementById("main-app");

  const tabBtns = document.querySelectorAll(".tab-btn");
  const tabContents = document.querySelectorAll(".tab-content");

  const unlockBtn = document.getElementById("unlock-btn");
  const windowsPassword = document.getElementById("windows-password");
  const savePasswordBtn = document.getElementById("save-password-btn");
  const deviceSelect = document.getElementById("device-select");
  const refreshDevicesBtn = document.getElementById("refresh-devices-btn");
  const newPinInput = document.getElementById("new-pin-input");
  const savePinBtn = document.getElementById("save-pin-btn");
  const activeDeviceName = document.getElementById("active-device-name");
  const btStatus = document.getElementById("bt-status");

  // Load Saved State via Native Android Bridge or Local Storage
  initApp();

  function initApp() {
    let savedPin = getNativeData("getAppPin");
    if (!savedPin) {
      pinInstruction.textContent = "Create a 4-6 digit App PIN";
      pinBtn.textContent = "Set PIN & Unlock";
    }

    let savedPwd = getNativeData("getPassword");
    if (savedPwd) {
      windowsPassword.value = savedPwd; // Raw string loaded
    }

    refreshDeviceList();
    checkBluetoothStatus();

    // Periodically update Bluetooth status every 3 seconds
    setInterval(checkBluetoothStatus, 3000);
  }

  // APP PIN LOCK LOGIC
  pinBtn.addEventListener("click", () => {
    const inputPin = pinInput.value;
    let savedPin = getNativeData("getAppPin");

    if (!savedPin) {
      if (inputPin.length >= 4) {
        callNative("setAppPin", inputPin);
        unlockApp();
      } else {
        pinError.textContent = "PIN must be 4 to 6 digits";
      }
    } else {
      if (inputPin === savedPin) {
        unlockApp();
      } else {
        pinError.textContent = "Incorrect PIN";
      }
    }
  });

  function unlockApp() {
    pinOverlay.classList.add("hidden");
    mainApp.classList.remove("hidden");
  }

  // TAB SWITCHING
  tabBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      tabBtns.forEach((b) => b.classList.remove("active"));
      tabContents.forEach((c) => c.classList.add("hidden"));

      btn.classList.add("active");
      document.getElementById(btn.dataset.tab).classList.remove("hidden");
      if (btn.dataset.tab === "settings-tab") refreshDeviceList();
    });
  });

  // SAVE PASSWORD (RAW STRING - PRESERVES ALL SPACES)
  savePasswordBtn.addEventListener("click", () => {
    const rawPwd = windowsPassword.value;
    callNative("savePassword", rawPwd);
    alert("Password saved successfully!");
  });

  // SAVE NEW PIN
  savePinBtn.addEventListener("click", () => {
    const newPin = newPinInput.value;
    if (newPin.length >= 4) {
      callNative("setAppPin", newPin);
      newPinInput.value = "";
      alert("App PIN updated!");
    } else {
      alert("PIN must be at least 4 digits.");
    }
  });

  // TRIGGER BIOMETRIC FINGERPRINT UNLOCK
  unlockBtn.addEventListener("click", () => {
    callNative("triggerFingerprintUnlock");
  });

  // PAIRING HELPERS (need the updated APK; older APKs lack these bridge methods)
  const discoverableBtn = document.getElementById("discoverable-btn");
  const btSettingsBtn = document.getElementById("bt-settings-btn");
  const diagnosticsBtn = document.getElementById("diagnostics-btn");
  const unlockResult = document.getElementById("unlock-result");

  function nativeOrWarn(name) {
    if (window.AndroidInterface && window.AndroidInterface[name]) {
      window.AndroidInterface[name]();
    } else {
      alert("Please install the latest app version to use this feature.");
    }
  }
  if (discoverableBtn) discoverableBtn.addEventListener("click", () => nativeOrWarn("makeDiscoverable"));
  if (btSettingsBtn) btSettingsBtn.addEventListener("click", () => nativeOrWarn("openBluetoothSettings"));
  if (diagnosticsBtn) diagnosticsBtn.addEventListener("click", () => {
    const d = getNativeData("getDiagnostics") || "Diagnostics need the latest app version.";
    const out = document.getElementById("diagnostics-output");
    if (out) {            // shown on the page itself (works even if alert() is blocked)
      out.textContent = d;
      out.classList.remove("hidden");
    } else {
      alert(d);
    }
  });

  // Called by the app after every unlock attempt with the REAL result
  window.onUnlockResult = (ok, message) => {
    if (!unlockResult) return;
    unlockResult.textContent = message;
    unlockResult.classList.toggle("ok", !!ok);
    unlockResult.classList.toggle("fail", !ok);
  };

  // REFRESH BLUETOOTH DEVICES
  refreshDevicesBtn.addEventListener("click", () => {
    refreshDeviceList();
  });

  deviceSelect.addEventListener("change", (e) => {
    const selectedAddress = e.target.value;
    const selectedName = e.target.options[e.target.selectedIndex].text;
    if (selectedAddress) {
      callNative("setSelectedDevice", selectedAddress);
      callNative("saveSelectedDeviceAddress", selectedAddress); // Persist MAC selection
      if (activeDeviceName) {
        activeDeviceName.textContent = selectedName;
      }
    }
  });

  function refreshDeviceList() {
    let devicesJson = getNativeData("getPairedDevices");
    let savedMac = getNativeData("getSelectedDeviceAddress");

    if (devicesJson) {
      try {
        const devices = JSON.parse(devicesJson);
        deviceSelect.innerHTML = '<option value="">Select Paired Laptop...</option>';
        
        devices.forEach((dev) => {
          const opt = document.createElement("option");
          opt.value = dev.address;
          opt.textContent = dev.name;
          
          // Pre-select previously saved device
          if (savedMac && dev.address === savedMac) {
            opt.selected = true;
            callNative("setSelectedDevice", dev.address);
            if (activeDeviceName) {
              activeDeviceName.textContent = dev.name;
            }
          }
          
          deviceSelect.appendChild(opt);
        });
      } catch (e) {
        console.error("Failed to parse device list", e);
      }
    }
  }

  function checkBluetoothStatus() {
    let status = getNativeData("getBluetoothStatus") || "Disconnected";
    if (btStatus) {
      btStatus.textContent = "Status: " + status;
      if (status.toLowerCase().startsWith("connected")) {
        btStatus.classList.add("connected");
      } else {
        btStatus.classList.remove("connected");
      }
    }
  }

  // BRIDGE HELPERS WITH SYNCHRONIZED STORAGE KEYS
  function callNative(funcName, param) {
    if (window.AndroidInterface && window.AndroidInterface[funcName]) {
      if (param !== undefined) {
        window.AndroidInterface[funcName](param);
      } else {
        window.AndroidInterface[funcName]();
      }
    }
    
    // Always mirror to localStorage using matching keys
    if (funcName === "savePassword") localStorage.setItem("windows_password", param);
    if (funcName === "setAppPin") localStorage.setItem("app_pin", param);
    if (funcName === "saveSelectedDeviceAddress") localStorage.setItem("selected_device_mac", param);
  }

  function getNativeData(funcName) {
    let nativeValue = null;
    if (window.AndroidInterface && window.AndroidInterface[funcName]) {
      nativeValue = window.AndroidInterface[funcName]();
    }

    if (nativeValue !== null && nativeValue !== undefined && nativeValue !== "") {
      return nativeValue;
    }

    // Fallback to matching localStorage keys
    if (funcName === "getPassword") return localStorage.getItem("windows_password") || "";
    if (funcName === "getAppPin") return localStorage.getItem("app_pin") || "";
    if (funcName === "getSelectedDeviceAddress") return localStorage.getItem("selected_device_mac") || "";
    return null;
  }
});
