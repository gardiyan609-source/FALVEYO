package com.vocativa.service

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.math.*

private const val TAG = "VocativaBLE"

private val DEFAULT_SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
private val DEFAULT_TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val DEFAULT_RX_CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
private val CCCD_UUID            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val NORDIC_UART_SERVICE  = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val NORDIC_TX_CHAR       = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
private val NORDIC_RX_CHAR       = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private var screenWidth: Int,
    private var screenHeight: Int,
    private val onCommand: (String) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bleAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner? get() = bleAdapter?.bluetoothLeScanner

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var cursorX = screenWidth / 2f
    private var cursorY = screenHeight / 2f

    // 60 FPS Pürüzsüz İnterpolasyon ve Hız Vektörleri
    @Volatile private var targetVx = 0f
    @Volatile private var targetVy = 0f
    private var currentVx = 0f
    private var currentVy = 0f
    private var lastJoystickPacketTime = 0L

    init {
        startPhysicsLoop()
    }

    // ----------------------------------------------------------------
    // 60 FPS PÜRÜZSÜZ İMLEÇ MOTORU (Physics & Interpolation Loop)
    // ----------------------------------------------------------------

    private fun startPhysicsLoop() {
        scope.launch {
            val frameTimeMs = 16L // ~60 FPS
            while (isActive) {
                val now = System.currentTimeMillis()
                val isRecent = (now - lastJoystickPacketTime) < 250L

                val speedSetting = AppSettings.cursorSpeed.value
                val smoothingEnabled = AppSettings.smoothingEnabled.value
                val smoothingFactor = AppSettings.smoothingFactor.value.coerceIn(0.1f, 0.95f)

                if (!isRecent) {
                    targetVx = 0f
                    targetVy = 0f
                }

                if (smoothingEnabled) {
                    // Düşük geçiren Exponential Moving Average filtresi
                    currentVx += (targetVx - currentVx) * smoothingFactor
                    currentVy += (targetVy - currentVy) * smoothingFactor
                } else {
                    currentVx = targetVx
                    currentVy = targetVy
                }

                // Çok küçük değerlerde kes (floating point drift önleme)
                if (abs(currentVx) < 0.01f) currentVx = 0f
                if (abs(currentVy) < 0.01f) currentVy = 0f

                if (currentVx != 0f || currentVy != 0f) {
                    val newX = (cursorX + currentVx).coerceIn(0f, screenWidth.toFloat())
                    val newY = (cursorY + currentVy).coerceIn(0f, screenHeight.toFloat())

                    cursorX = newX
                    cursorY = newY

                    GlobalCursorState.updatePosition(cursorX, cursorY)
                    onCommand("TOUCH_MOVE")
                }

                delay(frameTimeMs)
            }
        }
    }

    // ----------------------------------------------------------------
    // EKRAN BOYUTU GÜNCELLE
    // ----------------------------------------------------------------

    fun updateScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        cursorX = cursorX.coerceIn(0f, screenWidth.toFloat())
        cursorY = cursorY.coerceIn(0f, screenHeight.toFloat())
        Log.d(TAG, "Ekran boyutu güncellendi: ${width}x${height}")
    }

    // ----------------------------------------------------------------
    // TARAMA
    // ----------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val deviceName = result.scanRecord?.deviceName ?: try { device.name } catch (_: SecurityException) { null }
            GlobalCursorState.addOrUpdateDevice(device, result.rssi, deviceName)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                val device = result.device ?: return@forEach
                val deviceName = result.scanRecord?.deviceName ?: try { device.name } catch (_: SecurityException) { null }
                GlobalCursorState.addOrUpdateDevice(device, result.rssi, deviceName)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Tarama hatası: $errorCode")
            GlobalCursorState.setIsScanning(false)
            GlobalCursorState.setStatusLog("Tarama başarısız (Hata: $errorCode)")
        }
    }

    fun startScan(timeoutMillis: Long = 15000L) {
        val adapter = bleAdapter
        if (adapter == null || !adapter.isEnabled) {
            GlobalCursorState.setStatusLog("Bluetooth kapalı!")
            return
        }

        stopScan()
        GlobalCursorState.clearScannedDevices()
        GlobalCursorState.setIsScanning(true)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner?.startScan(null, settings, scanCallback)
            scanTimeoutRunnable = Runnable { stopScan() }
            scanTimeoutRunnable?.let { mainHandler.postDelayed(it, timeoutMillis) }
        } catch (e: Exception) {
            Log.e(TAG, "startScan hatası: ${e.message}")
            GlobalCursorState.setIsScanning(false)
        }
    }

    fun stopScan() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        GlobalCursorState.setIsScanning(false)
    }

    // ----------------------------------------------------------------
    // BAĞLANTI
    // ----------------------------------------------------------------

    fun connect(device: BluetoothDevice) {
        stopScan()
        disconnect()
        val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        GlobalCursorState.setStatusLog("Bağlantı kuruluyor: $deviceName...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "GATT kapatma hatası: ${e.message}")
        } finally {
            bluetoothGatt = null
            rxCharacteristic = null
            txCharacteristic = null
            targetVx = 0f
            targetVy = 0f
            currentVx = 0f
            currentVy = 0f
            GlobalCursorState.setConnected(false)
        }
    }

    // ----------------------------------------------------------------
    // GATT CALLBACK
    // ----------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val devName = try { gatt.device.name ?: gatt.device.address } catch (_: SecurityException) { gatt.device.address }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Bağlandı: $devName")
                    mainHandler.postDelayed({ try { gatt.discoverServices() } catch (_: Exception) {} }, 250)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Bağlantı kesildi: $devName")
                    GlobalCursorState.setConnected(false)
                    try { gatt.close() } catch (_: Exception) {}
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                        rxCharacteristic = null
                        txCharacteristic = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            var foundTx: BluetoothGattCharacteristic? = null
            var foundRx: BluetoothGattCharacteristic? = null

            val service = gatt.getService(DEFAULT_SERVICE_UUID) ?: gatt.getService(NORDIC_UART_SERVICE)

            if (service != null) {
                foundTx = service.getCharacteristic(DEFAULT_TX_CHAR_UUID) ?: service.getCharacteristic(NORDIC_TX_CHAR)
                foundRx = service.getCharacteristic(DEFAULT_RX_CHAR_UUID) ?: service.getCharacteristic(NORDIC_RX_CHAR)
            }

            if (foundTx == null || foundRx == null) {
                for (svc in gatt.services) {
                    for (charac in svc.characteristics) {
                        val props = charac.properties
                        if (foundTx == null && ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)) foundTx = charac
                        if (foundRx == null && ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)) foundRx = charac
                    }
                }
            }

            txCharacteristic = foundTx
            rxCharacteristic = foundRx

            if (foundTx != null) {
                gatt.setCharacteristicNotification(foundTx, true)
                val descriptor = foundTx.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    val isIndicate = (foundTx.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    val payload = if (isIndicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, payload)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = payload
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }

            val devName = try { gatt.device.name ?: gatt.device.address } catch (_: SecurityException) { gatt.device.address }
            GlobalCursorState.setConnected(true, devName, gatt.device.address)

            // Cursor'u ortaya sıfırla
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            targetVx = 0f
            targetVy = 0f
            currentVx = 0f
            currentVy = 0f
            GlobalCursorState.updatePosition(cursorX, cursorY)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleMessage(String(value).trim())
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleMessage(String(characteristic.value ?: return).trim())
            }
        }
    }

    // ----------------------------------------------------------------
    // GELEN VERİ VE HASSASİYET HESAPLAMA
    // ----------------------------------------------------------------

    private fun handleMessage(msg: String) {
        if (msg.isEmpty()) return
        GlobalCursorState.setLastCommand(msg)

        if (msg.startsWith("JOYSTICK:")) {
            val parts = msg.removePrefix("JOYSTICK:").split(",")
            if (parts.size >= 2) {
                val jx = parts[0].toFloatOrNull() ?: 0f
                val jy = parts[1].toFloatOrNull() ?: 0f

                lastJoystickPacketTime = System.currentTimeMillis()

                // Normalizasyon [-1.0, 1.0]
                val nx = (jx / 1000f).coerceIn(-1.5f, 1.5f)
                val ny = (jy / 1000f).coerceIn(-1.5f, 1.5f)

                val magnitude = hypot(nx, ny)
                val deadzone = AppSettings.deadzone.value
                val speed = AppSettings.cursorSpeed.value
                val sensitivity = AppSettings.fineSensitivity.value

                if (magnitude <= deadzone) {
                    targetVx = 0f
                    targetVy = 0f
                } else {
                    // Ölü bölgeyi çıkarıp doğrusal hassasiyet oranına uyarla
                    val effectiveMag = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 2f)

                    // Hem mikro hareketleri kaçırmayan hem de yüksek eğimde hızlanan hassas eğri
                    val curvedMag = effectiveMag.pow(sensitivity)
                    val factor = (curvedMag / magnitude) * speed

                    targetVx = nx * factor
                    targetVy = ny * factor
                }
            }
            return
        }

        when (msg) {
            "UP", "DOWN", "SELECT", "BACK", "HOME",
            "TOUCH_DOWN", "TOUCH_UP" -> onCommand(msg)
            else -> onCommand(msg)
        }
    }

    // ----------------------------------------------------------------
    // ANDROID → ESP32
    // ----------------------------------------------------------------

    fun sendCommand(cmd: String) {
        val char = rxCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        val data = cmd.toByteArray()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char, data, writeType)
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
        Log.d(TAG, "Android→ESP32: $cmd")
    }

    fun destroy() {
        scope.cancel()
        disconnect()
    }
}
