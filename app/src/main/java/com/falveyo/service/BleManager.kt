package com.falveyo.service

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

private const val TAG = "FalveyoBLE"

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
                val isRecent = (now - lastJoystickPacketTime) < 100L

                val speedSetting = AppSettings.cursorSpeed.value
                val smoothingEnabled = AppSettings.smoothingEnabled.value
                val smoothingFactor = AppSettings.smoothingFactor.value.coerceIn(0.1f, 0.95f)

                if (!isRecent) {
                    targetVx = 0f
                    targetVy = 0f
                    currentVx = 0f
                    currentVy = 0f
                    GlobalCursorState.updateJoystickVector(0f, 0f)
                } else if (targetVx == 0f && targetVy == 0f) {
                    currentVx = 0f
                    currentVy = 0f
                    GlobalCursorState.updateJoystickVector(0f, 0f)
                } else if (smoothingEnabled) {
                    // Düşük geçiren Exponential Moving Average filtresi
                    currentVx += (targetVx - currentVx) * smoothingFactor
                    currentVy += (targetVy - currentVy) * smoothingFactor
                } else {
                    currentVx = targetVx
                    currentVy = targetVy
                }

                // Çok küçük değerlerde kes (floating point drift önleme)
                if (abs(currentVx) < 0.05f) currentVx = 0f
                if (abs(currentVy) < 0.05f) currentVy = 0f

                GlobalCursorState.updateJoystickVector(currentVx, currentVy)

                if (currentVx != 0f || currentVy != 0f) {
                    val newX = (cursorX + currentVx).coerceIn(0f, screenWidth.toFloat())
                    val newY = (cursorY + currentVy).coerceIn(0f, screenHeight.toFloat())

                    cursorX = newX
                    cursorY = newY

                    GlobalCursorState.updatePosition(cursorX, cursorY)

                    if (GlobalCursorState.touching.value) {
                        InputExecutor.touchMove(cursorX, cursorY)
                    }
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
    // BAĞLANTI & YENİDEN DENEME YÖNETİMİ
    // ----------------------------------------------------------------

    private var targetDevice: BluetoothDevice? = null
    private var retryCount = 0
    private val maxRetries = 2
    private var isConnecting = false

    fun connect(device: BluetoothDevice) {
        targetDevice = device
        retryCount = 0
        isConnecting = true
        stopScan()

        val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        GlobalCursorState.setStatusLog("Bağlantı kuruluyor: $deviceName...")

        // Android BLE radyo sürücüsünün taramayı temizlemesi için 200ms bekle ve temiz bağlantı kur
        mainHandler.postDelayed({
            executeConnect(device)
        }, 200L)
    }

    private fun executeConnect(device: BluetoothDevice) {
        try {
            cleanupGatt()
            val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            Log.d(TAG, "connectGatt başlatılıyor (Deneme ${retryCount + 1}): $deviceName (${device.address})")
            
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectGatt hatası: ${e.message}")
            GlobalCursorState.setStatusLog("Bağlantı hatası: ${e.message}")
            isConnecting = false
        }
    }

    fun connect(macAddress: String) {
        val adapter = bleAdapter ?: return
        try {
            val device = adapter.getRemoteDevice(macAddress)
            connect(device)
        } catch (e: Exception) {
            Log.e(TAG, "MAC adresi ile bağlanamadı ($macAddress): ${e.message}")
            GlobalCursorState.setStatusLog("Geçersiz MAC adresi: $macAddress")
        }
    }

    private fun cleanupGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        rxCharacteristic = null
        txCharacteristic = null
    }

    fun disconnect() {
        isConnecting = false
        targetDevice = null
        retryCount = 0
        cleanupGatt()
        targetVx = 0f
        targetVy = 0f
        currentVx = 0f
        currentVy = 0f
        GlobalCursorState.setConnected(false)
        GlobalCursorState.setStatusLog("Bağlantı kesildi")
    }

    // ----------------------------------------------------------------
    // GATT CALLBACK
    // ----------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val devName = try { gatt.device.name ?: gatt.device.address } catch (_: SecurityException) { gatt.device.address }
            Log.d(TAG, "onConnectionStateChange: dev=$devName, status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT bağlantı uyarısı (Status: $status, State: $newState)")
                
                // Android GATT 133 / Zaman aşımı durumunda otomatik temiz yeniden deneme
                if (isConnecting && retryCount < maxRetries && targetDevice != null) {
                    retryCount++
                    GlobalCursorState.setStatusLog("Bağlantı yenileniyor ($retryCount/$maxRetries)...")
                    try { gatt.close() } catch (_: Exception) {}
                    mainHandler.postDelayed({
                        targetDevice?.let { executeConnect(it) }
                    }, 400L)
                    return
                }
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnecting = false
                    retryCount = 0
                    Log.d(TAG, "GATT Bağlandı: $devName")
                    GlobalCursorState.setStatusLog("Bağlandı, servisler hazırlanıyor...")

                    // Bağlantı önceliğini yükselt ve MTU paketini genişlet
                    mainHandler.postDelayed({
                        try {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                gatt.requestMtu(512)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Öncelik/MTU isteği hatası: ${e.message}")
                        }
                    }, 100L)

                    // Servis keşfi için 350ms bekle (ESP32 BLE kararlılığı için kritik)
                    mainHandler.postDelayed({
                        try {
                            val success = gatt.discoverServices()
                            if (!success) {
                                Log.w(TAG, "discoverServices false döndü, tekrar deneniyor...")
                                gatt.discoverServices()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "discoverServices hatası: ${e.message}")
                        }
                    }, 350L)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT Bağlantı kesildi: $devName (Status: $status)")
                    GlobalCursorState.setConnected(false)
                    try { gatt.close() } catch (_: Exception) {}
                    if (bluetoothGatt == gatt) {
                        bluetoothGatt = null
                        rxCharacteristic = null
                        txCharacteristic = null
                    }

                    if (!isConnecting) {
                        GlobalCursorState.setStatusLog("Bağlantı kesildi ($devName)")
                    } else if (retryCount >= maxRetries) {
                        isConnecting = false
                        GlobalCursorState.setStatusLog("Bağlantı kurulamadı (Hata: $status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered başarısız: $status")
                GlobalCursorState.setStatusLog("Servis keşfi başarısız ($status)")
                return
            }

            Log.d(TAG, "Servisler keşfedildi. Toplam servis sayısı: ${gatt.services.size}")

            var foundAnyNotify = false

            // Tüm servis ve karakteristikleri tara (ESP32, Nordic UART, HM-10 veya Özel UUID'ler)
            for (svc in gatt.services) {
                for (charac in svc.characteristics) {
                    val props = charac.properties

                    val isNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val isIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    val isWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                  (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                    if (isWrite && rxCharacteristic == null) {
                        rxCharacteristic = charac
                        Log.d(TAG, "RX (Yazma) Karakteristiği bulundu: ${charac.uuid}")
                    }

                    if (isNotify || isIndicate) {
                        txCharacteristic = charac
                        foundAnyNotify = true
                        Log.d(TAG, "TX (Bildirim) Karakteristiği bulundu: ${charac.uuid}")

                        // Bildirimi aktif et
                        gatt.setCharacteristicNotification(charac, true)
                        val descriptor = charac.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
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
                            Log.d(TAG, "CCCD tanımlayıcısı yazıldı: ${charac.uuid}")
                        }
                    }
                }
            }

            val devName = try { gatt.device.name ?: gatt.device.address } catch (_: SecurityException) { gatt.device.address }
            GlobalCursorState.setConnected(true, devName, gatt.device.address)
            GlobalCursorState.setStatusLog("Bağlandı ve hazır: $devName")

            // Cursor'u ortaya sıfırla
            cursorX = screenWidth / 2f
            cursorY = screenHeight / 2f
            targetVx = 0f
            targetVy = 0f
            currentVx = 0f
            currentVy = 0f
            GlobalCursorState.updatePosition(cursorX, cursorY)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: ${descriptor.uuid}, status=$status")
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

    private fun handleMessage(raw: String) {
        if (raw.isEmpty()) return

        // Çoklu komut / hızlı basış ayrıştırma (\n, \r, ;)
        val commands = raw.split("\n", "\r", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (msg in commands) {
            processSingleCommand(msg)
        }
    }

    private var lastSwState = 0
    private var lastBtn1State = 0
    private var lastBtn2State = 0
    private var lastBtn3State = 0

    private fun processSingleCommand(msg: String) {
        GlobalCursorState.setLastCommand(msg)

        val isJoy = msg.startsWith("JOYSTICK:", ignoreCase = true) ||
                msg.startsWith("JOY:", ignoreCase = true) ||
                msg.startsWith("J:", ignoreCase = true)

        if (isJoy) {
            val payload = when {
                msg.startsWith("JOYSTICK:", ignoreCase = true) -> msg.substring(9)
                msg.startsWith("JOY:", ignoreCase = true) -> msg.substring(4)
                else -> msg.substring(2)
            }
            val parts = payload.split(",")
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
                    currentVx = 0f
                    currentVy = 0f
                    GlobalCursorState.updateJoystickVector(0f, 0f)
                } else {
                    // Ölü bölgeyi çıkarıp doğrusal hassasiyet oranına uyarla
                    val effectiveMag = ((magnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 2f)

                    // Hem mikro hareketleri kaçırmayan hem de yüksek eğimde hızlanan hassas eğri
                    val curvedMag = effectiveMag.pow(sensitivity)
                    val factor = (curvedMag / magnitude) * speed

                    targetVx = nx * factor
                    targetVy = ny * factor
                }

                // Gömülü tuş durumları kontrolü (örn: JOYSTICK:x,y,sw veya JOYSTICK:x,y,sw,up,down,home)
                if (parts.size >= 3) {
                    val sw = parts[2].toIntOrNull() ?: 0
                    if (sw == 1 && lastSwState == 0) {
                        onCommand("TOUCH_DOWN")
                    } else if (sw == 0 && lastSwState == 1) {
                        onCommand("TOUCH_UP")
                    }
                    lastSwState = sw

                    if (parts.size >= 4) {
                        val up = parts[3].toIntOrNull() ?: 0
                        if (up == 1 && lastBtn1State == 0) onCommand("UP")
                        lastBtn1State = up
                    }
                    if (parts.size >= 5) {
                        val down = parts[4].toIntOrNull() ?: 0
                        if (down == 1 && lastBtn2State == 0) onCommand("DOWN")
                        lastBtn2State = down
                    }
                    if (parts.size >= 6) {
                        val home = parts[5].toIntOrNull() ?: 0
                        if (home == 1 && lastBtn3State == 0) onCommand("HOME")
                        lastBtn3State = home
                    }
                }
            }
            return
        }

        onCommand(msg)
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
