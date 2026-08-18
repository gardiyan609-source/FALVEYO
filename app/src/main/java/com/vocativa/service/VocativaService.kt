package com.vocativa.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.pow

private const val TAG = "VocativaService"
private const val CHANNEL_ID = "vocativa_channel"
private const val NOTIF_ID = 1001

class VocativaService : Service() {

    companion object {
        const val ACTION_START      = "com.vocativa.service.ACTION_START"
        const val ACTION_STOP       = "com.vocativa.service.ACTION_STOP"
        const val ACTION_CONNECT    = "com.vocativa.service.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.vocativa.service.ACTION_DISCONNECT"
        const val EXTRA_MAC_ADDRESS = "extra_mac_address"

        @Volatile
        var instance: VocativaService? = null
            private set
    }

    var bleManager: BleManager? = null
        private set

    private var cursorOverlay: CursorOverlayService? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var touchDownTime = 0L
    private var isTouchDown = false
    private var touchStartX = 0f
    private var touchStartY = 0f

    // Edge scroll scroller
    private var edgeScroller: SmoothScrollController? = null

    // Ardışık hızlı basmaları (debounce) önlemek için zaman damgaları
    private val lastCommandTimestamps: MutableMap<String, Long> = mutableMapOf()
    private var minCommandIntervalMs: Long = 200L // 200ms içinde gelen aynı komutu yoksay

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppSettings.init(this)
        GlobalCursorState.setServiceRunning(true)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Hazır. Cihaz bekleniyor..."))

        // Kalıcı su shell başlat
        Thread {
            InputExecutor.init()
            Log.d(TAG, "Root shell hazır")
        }.start()

        val (w, h) = getScreenSize()
        Log.d(TAG, "Ekran Boyutu: ${w}x${h}")

        try {
            cursorOverlay = CursorOverlayService(this)
            cursorOverlay?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Overlay başlatma hatası: ${e.message}")
        }

        bleManager = BleManager(
            context = this,
            screenWidth = w,
            screenHeight = h,
            onCommand = { command -> handleCommand(command) }
        )

        // --- Yeni: Edge scroll entegrasyonu ---
        try {
            setupEdgeScroll(w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Edge scroll setup hatası: ${e.message}")
        }

        serviceScope.launch {
            GlobalCursorState.connected.collectLatest { isConnected ->
                val text = if (isConnected)
                    "Bağlandı: ${GlobalCursorState.connectedDeviceName.value ?: "ESP32"}"
                else "Bağlantı bekleniyor..."
                updateNotification(text)
            }
        }

        Log.d(TAG, "VocativaService başlatıldı")
    }

    // ----------------------------------------------------------------
    // ROTATION DEĞİŞİNCE
    // ----------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = getScreenSize()
        Log.d(TAG, "Rotation değişti, yeni ekran: ${w}x${h}")
        bleManager?.updateScreenSize(w, h)
        // ekran boyutu değişince scroller'ı da güncelle
        try {
            edgeScroller?.stop()
            setupEdgeScroll(w, h)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> bleManager?.disconnect()
        }
        return START_STICKY
    }

    // ----------------------------------------------------------------
    // KOMUT İŞLE
    // ----------------------------------------------------------------

    private fun shouldHandleCommand(command: String): Boolean {
        // TOUCH_MOVE / TOUCH_DOWN / TOUCH_UP genelde sürekli gelmesi gereken eventlerdir, bunları debounce etmiyoruz
        if (command == "TOUCH_MOVE" || command == "TOUCH_DOWN" || command == "TOUCH_UP") return true

        val now = System.currentTimeMillis()
        val last = lastCommandTimestamps[command] ?: 0L
        if (now - last < minCommandIntervalMs) {
            Log.d(TAG, "Komut atlandı (çok hızlı tekrar): $command")
            return false
        }
        lastCommandTimestamps[command] = now
        return true
    }

    private fun handleCommand(command: String) {
        if (!shouldHandleCommand(command)) return

        val pos = GlobalCursorState.position.value
        when (command) {
            "SELECT"     -> InputExecutor.tap(pos.x, pos.y)
            "BACK"       -> InputExecutor.back()
            "HOME"       -> InputExecutor.home()
            "UP"         -> InputExecutor.up()
            "DOWN"       -> InputExecutor.down()
            "TOUCH_DOWN" -> onJoystickPress()
            "TOUCH_UP"   -> onJoystickRelease()
            "TOUCH_MOVE" -> onJoystickMove()
        }
    }

    // ----------------------------------------------------------------
    // JOYSTICK BASMA / TUTMA / SÜRÜKLEME / METİN SEÇME
    // ----------------------------------------------------------------

    fun onJoystickPress() {
        touchDownTime = System.currentTimeMillis()
        isTouchDown = true
        val pos = GlobalCursorState.position.value
        touchStartX = pos.x
        touchStartY = pos.y

        InputExecutor.touchDown(pos.x, pos.y)
        GlobalCursorState.setTouching(true)
        Log.d(TAG, "TOUCH DOWN @ ${pos.x.toInt()},${pos.y.toInt()}")

        // dokunma başladığında edge scroll'u durdur
        edgeScroller?.velocityX = 0f
        edgeScroller?.velocityY = 0f
    }

    fun onJoystickMove() {
        if (!isTouchDown) return
        val pos = GlobalCursorState.position.value
        InputExecutor.touchMove(pos.x, pos.y)
    }

    fun onJoystickRelease() {
        val pos = GlobalCursorState.position.value
        val held = System.currentTimeMillis() - touchDownTime
        val moveDist = hypot(pos.x - touchStartX, pos.y - touchStartY)
        val longPressThreshold = AppSettings.longPressMs.value

        if (isTouchDown) {
            InputExecutor.touchUp(pos.x, pos.y)

            // Eğer hareket etmeden uzun süre basılı tutulduysa ve bazı uygulamalar native algılamadıysa
            if (held >= longPressThreshold && moveDist < 12f) {
                Log.d(TAG, "UZUN BASILI TUTMA TAMAMLANDI (${held}ms)")
            } else if (held < longPressThreshold && moveDist < 12f) {
                Log.d(TAG, "KISA DOKUNMA TAMAMLANDI")
            } else {
                Log.d(TAG, "SÜRÜKLEME / METİN SEÇME TAMAMLANDI (${moveDist.toInt()}px)")
            }
        }

        isTouchDown = false
        GlobalCursorState.setTouching(false)
    }

    // ----------------------------------------------------------------
    // EDGE SCROLL SETUP
    // ----------------------------------------------------------------

    private fun setupEdgeScroll(screenW: Int, screenH: Int) {
        // scrollAction: her frame gelen dx,dy -> InputExecutor ile kısa swipe at
        val scroller = SmoothScrollController({ dx, dy ->
            // dy: px to move this frame. Kullanıcı pozisyonuna göre küçük swipe yap
            val pos = GlobalCursorState.position.value
            // eğer dy 0 ise atlama
            if (dy == 0) return@SmoothScrollController
            try {
                // Küçük adımlarla hızlı süregelen swipe'lar yapmak, duration kısa olmalı
                val duration = 30
                InputExecutor.swipe(pos.x, pos.y, pos.x, pos.y + dy.toFloat(), duration)
            } catch (t: Throwable) {
                Log.w(TAG, "Edge scroll exec hatası: ${t.message}")
            }
        })

        scroller.dampingPerFrame = 0.985f
        scroller.stopThresholdPxPerSec = 6f
        scroller.startIfNeeded()
        edgeScroller = scroller

        // Parametreler - isteğe göre AppSettings'ten çekebilirsiniz
        val maxSpeedPxPerSec = 2800f
        val exponent = 1.6f
        val deadzonePx = 18

        // Pozisyon akışını dinle ve hedef hızı güncelle
        serviceScope.launch {
            GlobalCursorState.position.collectLatest { point ->
                // Eğer kullanıcı dokunuyorsa edge-scroll ile çakışmasını önle
                if (GlobalCursorState.touching.value) {
                    scroller.velocityX = 0f
                    scroller.velocityY = 0f
                    return@collectLatest
                }

                val nx = JoystickEdgeMath.normalizedEdgeFraction(point.x, screenW, deadzonePx)
                val ny = JoystickEdgeMath.normalizedEdgeFraction(point.y, screenH, deadzonePx)

                val speedX = if (nx == 0f) 0f else (if (nx >= 0) 1f else -1f) * (kotlin.math.abs(nx).pow(exponent)) * maxSpeedPxPerSec
                val speedY = if (ny == 0f) 0f else (if (ny >= 0) 1f else -1f) * (kotlin.math.abs(ny).pow(exponent)) * maxSpeedPxPerSec

                scroller.velocityX = speedX
                scroller.velocityY = speedY

                if (speedX == 0f && speedY == 0f) {
                    // isterseniz scroller.stop() yapabilirsiniz
                } else {
                    scroller.startIfNeeded()
                }
            }
        }

        // Touching akışını dinleyip bırakıldığında sıfırlama
        serviceScope.launch {
            GlobalCursorState.touching.collectLatest { touching ->
                if (!touching) {
                    // bırakınca scroller yumuşakça sönümlensin
                    scroller.velocityX = 0f
                    scroller.velocityY = 0f
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // YARDIMCILAR
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: return Pair(1080, 2400)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val size = Point()
            wm.defaultDisplay.getRealSize(size)
            Pair(size.x, size.y)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vocativa BLE Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Vocativa arka plan kontrol ve BLE yönetimi"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPI = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val stopIntent = Intent(this, VocativaService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vocativa")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openPI)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", stopPI)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(statusText))
    }

    fun stopServiceInternal() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        serviceScope.cancel()
        bleManager?.destroy()
        bleManager = null
        cursorOverlay?.stop()
        cursorOverlay = null
        InputExecutor.destroy()
        GlobalCursorState.setServiceRunning(false)
        GlobalCursorState.setConnected(false)
        GlobalCursorState.setIsScanning(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
        super.onDestroy()
    }
}
