package com.falveyo.service

import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "FalveyoInput"

object InputExecutor {

    // Kalıcı su shell
    private var suProcess: java.lang.Process? = null
    private var suWriter: OutputStreamWriter? = null
    private val suLock = Any()

    @Volatile
    private var detectedTouchDevice: String = "/dev/input/event1"
    @Volatile
    private var detectedKeyDevice: String? = null
    @Volatile
    private var hasDetectedTouchDevice = false

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trackingIdCounter = AtomicInteger(1)

    // Doğrudan donanım akışı (0ms gecikme ve 120 FPS akıcı yazım)
    @Volatile
    private var directTouchStream: FileOutputStream? = null
    private var rootBinaryProcess: java.lang.Process? = null
    @Volatile
    private var rootBinaryStream: java.io.OutputStream? = null
    private val is64BitSystem = (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())

    // Conflated (kuyruk birikmesini ve 2-3 saniye gecikmeyi önleyen) imleç sürükleme kanalı
    private val pendingTouchMove = AtomicReference<Pair<Int, Int>?>(null)
    private val isMovingWorkerRunning = AtomicBoolean(false)

    // Ekran rotasyon bilgisi (0 = Dikey, 1 = 90 derece Yatay, 2 = 180 Ters, 3 = 270 Ters Yatay)
    @Volatile
    var currentRotation: Int = Surface.ROTATION_0
    @Volatile
    var screenWidth: Int = 1080
    @Volatile
    var screenHeight: Int = 2400

    fun init() {
        executorScope.launch {
            synchronized(suLock) {
                try {
                    if (suProcess == null) {
                        suProcess = Runtime.getRuntime().exec("su")
                        suWriter = OutputStreamWriter(suProcess!!.outputStream, Charsets.UTF_8)
                        Log.d(TAG, "Su shell başlatıldı")
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Su shell başlatılamadı: ${e.message}")
                }
            }

            detectInputDevices()
            startMoveWorker()
            autoEnableAccessibilityService()
        }
    }

    fun autoEnableAccessibilityService() {
        executorScope.launch {
            try {
                val serviceName = "com.falveyo.service/com.falveyo.service.FalveyoAccessibilityService"
                val script = "cur=\$(settings get secure enabled_accessibility_services) ; " +
                        "if [ \"\$cur\" = \"null\" ] || [ -z \"\$cur\" ]; then settings put secure enabled_accessibility_services \"$serviceName\" ; " +
                        "elif [[ \"\$cur\" != *\"$serviceName\"* ]]; then settings put secure enabled_accessibility_services \"\$cur:$serviceName\" ; fi ; " +
                        "settings put secure accessibility_enabled 1 ; " +
                        "appops set com.falveyo.service SYSTEM_ALERT_WINDOW allow ; " +
                        "pm grant com.falveyo.service android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null"

                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
                proc.waitFor()
                Log.d(TAG, "Erişilebilirlik ve System Overlay izinleri root üzerinden otomatik etkinleştirildi")
            } catch (e: Exception) {
                Log.e(TAG, "Erişilebilirlik otomatik açma hatası: ${e.message}")
            }
        }
    }

    private fun detectInputDevices() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "setenforce 0 ; chmod 666 /dev/input/event* ; getevent -lp"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var currentDev = "/dev/input/event1"
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("add device")) {
                    val parts = l.split(":")
                    if (parts.size >= 2) {
                        currentDev = parts[1].trim()
                    }
                }
                // Dokunmatik ekran tespiti
                if (l.contains("ABS_MT_POSITION_X") || l.contains("0035") || l.contains("BTN_TOUCH") || l.contains("touchscreen")) {
                    detectedTouchDevice = currentDev
                    hasDetectedTouchDevice = true
                    Log.d(TAG, "Dokunmatik ekran tespit edildi: $detectedTouchDevice")
                }
                // Klavye / Sistem tuşları cihazı tespiti (KEY_BACK, KEY_HOMEPAGE, KEY_ENTER vs.)
                if (l.contains("KEY_BACK") || l.contains("009e") || l.contains("KEY_HOMEPAGE") || l.contains("00ac") || l.contains("KEY_POWER")) {
                    detectedKeyDevice = currentDev
                    Log.d(TAG, "Tuş girdi cihazı tespit edildi: $detectedKeyDevice")
                }
            }
            reader.close()
            proc.destroy()

            // Doğrudan donanım dosyasına yazma denemesi (en yüksek performans)
            tryDirectDeviceOpen()
        } catch (e: Exception) {
            Log.w(TAG, "Girdi aygıtı tespiti hatası: ${e.message}")
        }
    }

    private fun tryDirectDeviceOpen() {
        closeKernelStreams()
        try {
            val file = File(detectedTouchDevice)
            if (file.exists() && file.canWrite()) {
                directTouchStream = FileOutputStream(file)
                Log.d(TAG, "Doğrudan donanım akışı açıldı (0ms kernel write): $detectedTouchDevice")
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "Doğrudan dosya akış denemesi: ${e.message}")
        }

        // SELinux kısıtlamasında doğrudan root binary pipe aç
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $detectedTouchDevice"))
            rootBinaryProcess = p
            rootBinaryStream = p.outputStream
            Log.d(TAG, "Root binary pipe akışı açıldı (120 FPS 0ms kernel write): $detectedTouchDevice")
        } catch (e: Exception) {
            Log.e(TAG, "Root binary pipe başlatılamadı: ${e.message}")
        }
    }

    private fun ensureKernelStream(): java.io.OutputStream? {
        val direct = directTouchStream
        if (direct != null) return direct

        val rootStream = rootBinaryStream
        if (rootStream != null) return rootStream

        synchronized(suLock) {
            try {
                if (rootBinaryStream == null) {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $detectedTouchDevice"))
                    rootBinaryProcess = p
                    rootBinaryStream = p.outputStream
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureKernelStream hatası: ${e.message}")
            }
            return rootBinaryStream
        }
    }

    private fun closeKernelStreams() {
        try { directTouchStream?.close() } catch (_: Exception) {}
        directTouchStream = null
        try { rootBinaryStream?.close() } catch (_: Exception) {}
        rootBinaryStream = null
        try { rootBinaryProcess?.destroy() } catch (_: Exception) {}
        rootBinaryProcess = null
    }

    fun updateScreenConfig(width: Int, height: Int, rotation: Int) {
        screenWidth = width
        screenHeight = height
        currentRotation = rotation
        Log.d(TAG, "InputExecutor ekran konfigürasyonu: ${width}x${height}, rotasyon: $rotation")
    }

    private fun mapToHardwareCoords(x: Float, y: Float): Pair<Int, Int> {
        val w = screenWidth.toFloat().coerceAtLeast(1f)
        val h = screenHeight.toFloat().coerceAtLeast(1f)

        return when (currentRotation) {
            Surface.ROTATION_90 -> {
                val hwX = (y).toInt()
                val hwY = (h - x).toInt()
                Pair(hwX, hwY)
            }
            Surface.ROTATION_270 -> {
                val hwX = (w - y).toInt()
                val hwY = (x).toInt()
                Pair(hwX, hwY)
            }
            Surface.ROTATION_180 -> {
                val hwX = (w - x).toInt()
                val hwY = (h - y).toInt()
                Pair(hwX, hwY)
            }
            else -> {
                Pair(x.toInt(), y.toInt())
            }
        }
    }

    // ----------------------------------------------------------------
    // CONFLATED MOVE WORKER (120 FPS - Kuyruk birikmesini 100% yok eder)
    // ----------------------------------------------------------------

    private fun startMoveWorker() {
        if (!isMovingWorkerRunning.compareAndSet(false, true)) return

        executorScope.launch {
            var lastSentX = -1
            var lastSentY = -1

            while (isActive) {
                val pos = pendingTouchMove.getAndSet(null)
                if (pos != null && (pos.first != lastSentX || pos.second != lastSentY)) {
                    lastSentX = pos.first
                    lastSentY = pos.second
                    dispatchHardwareMove(pos.first, pos.second)
                    delay(8L) // Aktif sürüklemede ~120 Hz
                } else {
                    delay(50L) // Boştayken CPU tasarrufu
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // ULTRA HIZLI ANLIK DOKUNMA VE İŞLEMLER (0ms / Non-blocking)
    // ----------------------------------------------------------------

    fun tap(x: Float, y: Float) {
        pendingTouchMove.set(null)
        val mapped = mapToHardwareCoords(x, y)
        val ix = mapped.first
        val iy = mapped.second
        val trackId = trackingIdCounter.incrementAndGet() % 65535

        val stream = ensureKernelStream()
        if (stream != null) {
            try {
                writeRawDown(stream, trackId, ix, iy)
                writeRawUp(stream)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct tap hatası, fallback su: ${e.message}")
                closeKernelStreams()
            }
        }

        val dev = detectedTouchDevice
        val cmd = "sendevent $dev 3 47 0 ; sendevent $dev 3 57 $trackId ; sendevent $dev 3 53 $ix ; sendevent $dev 3 54 $iy ; sendevent $dev 3 48 5 ; sendevent $dev 3 58 50 ; sendevent $dev 1 330 1 ; sendevent $dev 1 325 1 ; sendevent $dev 0 0 0 ; sendevent $dev 3 57 -1 ; sendevent $dev 1 330 0 ; sendevent $dev 1 325 0 ; sendevent $dev 0 0 0"
        exec(cmd)
    }

    fun touchDown(x: Float, y: Float) {
        pendingTouchMove.set(null)
        val mapped = mapToHardwareCoords(x, y)
        val ix = mapped.first
        val iy = mapped.second
        val trackId = trackingIdCounter.incrementAndGet() % 65535

        val stream = ensureKernelStream()
        if (stream != null) {
            try {
                writeRawDown(stream, trackId, ix, iy)
                Log.d(TAG, "TOUCH DOWN (Direct): $ix, $iy")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Direct touchDown hatası: ${e.message}")
                closeKernelStreams()
            }
        }

        val dev = detectedTouchDevice
        val cmd = "sendevent $dev 3 47 0 ; sendevent $dev 3 57 $trackId ; sendevent $dev 3 53 $ix ; sendevent $dev 3 54 $iy ; sendevent $dev 3 48 5 ; sendevent $dev 3 58 50 ; sendevent $dev 1 330 1 ; sendevent $dev 1 325 1 ; sendevent $dev 0 0 0"
        exec(cmd)
        Log.d(TAG, "TOUCH DOWN: visual (${x.toInt()}, ${y.toInt()}) -> hw ($ix, $iy)")
    }

    fun touchMove(x: Float, y: Float) {
        val mapped = mapToHardwareCoords(x, y)
        // Conflated: Eski bekleyen koordinatın üzerine doğrudan yenisini yazar, kuyruk asla şişmez
        pendingTouchMove.set(mapped)
    }

    private fun dispatchHardwareMove(ix: Int, iy: Int) {
        val stream = ensureKernelStream()
        if (stream != null) {
            try {
                writeRawMove(stream, ix, iy)
                return
            } catch (e: Exception) {
                closeKernelStreams()
            }
        }

        val dev = detectedTouchDevice
        val cmd = "sendevent $dev 3 47 0 ; sendevent $dev 3 53 $ix ; sendevent $dev 3 54 $iy ; sendevent $dev 0 0 0"
        exec(cmd)
    }

    fun touchUp(x: Float, y: Float) {
        // Bekleyen tüm ara hareketleri iptal et, UP sinyalini derhal gönder
        pendingTouchMove.set(null)

        val stream = ensureKernelStream()
        if (stream != null) {
            try {
                writeRawUp(stream)
                Log.d(TAG, "TOUCH UP (Direct)")
                return
            } catch (e: Exception) {
                closeKernelStreams()
            }
        }

        val dev = detectedTouchDevice
        val cmd = "sendevent $dev 3 47 0 ; sendevent $dev 3 57 -1 ; sendevent $dev 1 330 0 ; sendevent $dev 1 325 0 ; sendevent $dev 0 0 0"
        exec(cmd)
        Log.d(TAG, "TOUCH UP: ${x.toInt()}, ${y.toInt()}")
    }

    // ----------------------------------------------------------------
    // HAM LINUX INPUT EVENT YAZICI (0ms KERNEL EVDEV TYPE B)
    // ----------------------------------------------------------------

    private fun writeRawEvent(stream: java.io.OutputStream, type: Short, code: Short, value: Int) {
        val buf = ByteBuffer.allocate(if (is64BitSystem) 24 else 16).order(ByteOrder.LITTLE_ENDIAN)
        val nowMs = System.currentTimeMillis()
        val sec = nowMs / 1000L
        val usec = (nowMs % 1000L) * 1000L

        if (is64BitSystem) {
            buf.putLong(sec)
            buf.putLong(usec)
        } else {
            buf.putInt(sec.toInt())
            buf.putInt(usec.toInt())
        }
        buf.putShort(type)
        buf.putShort(code)
        buf.putInt(value)

        stream.write(buf.array())
    }

    private fun writeRawDown(stream: java.io.OutputStream, trackId: Int, ix: Int, iy: Int) {
        synchronized(stream) {
            writeRawEvent(stream, 3, 47, 0)       // EV_ABS, ABS_MT_SLOT, 0
            writeRawEvent(stream, 3, 57, trackId) // EV_ABS, ABS_MT_TRACKING_ID, trackId
            writeRawEvent(stream, 3, 53, ix)      // EV_ABS, ABS_MT_POSITION_X, ix
            writeRawEvent(stream, 3, 54, iy)      // EV_ABS, ABS_MT_POSITION_Y, iy
            writeRawEvent(stream, 3, 48, 5)       // EV_ABS, ABS_MT_TOUCH_MAJOR, 5
            writeRawEvent(stream, 3, 58, 50)      // EV_ABS, ABS_MT_PRESSURE, 50
            writeRawEvent(stream, 1, 330, 1)      // EV_KEY, BTN_TOUCH, 1
            writeRawEvent(stream, 1, 325, 1)      // EV_KEY, BTN_TOOL_FINGER, 1
            writeRawEvent(stream, 0, 0, 0)        // EV_SYN, SYN_REPORT, 0
            stream.flush()
        }
    }

    private fun writeRawMove(stream: java.io.OutputStream, ix: Int, iy: Int) {
        synchronized(stream) {
            writeRawEvent(stream, 3, 47, 0)       // EV_ABS, ABS_MT_SLOT, 0
            writeRawEvent(stream, 3, 53, ix)      // EV_ABS, ABS_MT_POSITION_X, ix
            writeRawEvent(stream, 3, 54, iy)      // EV_ABS, ABS_MT_POSITION_Y, iy
            writeRawEvent(stream, 0, 0, 0)        // EV_SYN, SYN_REPORT, 0
            stream.flush()
        }
    }

    private fun writeRawUp(stream: java.io.OutputStream) {
        synchronized(stream) {
            writeRawEvent(stream, 3, 47, 0)       // EV_ABS, ABS_MT_SLOT, 0
            writeRawEvent(stream, 3, 57, -1)      // EV_ABS, ABS_MT_TRACKING_ID, -1
            writeRawEvent(stream, 1, 330, 0)      // EV_KEY, BTN_TOUCH, 0
            writeRawEvent(stream, 1, 325, 0)      // EV_KEY, BTN_TOOL_FINGER, 0
            writeRawEvent(stream, 0, 0, 0)        // EV_SYN, SYN_REPORT, 0
            stream.flush()
        }
    }

    fun selectTextDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 650L) {
        val ix1 = x1.toInt()
        val iy1 = y1.toInt()
        val ix2 = x2.toInt()
        val iy2 = y2.toInt()
        val dur = durationMs.coerceIn(400L, 1200L)
        val cmd = "cmd input swipe $ix1 $iy1 $ix2 $iy2 $dur || input swipe $ix1 $iy1 $ix2 $iy2 $dur"
        exec(cmd, async = true)
        Log.d(TAG, "METİN SEÇİMİ DRAG: $ix1,$iy1 -> $ix2,$iy2 (${dur}ms)")
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 450L) {
        val ix = x.toInt()
        val iy = y.toInt()
        exec("cmd input swipe $ix $iy $ix $iy $durationMs || input swipe $ix $iy $ix $iy $durationMs", async = true)
        Log.d(TAG, "LONG PRESS: $ix, $iy (${durationMs}ms)")
    }

    private fun ensureSuProcess() {
        if (suWriter == null || suProcess == null) {
            try {
                val p = Runtime.getRuntime().exec("su")
                suProcess = p
                suWriter = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
                Log.d(TAG, "Kalıcı root shell hazırlandı")
            } catch (e: Exception) {
                Log.e(TAG, "Root shell başlatılamadı: ${e.message}")
            }
        }
    }

    private val isSwipeExecuting = AtomicBoolean(false)

    suspend fun executeSingleSwipeSync(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        if (!isSwipeExecuting.compareAndSet(false, true)) {
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val ix1 = x1.toInt()
                val iy1 = y1.toInt()
                val ix2 = x2.toInt()
                val iy2 = y2.toInt()
                val dur = durationMs.coerceIn(40, 250)

                val cmd = "input swipe $ix1 $iy1 $ix2 $iy2 $dur\n"

                synchronized(suLock) {
                    ensureSuProcess()
                    try {
                        suWriter?.write(cmd)
                        suWriter?.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Swipe write hatası: ${e.message}")
                        try {
                            suWriter?.close()
                            suProcess?.destroy()
                        } catch (_: Exception) {}
                        suWriter = null
                        suProcess = null
                        ensureSuProcess()
                        suWriter?.write(cmd)
                        suWriter?.flush()
                    }
                }

                delay(dur.toLong() + 30L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "executeSingleSwipeSync hatası: ${e.message}")
        } finally {
            isSwipeExecuting.set(false)
        }
    }

    suspend fun swipeAndWait(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 100) {
        executeSingleSwipeSync(x1, y1, x2, y2, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 100) {
        executorScope.launch {
            executeSingleSwipeSync(x1, y1, x2, y2, durationMs)
        }
    }

    fun cancelActiveSwipe() {
        isSwipeExecuting.set(false)
    }

    // ----------------------------------------------------------------
    // SİSTEM TUŞLARI (HOME, BACK, RECENTS, UP, DOWN, SELECT)
    // ----------------------------------------------------------------

    private var lastBackTimestamp = 0L
    private var lastHomeTimestamp = 0L
    private var lastRecentsTimestamp = 0L

    fun back() {
        val now = System.currentTimeMillis()
        if (now - lastBackTimestamp < 280L) {
            Log.d(TAG, "BACK debounced (çift tetikleme önlendi)")
            return
        }
        lastBackTimestamp = now
        // Tek ve temiz bir KEYCODE_BACK sinyali gönder
        exec("input keyevent 4 || cmd input keyevent 4")
        Log.d(TAG, "BACK (Tekil)")
    }

    fun home() {
        val now = System.currentTimeMillis()
        if (now - lastHomeTimestamp < 280L) {
            Log.d(TAG, "HOME debounced (çift tetikleme önlendi)")
            return
        }
        lastHomeTimestamp = now
        exec("input keyevent 3 || cmd input keyevent 3")
        Log.d(TAG, "HOME (Tekil)")
    }

    fun recents() {
        val now = System.currentTimeMillis()
        if (now - lastRecentsTimestamp < 280L) {
            Log.d(TAG, "RECENTS debounced (çift tetikleme önlendi)")
            return
        }
        lastRecentsTimestamp = now
        exec("input keyevent 187 || cmd input keyevent 187")
        Log.d(TAG, "RECENTS (Tekil)")
    }

    fun select() {
        val pos = GlobalCursorState.position.value
        tap(pos.x, pos.y)
        Log.d(TAG, "SELECT -> TAP at ${pos.x}, ${pos.y}")
    }

    fun up() {
        recents()
    }

    fun down() {
        back()
    }

    fun key(keyCode: Int, name: String = "KEY_$keyCode") {
        exec("input keyevent $keyCode || cmd input keyevent $keyCode")
        Log.d(TAG, "KEY: $name ($keyCode)")
    }

    // ----------------------------------------------------------------
    // SHELL YÖNETİMİ
    // ----------------------------------------------------------------

    private fun exec(command: String, async: Boolean = false) {
        executorScope.launch {
            synchronized(suLock) {
                val formatted = command.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ; ")
                if (formatted.isEmpty()) return@synchronized

                if (suWriter == null || suProcess == null) {
                    try {
                        val p = Runtime.getRuntime().exec("su")
                        suProcess = p
                        suWriter = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
                        Log.d(TAG, "Kalıcı root shell yeniden başlatıldı")
                    } catch (e: Exception) {
                        Log.e(TAG, "Root shell başlatılamadı: ${e.message}")
                    }
                }

                val writer = suWriter
                if (writer != null) {
                    try {
                        val lineToSend = if (async) "($formatted) &\n" else "$formatted\n"
                        writer.write(lineToSend)
                        writer.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "exec hatası: ${e.message}")
                        try {
                            suWriter?.close()
                            suProcess?.destroy()
                        } catch (_: Exception) {}
                        suWriter = null
                        suProcess = null
                    }
                }
            }
        }
    }

    fun destroy() {
        executorScope.launch {
            cancelActiveSwipe()
            pendingTouchMove.set(null)
            try {
                directTouchStream?.close()
            } catch (_: Exception) {}
            directTouchStream = null

            synchronized(suLock) {
                try {
                    suWriter?.write("exit\n")
                    suWriter?.flush()
                    suWriter?.close()
                    suProcess?.destroy()
                } catch (_: Exception) {}
                suWriter = null
                suProcess = null
                Log.d(TAG, "Su shell kapatıldı")
            }
        }
    }
}
