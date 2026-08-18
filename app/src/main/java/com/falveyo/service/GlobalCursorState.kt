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

data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice,
    val isFalveyoOrEsp: Boolean = false
)

object GlobalCursorState {

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
        _joystickVector.value = PointF(vx, vy)
    }

    fun setEdgeScrollingDirection(dir: EdgeScrollDirection) {
        _edgeScrollingDirection.value = dir
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
        _touching.value = value
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
        val currentList = _scannedDevices.value.toMutableList()
        val name = scanRecordName?.takeIf { it.isNotBlank() }
            ?: (try { device.name } catch (_: SecurityException) { null })?.takeIf { it.isNotBlank() }
            ?: "Bilinmeyen Cihaz (${device.address.takeLast(5)})"

        val isFalveyoOrEsp = name.contains("falveyo", ignoreCase = true) ||
                name.contains("vocativa", ignoreCase = true) ||
                name.contains("esp32", ignoreCase = true) ||
                name.contains("esp", ignoreCase = true) ||
                name.contains("cursor", ignoreCase = true) ||
                name.contains("joystick", ignoreCase = true)

        val existingIndex = currentList.indexOfFirst { it.address == device.address }
        val item = ScannedBleDevice(
            name = name,
            address = device.address,
            rssi = rssi,
            device = device,
            isFalveyoOrEsp = isFalveyoOrEsp
        )

        if (existingIndex >= 0) {
            currentList[existingIndex] = item
        } else {
            currentList.add(item)
        }

        // Sort Falveyo/ESP32 devices on top, then by strongest RSSI
        currentList.sortWith(
            compareByDescending<ScannedBleDevice> { it.isFalveyoOrEsp }
                .thenByDescending { it.rssi }
        )

        _scannedDevices.value = currentList
    }

    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun setLastCommand(command: String) {
        _lastCommand.value = command
    }

    fun setStatusLog(status: String) {
        _statusLog.value = status
    }
}

