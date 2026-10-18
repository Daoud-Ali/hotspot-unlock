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
      windowsPassword.value = savedPwd; // Raw string loaded (no trim)
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
    });
  });

  // SAVE PASSWORD (RAW STRING - PRESERVES ALL SPACES)
  savePasswordBtn.addEventListener("click", () => {
    const rawPwd = windowsPassword.value; // DO NOT call .trim()
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

  // REFRESH BLUETOOTH DEVICES
  refreshDevicesBtn.addEventListener("click", () => {
    refreshDeviceList();
  });

  deviceSelect.addEventListener("change", (e) => {
    const selectedAddress = e.target.value;
    const selectedName = e.target.options[e.target.selectedIndex].text;
    if (selectedAddress) {
      callNative("setSelectedDevice", selectedAddress);
      if (activeDeviceName) {
        activeDeviceName.textContent = selectedName;
      }
    }
  });

  function refreshDeviceList() {
    let devicesJson = getNativeData("getPairedDevices");
    if (devicesJson) {
      try {
        const devices = JSON.parse(devicesJson);
        deviceSelect.innerHTML = '<option value="">Select Paired Laptop...</option>';
        devices.forEach((dev) => {
          const opt = document.createElement("option");
          opt.value = dev.address;
          opt.textContent = dev.name;
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
      if (status.toLowerCase().includes("connect")) {
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
    
    // Always mirror to localStorage using matching keys for web/offline fallback
    if (funcName === "savePassword") localStorage.setItem("windows_password", param);
    if (funcName === "setAppPin") localStorage.setItem("app_pin", param);
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
    return null;
  }
});
