package com.vocativa.service

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private const val TAG = "VocativaInput"

object InputExecutor {

    // Kalıcı su shell
    private var suProcess: Process? = null
    private var suWriter: OutputStreamWriter? = null
    private var detectedTouchDevice: String = "/dev/input/event1"
    private var hasDetectedTouchDevice = false

    fun init() {
        try {
            suProcess = Runtime.getRuntime().exec("su")
            suWriter = OutputStreamWriter(suProcess!!.outputStream)
            Log.d(TAG, "Su shell başlatıldı")

            // Arka planda dokunmatik ekran donanım düğümünü tespit et
            detectTouchScreenDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Su shell başlatılamadı: ${e.message}")
        }
    }

    private fun detectTouchScreenDevice() {
        Thread {
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
                    if (l.contains("ABS_MT_POSITION_X") || l.contains("0035") || l.contains("TOUCH") || l.contains("touchscreen")) {
                        detectedTouchDevice = currentDev
                        hasDetectedTouchDevice = true
                        Log.d(TAG, "Dokunmatik ekran cihazı tespit edildi: $detectedTouchDevice")
                        break
                    }
                }
                reader.close()
                proc.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Dokunmatik ekran tespiti hatası: ${e.message}")
            }
        }.start()
    }

    fun destroy() {
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

    // ----------------------------------------------------------------
    // TEMEL DOKUNMA VE İŞLEMLER
    // ----------------------------------------------------------------

    fun tap(x: Float, y: Float) {
        val ix = x.toInt()
        val iy = y.toInt()
        when (AppSettings.touchMethod.value) {
            TouchInputMethod.MOTION_EVENT -> {
                exec("input tap $ix $iy")
            }
            TouchInputMethod.SEND_EVENT -> {
                val dev = detectedTouchDevice
                val cmd = """
                    sendevent $dev 3 57 1
                    sendevent $dev 3 53 $ix
                    sendevent $dev 3 54 $iy
                    sendevent $dev 3 58 50
                    sendevent $dev 0 0 0
                    sendevent $dev 3 57 -1
                    sendevent $dev 0 0 0
                """.trimIndent()
                exec(cmd)
            }
            TouchInputMethod.SWIPE_HYBRID -> {
                exec("input tap $ix $iy")
            }
        }
        Log.d(TAG, "TAP: $ix, $iy")
    }

    fun touchDown(x: Float, y: Float) {
        val ix = x.toInt()
        val iy = y.toInt()
        when (AppSettings.touchMethod.value) {
            TouchInputMethod.MOTION_EVENT -> {
                // Android 12+ motionevent DOWN veya fallback
                exec("input motionevent DOWN $ix $iy || sendevent $detectedTouchDevice 3 57 1 && sendevent $detectedTouchDevice 3 53 $ix && sendevent $detectedTouchDevice 3 54 $iy && sendevent $detectedTouchDevice 0 0 0")
            }
            TouchInputMethod.SEND_EVENT -> {
                exec(buildSendEventDown(detectedTouchDevice, ix, iy))
            }
            TouchInputMethod.SWIPE_HYBRID -> {
                // Swipe hibrit modda swipe başlangıcı
                exec("input motionevent DOWN $ix $iy")
            }
        }
        Log.d(TAG, "TOUCH DOWN: $ix, $iy")
    }

    fun touchMove(x: Float, y: Float) {
        val ix = x.toInt()
        val iy = y.toInt()
        when (AppSettings.touchMethod.value) {
            TouchInputMethod.MOTION_EVENT -> {
                exec("input motionevent MOVE $ix $iy || sendevent $detectedTouchDevice 3 53 $ix && sendevent $detectedTouchDevice 3 54 $iy && sendevent $detectedTouchDevice 0 0 0")
            }
            TouchInputMethod.SEND_EVENT -> {
                exec(buildSendEventMove(detectedTouchDevice, ix, iy))
            }
            TouchInputMethod.SWIPE_HYBRID -> {
                exec("input motionevent MOVE $ix $iy")
            }
        }
    }

    fun touchUp(x: Float, y: Float) {
        val ix = x.toInt()
        val iy = y.toInt()
        when (AppSettings.touchMethod.value) {
            TouchInputMethod.MOTION_EVENT -> {
                exec("input motionevent UP $ix $iy || sendevent $detectedTouchDevice 3 57 -1 && sendevent $detectedTouchDevice 0 0 0")
            }
            TouchInputMethod.SEND_EVENT -> {
                exec(buildSendEventUp(detectedTouchDevice))
            }
            TouchInputMethod.SWIPE_HYBRID -> {
                exec("input motionevent UP $ix $iy")
            }
        }
        Log.d(TAG, "TOUCH UP: $ix, $iy")
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 500L) {
        val ix = x.toInt()
        val iy = y.toInt()
        // Metin seçimi için en güvenilir Android komutu aynı koordinata uzun swipe atmaktır
        exec("input swipe $ix $iy $ix $iy $durationMs")
        Log.d(TAG, "LONG PRESS (Metin Seçme): $ix, $iy (${durationMs}ms)")
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int = 300) {
        exec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
    }

    fun back()   { exec("input keyevent 4");  Log.d(TAG, "BACK") }
    fun home()   { exec("input keyevent 3");  Log.d(TAG, "HOME") }
    fun select() { exec("input keyevent 23"); Log.d(TAG, "SELECT") }
    fun up()     { exec("input keyevent 19") }
    fun down()   { exec("input keyevent 20") }

    // ----------------------------------------------------------------
    // SHELL YÖNETİMİ
    // ----------------------------------------------------------------

    private fun exec(command: String) {
        val writer = suWriter
        if (writer != null) {
            try {
                val singleLine = command.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" && ")
                writer.write("$singleLine\n")
                writer.flush()
            } catch (e: Exception) {
                Log.e(TAG, "exec hatası: ${e.message}")
                init()
            }
        } else {
            Thread {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                } catch (e: Exception) {
                    Log.e(TAG, "fallback exec hatası: ${e.message}")
                }
            }.start()
        }
    }

    private fun buildSendEventDown(dev: String, x: Int, y: Int) = """
        sendevent $dev 3 57 1
        sendevent $dev 3 53 $x
        sendevent $dev 3 54 $y
        sendevent $dev 3 58 50
        sendevent $dev 0 0 0
    """.trimIndent()

    private fun buildSendEventMove(dev: String, x: Int, y: Int) = """
        sendevent $dev 3 53 $x
        sendevent $dev 3 54 $y
        sendevent $dev 0 0 0
    """.trimIndent()

    private fun buildSendEventUp(dev: String) = """
        sendevent $dev 3 57 -1
        sendevent $dev 0 0 0
    """.trimIndent()
}
