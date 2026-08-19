package com.falveyo.service

import android.bluetooth.BluetoothDevice
import android.graphics.PointF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EdgeScrollDirection(val title: String) {
    NONE("Boşta"),
    BOTTOM("Aşağı Kaydırma"),
    TOP("Yukarı Kaydırma"),
    LEFT("Sola Kaydırma"),
    RIGHT("Sağa Kaydırma")
}

enum class ButtonActionType(val label: String) {
    NONE("Boşta"),
    SHORT_CLICK("Tek Tık"),
    LONG_PRESS("Uzun Basış"),
    BACK("Geri"),
    HOME("Ana Ekran")
}

data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice,
    val isFalveyoOrEsp: Boolean = false
)

object GlobalCursorState {

    private val _lastButtonEvent = MutableStateFlow(ButtonActionType.NONE)
    val lastButtonEvent: StateFlow<ButtonActionType> = _lastButtonEvent.asStateFlow()

    private val _buttonEventTimestamp = MutableStateFlow(0L)
    val buttonEventTimestamp: StateFlow<Long> = _buttonEventTimestamp.asStateFlow()

    fun recordButtonEvent(action: ButtonActionType) {
        _lastButtonEvent.value = action
        _buttonEventTimestamp.value = System.currentTimeMillis()
    }

    private val _position = MutableStateFlow(PointF(540f, 960f))
    val position: StateFlow<PointF> = _position.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _touching = MutableStateFlow(false)
    val touching: StateFlow<Boolean> = _touching.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedBleDevice>> = _scannedDevices.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _accessibilityActive = MutableStateFlow(false)
    val accessibilityActive: StateFlow<Boolean> = _accessibilityActive.asStateFlow()

    private val _lastCommand = MutableStateFlow<String?>(null)
    val lastCommand: StateFlow<String?> = _lastCommand.asStateFlow()

    private val _statusLog = MutableStateFlow("Hazır. Cihaz taraması başlatabilirsiniz.")
    val statusLog: StateFlow<String> = _statusLog.asStateFlow()

    private val _edgeScrollingDirection = MutableStateFlow(EdgeScrollDirection.NONE)
    val edgeScrollingDirection: StateFlow<EdgeScrollDirection> = _edgeScrollingDirection.asStateFlow()

    private val _joystickVector = MutableStateFlow(PointF(0f, 0f))
    val joystickVector: StateFlow<PointF> = _joystickVector.asStateFlow()

    private val _isSelecting = MutableStateFlow(false)
    val isSelecting: StateFlow<Boolean> = _isSelecting.asStateFlow()

    private val _selectionStart = MutableStateFlow<PointF?>(null)
    val selectionStart: StateFlow<PointF?> = _selectionStart.asStateFlow()

    fun updatePosition(x: Float, y: Float) {
        val cur = _position.value
        if (cur.x == x && cur.y == y) return
        _position.value = PointF(x, y)
    }

    fun setSelecting(selecting: Boolean, startX: Float = 0f, startY: Float = 0f) {
        _isSelecting.value = selecting
        if (selecting) {
            _selectionStart.value = PointF(startX, startY)
        } else {
            _selectionStart.value = null
        }
    }

    fun updateJoystickVector(vx: Float, vy: Float) {
        val cur = _joystickVector.value
        if (cur.x == vx && cur.y == vy) return
        _joystickVector.value = PointF(vx, vy)
    }

    fun setEdgeScrollingDirection(dir: EdgeScrollDirection) {
        if (_edgeScrollingDirection.value != dir) {
            _edgeScrollingDirection.value = dir
        }
    }

    fun setConnected(value: Boolean, deviceName: String? = null, deviceAddress: String? = null) {
        _connected.value = value
        if (value) {
            _connectedDeviceName.value = deviceName ?: "ESP32 Cihazı"
            _connectedDeviceAddress.value = deviceAddress
            _statusLog.value = "Bağlandı: ${deviceName ?: deviceAddress ?: "ESP32"}"
        } else {
            _connectedDeviceName.value = null
            _connectedDeviceAddress.value = null
            _statusLog.value = "Bağlantı kesildi / Bağlantı bekleniyor"
        }
    }

    fun setTouching(value: Boolean) {
        if (_touching.value != value) {
            _touching.value = value
        }
    }

    fun setIsScanning(value: Boolean) {
        _isScanning.value = value
        if (value) {
            _statusLog.value = "BLE cihazları taranıyor..."
        } else {
            _statusLog.value = "Tarama tamamlandı."
        }
    }

    fun clearScannedDevices() {
        _scannedDevices.value = emptyList()
    }

    fun addOrUpdateDevice(device: BluetoothDevice, rssi: Int, scanRecordName: String? = null) {
        val currentList = _scannedDevices.value
        val name = scanRecordName?.takeIf { it.isNotBlank() }
            ?: (try { device.name } catch (_: SecurityException) { null })?.takeIf { it.isNotBlank() }
            ?: "Bilinmeyen Cihaz (${device.address.takeLast(5)})"

        val existing = currentList.find { it.address == device.address }
        // Cihaz zaten listede varsa ve RSSI değişimi 4 dBm'den azsa UI recomposition tetikleme
        if (existing != null && existing.name == name && kotlin.math.abs(existing.rssi - rssi) < 4) {
            return
        }

        val isFalveyoOrEsp = name.contains("falveyo", ignoreCase = true) ||
                name.contains("vocativa", ignoreCase = true) ||
                name.contains("esp32", ignoreCase = true) ||
                name.contains("esp", ignoreCase = true) ||
                name.contains("cursor", ignoreCase = true) ||
                name.contains("joystick", ignoreCase = true)

        val item = ScannedBleDevice(
            name = name,
            address = device.address,
            rssi = rssi,
            device = device,
            isFalveyoOrEsp = isFalveyoOrEsp
        )

        val newList = currentList.toMutableList()
        val existingIndex = newList.indexOfFirst { it.address == device.address }
        if (existingIndex >= 0) {
            newList[existingIndex] = item
        } else {
            newList.add(item)
        }

        // Sort Falveyo/ESP32 devices on top, then by strongest RSSI
        newList.sortWith(
            compareByDescending<ScannedBleDevice> { it.isFalveyoOrEsp }
                .thenByDescending { it.rssi }
        )

        _scannedDevices.value = newList
    }

    fun setServiceRunning(running: Boolean) {
        if (_serviceRunning.value != running) {
            _serviceRunning.value = running
        }
    }

    fun setAccessibilityActive(active: Boolean) {
        if (_accessibilityActive.value != active) {
            _accessibilityActive.value = active
        }
    }

    fun setLastCommand(command: String) {
        // Joystick hareket logları UI'ı sürekli yeniden çizdirip kasılmaya yol açtığı için filtrelenir
        val isJoystickMove = command.startsWith("JOYSTICK:", ignoreCase = true) ||
                command.startsWith("JOY:", ignoreCase = true) ||
                command.startsWith("J:", ignoreCase = true) ||
                command == "TOUCH_MOVE" || command == "JOY_MOVE"

        if (isJoystickMove) return

        if (_lastCommand.value != command) {
            _lastCommand.value = command
        }
    }

    fun setStatusLog(status: String) {
        _statusLog.value = status
    }
}

