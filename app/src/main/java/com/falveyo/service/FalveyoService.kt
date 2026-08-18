package com.falveyo.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.hypot

private const val TAG = "FalveyoService"
private const val CHANNEL_ID = "falveyo_channel"
private const val NOTIF_ID = 1001

class FalveyoService : Service() {

    companion object {
        const val ACTION_START      = "com.falveyo.service.ACTION_START"
        const val ACTION_STOP       = "com.falveyo.service.ACTION_STOP"
        const val ACTION_CONNECT    = "com.falveyo.service.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.falveyo.service.ACTION_DISCONNECT"
        const val EXTRA_MAC_ADDRESS = "extra_mac_address"

        @Volatile
        var instance: FalveyoService? = null
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

    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            AppSettings.init(this)
            GlobalCursorState.setServiceRunning(true)

            createNotificationChannel()
            startForegroundCompat("Hazır. Cihaz bekleniyor...")

            // Kalıcı su shell başlat
            InputExecutor.init()
            Log.d(TAG, "Root shell hazır")

            val (w, h) = getScreenSize()
            val rotation = getScreenRotation()
            screenWidth = w
            screenHeight = h
            InputExecutor.updateScreenConfig(w, h, rotation)
            GlobalCursorState.updatePosition(w / 2f, h / 2f)
            Log.d(TAG, "Ekran Boyutu: ${w}x${h}, Rotasyon: $rotation")

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
        } catch (e: Exception) {
            Log.e(TAG, "FalveyoService onCreate hatası: ${e.message}", e)
        }

        serviceScope.launch {
            GlobalCursorState.connected.collectLatest { isConnected ->
                val text = if (isConnected)
                    "Bağlandı: ${GlobalCursorState.connectedDeviceName.value ?: "ESP32"}"
                else "Bağlantı bekleniyor..."
                updateNotification(text)
                if (!isConnected) {
                    stopEdgeScroll()
                    if (isTouchDown) {
                        isTouchDown = false
                        GlobalCursorState.setTouching(false)
                        val pos = GlobalCursorState.position.value
                        InputExecutor.touchUp(pos.x, pos.y)
                    }
                }
            }
        }

        serviceScope.launch {
            AppSettings.edgeScrollEnabled.collectLatest { enabled ->
                if (!enabled) {
                    stopEdgeScroll()
                }
            }
        }

        startEdgeScrollMonitor()

        Log.d(TAG, "FalveyoService başlatıldı")
    }

    // ----------------------------------------------------------------
    // KENAR KAYDIRMA MOTORU (Android Kinetik Fling Swipe - Pürüzsüz & Sıfır Kuyruk)
    // ----------------------------------------------------------------

    private var activeEdgeDirection = EdgeScrollDirection.NONE

    private fun startEdgeScrollMonitor() {
        serviceScope.launch {
            while (isActive) {
                val isEnabled = AppSettings.edgeScrollEnabled.value
                val isConn = GlobalCursorState.connected.value

                if (!isEnabled || !isConn || isTouchDown || screenWidth <= 100 || screenHeight <= 100) {
                    if (activeEdgeDirection != EdgeScrollDirection.NONE) {
                        stopEdgeScroll()
                    }
                    delay(40L)
                    continue
                }

                val pos = GlobalCursorState.position.value
                val joy = GlobalCursorState.joystickVector.value
                val margin = AppSettings.edgeScrollMargin.value

                // Kenar sınır kontrolleri
                val inBottom = pos.y >= screenHeight - margin
                val inTop = pos.y <= margin
                val inRight = pos.x >= screenWidth - margin
                val inLeft = pos.x <= margin

                val pushThreshold = 0.12f

                val isPushingBottom = inBottom && (joy.y > pushThreshold)
                val isPushingTop = inTop && (joy.y < -pushThreshold)
                val isPushingRight = inRight && (joy.x > pushThreshold)
                val isPushingLeft = inLeft && (joy.x < -pushThreshold)

                val dir = when {
                    isPushingBottom -> EdgeScrollDirection.BOTTOM
                    isPushingTop -> EdgeScrollDirection.TOP
                    isPushingRight -> EdgeScrollDirection.RIGHT
                    isPushingLeft -> EdgeScrollDirection.LEFT
                    else -> EdgeScrollDirection.NONE
                }

                // Kullanıcı joystick'i bıraktı, nötre çekti veya kenardan çıktı -> ANINDA DURDUR & SIFIRLA
                if (dir == EdgeScrollDirection.NONE) {
                    if (activeEdgeDirection != EdgeScrollDirection.NONE) {
                        stopEdgeScroll()
                    }
                    delay(30L)
                    continue
                }

                activeEdgeDirection = dir
                GlobalCursorState.setEdgeScrollingDirection(dir)

                val speedSetting = AppSettings.cursorSpeed.value.coerceAtLeast(1f)
                val userSpeedMultiplier = AppSettings.edgeScrollSpeed.value.coerceIn(0.25f, 4.0f)

                val pushIntensity = when (dir) {
                    EdgeScrollDirection.BOTTOM -> joy.y
                    EdgeScrollDirection.TOP -> -joy.y
                    EdgeScrollDirection.RIGHT -> joy.x
                    EdgeScrollDirection.LEFT -> -joy.x
                    EdgeScrollDirection.NONE -> 0f
                }.coerceIn(0.12f, 3.0f)

                val effectiveSpeed = (pushIntensity / (speedSetting * 0.4f)).coerceIn(0.35f, 2.5f) * userSpeedMultiplier

                // Pürüzsüz ve doğal Android kinetik fırlatması (Fling Swipe)
                val duration = (90f / effectiveSpeed.coerceAtLeast(0.6f)).toInt().coerceIn(60, 110)
                val distance = (screenHeight.coerceAtMost(screenWidth) * 0.22f * effectiveSpeed).coerceIn(120f, 500f)

                val centerX = screenWidth * 0.50f
                val centerY = screenHeight * 0.50f

                when (dir) {
                    EdgeScrollDirection.BOTTOM -> {
                        val startY = centerY + (distance * 0.5f)
                        val endY = centerY - (distance * 0.5f)
                        InputExecutor.swipeAndWait(centerX, startY, centerX, endY, duration)
                    }
                    EdgeScrollDirection.TOP -> {
                        val startY = centerY - (distance * 0.5f)
                        val endY = centerY + (distance * 0.5f)
                        InputExecutor.swipeAndWait(centerX, startY, centerX, endY, duration)
                    }
                    EdgeScrollDirection.RIGHT -> {
                        val startX = centerX + (distance * 0.5f)
                        val endX = centerX - (distance * 0.5f)
                        InputExecutor.swipeAndWait(startX, centerY, endX, centerY, duration)
                    }
                    EdgeScrollDirection.LEFT -> {
                        val startX = centerX - (distance * 0.5f)
                        val endX = centerX + (distance * 0.5f)
                        InputExecutor.swipeAndWait(startX, centerY, endX, centerY, duration)
                    }
                    EdgeScrollDirection.NONE -> {}
                }

                delay(30L)
            }
        }
    }

    fun stopEdgeScroll() {
        activeEdgeDirection = EdgeScrollDirection.NONE
        if (GlobalCursorState.edgeScrollingDirection.value != EdgeScrollDirection.NONE) {
            GlobalCursorState.setEdgeScrollingDirection(EdgeScrollDirection.NONE)
        }
    }

    // ----------------------------------------------------------------
    // ROTATION DEĞİŞİNCE
    // ----------------------------------------------------------------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = getScreenSize()
        val rotation = getScreenRotation()
        screenWidth = w
        screenHeight = h
        InputExecutor.updateScreenConfig(w, h, rotation)
        val cur = GlobalCursorState.position.value
        GlobalCursorState.updatePosition(
            cur.x.coerceIn(0f, w.toFloat()),
            cur.y.coerceIn(0f, h.toFloat())
        )
        Log.d(TAG, "Rotation değişti, yeni ekran: ${w}x${h}, rotasyon: $rotation")
        bleManager?.updateScreenSize(w, h)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServiceInternal()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val mac = intent.getStringExtra(EXTRA_MAC_ADDRESS)
                if (!mac.isNullOrEmpty()) {
                    bleManager?.connect(mac)
                }
            }
            ACTION_DISCONNECT -> bleManager?.disconnect()
        }
        return START_STICKY
    }

    // ----------------------------------------------------------------
    // KOMUT İŞLE (0ms Ultra Hızlı)
    // ----------------------------------------------------------------

    private fun handleCommand(command: String) {
        GlobalCursorState.setLastCommand(command)
        cursorOverlay?.notifyUserActivity()
        val raw = command.trim()
        if (raw.isEmpty()) return

        var normalized = raw.uppercase()

        // 1. Doğrudan Basılı Tutma (DOWN / PRESS) Olayları
        val isPressEvent = when {
            normalized in listOf("TOUCH_DOWN", "JOY_DOWN", "JOYSTICK_DOWN", "SW_DOWN", "SELECT_DOWN", "PRESS_DOWN", "BTN_DOWN", "MOUSE_DOWN", "SW:1", "SELECT:1", "BTN:1", "CLICK:1", "TOUCH:1", "BTN_SW:1", "JOY_SW:1", "BUTTON:1") -> true
            normalized.endsWith(":1") || normalized.endsWith(":DOWN") || normalized.endsWith(":PRESS") || normalized.endsWith("_DOWN") || normalized.endsWith("_PRESS") -> {
                normalized.contains("SW") || normalized.contains("SELECT") || normalized.contains("BTN") || normalized.contains("TOUCH") || normalized.contains("CLICK") || normalized.contains("BUTTON")
            }
            else -> false
        }

        if (isPressEvent) {
            onJoystickPress()
            return
        }

        // 2. Doğrudan Bırakma (UP / RELEASE) Olayları -> Seçimi Tamamla
        val isReleaseEvent = when {
            normalized in listOf("TOUCH_UP", "JOY_UP", "JOYSTICK_UP", "SW_UP", "SELECT_UP", "PRESS_UP", "BTN_UP", "MOUSE_UP", "SW:0", "SELECT:0", "BTN:0", "CLICK:0", "TOUCH:0", "BTN_SW:0", "JOY_SW:0", "BUTTON:0") -> true
            normalized.endsWith(":0") || normalized.endsWith(":UP") || normalized.endsWith(":RELEASE") || normalized.endsWith("_UP") || normalized.endsWith("_RELEASE") -> {
                normalized.contains("SW") || normalized.contains("SELECT") || normalized.contains("BTN") || normalized.contains("TOUCH") || normalized.contains("CLICK") || normalized.contains("BUTTON")
            }
            else -> false
        }

        if (isReleaseEvent) {
            onJoystickRelease()
            return
        }

        if (normalized == "TOUCH_MOVE" || normalized == "JOY_MOVE" || normalized == "JOYSTICK_MOVE") {
            onJoystickMove()
            return
        }

        // Ortak önekleri temizle (örn. "BUTTON:", "BTN:", "CMD:", "KEY:", "ACTION:", "EVENT:")
        val prefixes = listOf("BUTTON:", "BTN:", "CMD:", "KEY:", "ACTION:", "EVENT:", "BUTTON_", "BTN_")
        for (p in prefixes) {
            if (normalized.startsWith(p) && normalized.length > p.length) {
                normalized = normalized.removePrefix(p).trim()
            }
        }

        // Bırakma sinyallerini atla (diğer genel butonlar için)
        if (normalized.endsWith(":0") || normalized.endsWith(":RELEASE") || normalized.endsWith(":OFF") ||
            normalized.endsWith("_RELEASE") || normalized.endsWith("_UP") || normalized.endsWith(":UP")) {
            return
        }

        // Basış durum soneklerini temizle (örn. ":1", ":PRESS", ":CLICK", ":DOWN", ":ON")
        val suffixes = listOf(":1", ":PRESS", ":CLICK", ":DOWN", ":ON", "_PRESS", "_DOWN", "_CLICK")
        for (s in suffixes) {
            if (normalized.endsWith(s) && normalized.length > s.length) {
                normalized = normalized.removeSuffix(s).trim()
            }
        }

        val pos = GlobalCursorState.position.value

        when (normalized) {
            // HOME / ANA EKRAN TUŞU
            "HOME", "KEY_HOME", "KEY_HOMEPAGE", "HOMEPAGE", "GO_HOME", "HOME_BUTTON", "BTN3", "BUTTON3", "B3", "H", "3" -> {
                stopEdgeScroll()
                InputExecutor.home()
                GlobalCursorState.setStatusLog("HOME basıldı")
            }

            // UP / SON UYGULAMALAR TUŞU
            "UP", "KEY_UP", "DPAD_UP", "ARROW_UP", "UP_BUTTON", "BTN1", "BUTTON1", "B1", "U", "19", "RECENTS", "APP_SWITCH", "RECENT_APPS" -> {
                stopEdgeScroll()
                InputExecutor.recents()
                GlobalCursorState.setStatusLog("Son Uygulamalar basıldı")
            }

            // DOWN / GERİ TUŞU
            "DOWN", "KEY_DOWN", "DPAD_DOWN", "ARROW_DOWN", "DOWN_BUTTON", "BTN2", "BUTTON2", "B2", "D", "20", "BACK", "KEY_BACK", "BACK_BUTTON", "ESC", "ESCAPE", "4" -> {
                stopEdgeScroll()
                InputExecutor.back()
                GlobalCursorState.setStatusLog("Geri (BACK) basıldı")
            }

            // SELECT / TIKLAMA TUŞU
            "SELECT", "CLICK", "OK", "ENTER", "TAP", "PRESS", "KEY_ENTER", "KEY_SELECT", "23", "66" -> {
                stopEdgeScroll()
                InputExecutor.tap(pos.x, pos.y)
                GlobalCursorState.setStatusLog("SELECT basıldı")
            }

            // JOYSTICK DOKUNMA / TUTMA HAREKETLERİ
            "TOUCH_DOWN", "JOY_DOWN", "JOYSTICK_DOWN", "SW_DOWN" -> onJoystickPress()
            "TOUCH_UP", "JOY_UP", "JOYSTICK_UP", "SW_UP" -> onJoystickRelease()
            "TOUCH_MOVE", "JOY_MOVE", "JOYSTICK_MOVE" -> onJoystickMove()

            else -> {
                val keyNum = normalized.toIntOrNull()
                if (keyNum != null) {
                    stopEdgeScroll()
                    when (keyNum) {
                        3 -> InputExecutor.home()
                        4 -> InputExecutor.back()
                        19 -> InputExecutor.recents()
                        20 -> InputExecutor.back()
                        else -> InputExecutor.key(keyNum)
                    }
                    GlobalCursorState.setStatusLog("Tuş ($keyNum) basıldı")
                } else {
                    Log.w(TAG, "Bilinmeyen ESP32 komutu: '$command'")
                    GlobalCursorState.setStatusLog("Bilinmeyen komut: $command")
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // JOYSTICK BASMA / BASILI TUTMA / METİN SEÇME (GERÇEK İNSAN PARMAĞI GİBİ)
    // ----------------------------------------------------------------

    fun onJoystickPress() {
        stopEdgeScroll()
        touchDownTime = System.currentTimeMillis()
        isTouchDown = true
        val pos = GlobalCursorState.position.value
        touchStartX = pos.x
        touchStartY = pos.y

        GlobalCursorState.setTouching(true)
        // Gerçek insan parmağı gibi ekranda doğrudan donanımsal dokunmayı (ACTION_DOWN) başlatır
        // Bu sayede metne basılı tutulduğunda Android'in kendi metin seçme motoru kelimeyi anında seçer
        InputExecutor.touchDown(pos.x, pos.y)
        Log.d(TAG, "DOKUNMA (TOUCH DOWN) BAŞLADI @ ${pos.x.toInt()}, ${pos.y.toInt()}")
    }

    fun onJoystickMove() {
        if (!isTouchDown) return
        val pos = GlobalCursorState.position.value
        InputExecutor.touchMove(pos.x, pos.y)
    }

    fun onJoystickRelease() {
        val pos = GlobalCursorState.position.value
        val held = System.currentTimeMillis() - touchDownTime

        GlobalCursorState.setTouching(false)

        if (isTouchDown) {
            // Gerçek insan parmağı ekrandan kalkmış gibi dokunmayı (ACTION_UP) sonlandırır
            InputExecutor.touchUp(pos.x, pos.y)
            Log.d(TAG, "DOKUNMA (TOUCH UP) TAMAMLANDI (${held}ms)")
            if (held >= 400L) {
                GlobalCursorState.setStatusLog("Metin seçildi / Basılı tutuldu ($held ms)")
            } else {
                GlobalCursorState.setStatusLog("Tıklandı")
            }
        }

        isTouchDown = false
    }

    // ----------------------------------------------------------------
    // YARDIMCILAR
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Pair(1080, 2400)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val size = Point()
                wm.defaultDisplay.getRealSize(size)
                Pair(size.x, size.y)
            }
        } catch (e: Exception) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    @Suppress("DEPRECATION")
    private fun getScreenRotation(): Int {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (e: Exception) {
            Surface.ROTATION_0
        }
    }

    private fun startForegroundCompat(statusText: String) {
        try {
            val notification = buildNotification(statusText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground hatası: ${e.message}")
            try {
                startForeground(NOTIF_ID, buildNotification(statusText))
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground hatası: ${e2.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FALVEYO BLE Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FALVEYO arka plan kontrol ve BLE yönetimi"
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

        val stopIntent = Intent(this, FalveyoService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FALVEYO")
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
        stopEdgeScroll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIF_ID)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        stopEdgeScroll()
        if (isTouchDown) {
            isTouchDown = false
            GlobalCursorState.setTouching(false)
            val pos = GlobalCursorState.position.value
            InputExecutor.touchUp(pos.x, pos.y)
        }
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
