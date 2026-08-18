package com.falveyo.service

import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "FalveyoInput"

object InputExecutor {

    // Kalıcı su shell
    private var suProcess: Process? = null
    private var suWriter: OutputStreamWriter? = null
    private val suLock = Any()
    private var detectedTouchDevice: String = "/dev/input/event1"
    private var detectedKeyDevice: String? = null
    private var hasDetectedTouchDevice = false

    private val executorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trackingIdCounter = AtomicInteger(1)

    fun init() {
        executorScope.launch {
            synchronized(suLock) {
                try {
                    if (suProcess == null) {
                        suProcess = Runtime.getRuntime().exec("su")
                        suWriter = OutputStreamWriter(suProcess!!.outputStream)
                        Log.d(TAG, "Su shell başlatıldı")
                    }
                    Unit
                } catch (e: Exception) {
                    Log.e(TAG, "Su shell başlatılamadı: ${e.message}")
                }
            }
            detectInputDevices()
        }
    }

    private fun detectInputDevices() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getevent -lp"))
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
        } catch (e: Exception) {
            Log.w(TAG, "Girdi aygıtı tespiti hatası: ${e.message}")
        }
    }

    fun destroy() {
        executorScope.launch {
            cancelActiveSwipe()
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

    // ----------------------------------------------------------------
    // ULTRA HIZLI ANLIK DOKUNMA VE İŞLEMLER (0ms / Non-blocking)
    // ----------------------------------------------------------------

    fun tap(x: Float, y: Float) {
        val mapped = mapToHardwareCoords(x, y)
        val ix = mapped.first
        val iy = mapped.second
        val trackId = trackingIdCounter.incrementAndGet() % 65535

        when (AppSettings.touchMethod.value) {
            TouchInputMethod.SEND_EVENT -> {
                val dev = detectedTouchDevice
                val cmd = """
                    sendevent $dev 1 330 1
                    sendevent $dev 3 57 $trackId
                    sendevent $dev 3 53 $ix
                    sendevent $dev 3 54 $iy
                    sendevent $dev 3 58 50
                    sendevent $dev 0 0 0
                    sendevent $dev 1 330 0
                    sendevent $dev 3 57 -1
                    sendevent $dev 0 0 0
                """.trimIndent()
                exec(cmd)
            }
            TouchInputMethod.MOTION_EVENT -> {
                val dev = detectedTouchDevice
                val cmd = "sendevent $dev 1 330 1; sendevent $dev 3 57 $trackId; sendevent $dev 3 53 $ix; sendevent $dev 3 54 $iy; sendevent $dev 0 0 0; sendevent $dev 1 330 0; sendevent $dev 3 57 -1; sendevent $dev 0 0 0 || cmd input tap ${x.toInt()} ${y.toInt()} || input tap ${x.toInt()} ${y.toInt()}"
                exec(cmd)
            }
            TouchInputMethod.SWIPE_HYBRID -> {
                // cmd input ekran rotasyonunu otomatik anlar
                exec("cmd input tap ${x.toInt()} ${y.toInt()} || input tap ${x.toInt()} ${y.toInt()}", async = true)
            }
        }
        Log.d(TAG, "TAP: visual (${x.toInt()}, ${y.toInt()}) -> hw ($ix, $iy)")
    }

    fun touchDown(x: Float, y: Float) {
        val mapped = mapToHardwareCoords(x, y)
        val ix = mapped.first
        val iy = mapped.second
        val dev = detectedTouchDevice
        val trackId = trackingIdCounter.incrementAndGet() % 65535
        val cmd = """
            sendevent $dev 1 330 1
            sendevent $dev 3 57 $trackId
            sendevent $dev 3 53 $ix
            sendevent $dev 3 54 $iy
            sendevent $dev 3 58 50
            sendevent $dev 0 0 0
        """.trimIndent()
        exec(cmd)
        Log.d(TAG, "TOUCH DOWN: visual (${x.toInt()}, ${y.toInt()}) -> hw ($ix, $iy)")
    }

    fun touchMove(x: Float, y: Float) {
        val mapped = mapToHardwareCoords(x, y)
        val ix = mapped.first
        val iy = mapped.second
        val dev = detectedTouchDevice
        val cmd = """
            sendevent $dev 3 53 $ix
            sendevent $dev 3 54 $iy
            sendevent $dev 0 0 0
        """.trimIndent()
        exec(cmd)
    }

    fun touchUp(x: Float, y: Float) {
        val dev = detectedTouchDevice
        val cmd = """
            sendevent $dev 1 330 0
            sendevent $dev 3 57 -1
            sendevent $dev 0 0 0
        """.trimIndent()
        exec(cmd)
        Log.d(TAG, "TOUCH UP: ${x.toInt()}, ${y.toInt()}")
    }

    // Ekran rotasyon bilgisi (0 = Dikey, 1 = 90 derece Yatay, 2 = 180 Ters, 3 = 270 Ters Yatay)
    @Volatile
    var currentRotation: Int = Surface.ROTATION_0
    @Volatile
    var screenWidth: Int = 1080
    @Volatile
    var screenHeight: Int = 2400

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
                // Saat yönünde 90 derece yatay: visual x -> hardware y, visual y -> hardware (h - x)
                val hwX = (y).toInt()
                val hwY = (h - x).toInt()
                Pair(hwX, hwY)
            }
            Surface.ROTATION_270 -> {
                // Saat yönünün tersi 270 derece yatay
                val hwX = (w - y).toInt()
                val hwY = (x).toInt()
                Pair(hwX, hwY)
            }
            Surface.ROTATION_180 -> {
                // Baş aşağı dikey
                val hwX = (w - x).toInt()
                val hwY = (h - y).toInt()
                Pair(hwX, hwY)
            }
            else -> {
                // Standart dikey (ROTATION_0)
                Pair(x.toInt(), y.toInt())
            }
        }
    }

    fun selectTextDrag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 850L) {
        val ix1 = x1.toInt()
        val iy1 = y1.toInt()
        val ix2 = x2.toInt()
        val iy2 = y2.toInt()
        val dur = durationMs.coerceIn(500L, 1600L)
        // Android'de metin seçimi için swipe süresi 700-1000ms olmalıdır (çok hızlı olursa kaydırma / fling sanılır)
        val cmd = "cmd input swipe $ix1 $iy1 $ix2 $iy2 $dur || input swipe $ix1 $iy1 $ix2 $iy2 $dur || input draganddrop $ix1 $iy1 $ix2 $iy2 $dur"
        exec(cmd, async = true)
        Log.d(TAG, "METİN SEÇİMİ DRAG: $ix1,$iy1 -> $ix2,$iy2 (${dur}ms)")
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 500L) {
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

    private val isSwipeExecuting = java.util.concurrent.atomic.AtomicBoolean(false)

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
                        // Shell yeniden oluştur
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

                // Kaydırmanın ekranda tamamlanmasını bekle
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

    fun back() {
        val keyDev = detectedKeyDevice
        val sendeventPrefix = if (keyDev != null) "sendevent $keyDev 1 158 1 ; sendevent $keyDev 0 0 0 ; sendevent $keyDev 1 158 0 ; sendevent $keyDev 0 0 0 ; " else ""
        exec("${sendeventPrefix}input keyevent 4 || cmd input keyevent 4 || input keyevent KEYCODE_BACK")
        Log.d(TAG, "BACK")
    }

    fun home() {
        val keyDev = detectedKeyDevice
        val sendeventPrefix = if (keyDev != null) "sendevent $keyDev 1 172 1 ; sendevent $keyDev 0 0 0 ; sendevent $keyDev 1 172 0 ; sendevent $keyDev 0 0 0 ; sendevent $keyDev 1 102 1 ; sendevent $keyDev 0 0 0 ; sendevent $keyDev 1 102 0 ; sendevent $keyDev 0 0 0 ; " else ""
        exec("${sendeventPrefix}input keyevent 3 || cmd input keyevent 3 || input keyevent KEYCODE_HOME")
        Log.d(TAG, "HOME")
    }

    fun recents() {
        val keyDev = detectedKeyDevice
        val sendeventPrefix = if (keyDev != null) "sendevent $keyDev 1 187 1 ; sendevent $keyDev 0 0 0 ; sendevent $keyDev 1 187 0 ; sendevent $keyDev 0 0 0 ; " else ""
        exec("${sendeventPrefix}input keyevent 187 || cmd input keyevent 187 || input keyevent KEYCODE_APP_SWITCH")
        Log.d(TAG, "RECENTS (APP_SWITCH)")
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

                // Oturum kapalıysa kalıcı shell'i yeniden başlat
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
}
