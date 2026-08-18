package com.falveyo.service

import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class CursorOverlayService(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var autoHideJob: Job? = null
    private val AUTO_HIDE_DELAY_MS = 20_000L // 20 saniye hareketsizlikte gizle

    fun notifyUserActivity() {
        cursorView?.setInactivityHidden(false)
        resetAutoHideTimer()
    }

    private fun resetAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(AUTO_HIDE_DELAY_MS)
            cursorView?.setInactivityHidden(true)
        }
    }

    fun start() {
        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            cursorView = CursorView(context)

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(cursorView, params)
            resetAutoHideTimer()
        } catch (e: Exception) {
            android.util.Log.e("CursorOverlayService", "Overlay eklenemedi (İzin gerekebilir): ${e.message}")
        }

        scope.launch {
            GlobalCursorState.position.collectLatest { point ->
                cursorView?.updatePosition(point.x, point.y)
                notifyUserActivity()
            }
        }

        scope.launch {
            GlobalCursorState.connected.collectLatest { connected ->
                cursorView?.setVisible(connected)
                if (connected) notifyUserActivity()
            }
        }

        scope.launch {
            GlobalCursorState.touching.collectLatest { touching ->
                cursorView?.setTouching(touching)
                notifyUserActivity()
            }
        }

        scope.launch {
            AppSettings.cursorRadius.collectLatest { radius ->
                cursorView?.setRadius(radius)
            }
        }

        scope.launch {
            AppSettings.cursorColor.collectLatest { color ->
                cursorView?.setCursorColor(color)
            }
        }

        scope.launch {
            GlobalCursorState.edgeScrollingDirection.collectLatest { dir ->
                cursorView?.setEdgeScrollDirection(dir)
                if (dir != EdgeScrollDirection.NONE) notifyUserActivity()
            }
        }
    }

    fun stop() {
        autoHideJob?.cancel()
        scope.cancel()
        cursorView?.let { windowManager?.removeView(it) }
        cursorView = null
        windowManager = null
    }
}

class CursorView(context: Context) : View(context) {

    private var cx = 0f
    private var cy = 0f
    private var visible = false
    private var isHiddenByInactivity = false
    private var isTouching = false
    private var edgeDirection = EdgeScrollDirection.NONE
    private var radius = AppSettings.DEFAULT_CURSOR_RADIUS

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 229, 255)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 64, 129)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 0, 229, 255)
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val arrowPath = Path()

    fun updatePosition(x: Float, y: Float) {
        cx = x
        cy = y
        postInvalidateOnAnimation()
    }

    fun setVisible(v: Boolean) {
        visible = v
        postInvalidate()
    }

    fun setInactivityHidden(hidden: Boolean) {
        if (isHiddenByInactivity != hidden) {
            isHiddenByInactivity = hidden
            postInvalidate()
        }
    }

    fun setTouching(touching: Boolean) {
        isTouching = touching
        postInvalidate()
    }

    fun setRadius(r: Float) {
        radius = r
        postInvalidate()
    }

    fun setCursorColor(colorInt: Int) {
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)
        fillPaint.color = Color.argb(170, r, g, b)
        edgeGlowPaint.color = Color.argb(190, r, g, b)
        postInvalidate()
    }

    fun setEdgeScrollDirection(dir: EdgeScrollDirection) {
        edgeDirection = dir
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible || isHiddenByInactivity) return

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. KENAR OTOMATİK KAYDIRMA GÖRSEL GERİ BİLDİRİMİ
        if (edgeDirection != EdgeScrollDirection.NONE) {
            arrowPath.reset()
            when (edgeDirection) {
                EdgeScrollDirection.BOTTOM -> {
                    canvas.drawRect(0f, h - 14f, w, h, edgeGlowPaint)
                    val midX = cx.coerceIn(50f, w - 50f)
                    arrowPath.moveTo(midX - 18f, h - 38f)
                    arrowPath.lineTo(midX + 18f, h - 38f)
                    arrowPath.lineTo(midX, h - 18f)
                    arrowPath.close()
                    canvas.drawPath(arrowPath, arrowPaint)
                }
                EdgeScrollDirection.TOP -> {
                    canvas.drawRect(0f, 0f, w, 14f, edgeGlowPaint)
                    val midX = cx.coerceIn(50f, w - 50f)
                    arrowPath.moveTo(midX - 18f, 38f)
                    arrowPath.lineTo(midX + 18f, 38f)
                    arrowPath.lineTo(midX, 18f)
                    arrowPath.close()
                    canvas.drawPath(arrowPath, arrowPaint)
                }
                EdgeScrollDirection.RIGHT -> {
                    canvas.drawRect(w - 14f, 0f, w, h, edgeGlowPaint)
                    val midY = cy.coerceIn(50f, h - 50f)
                    arrowPath.moveTo(w - 38f, midY - 18f)
                    arrowPath.lineTo(w - 38f, midY + 18f)
                    arrowPath.lineTo(w - 18f, midY)
                    arrowPath.close()
                    canvas.drawPath(arrowPath, arrowPaint)
                }
                EdgeScrollDirection.LEFT -> {
                    canvas.drawRect(0f, 0f, 14f, h, edgeGlowPaint)
                    val midY = cy.coerceIn(50f, h - 50f)
                    arrowPath.moveTo(38f, midY - 18f)
                    arrowPath.lineTo(38f, midY + 18f)
                    arrowPath.lineTo(18f, midY)
                    arrowPath.close()
                    canvas.drawPath(arrowPath, arrowPaint)
                }
                EdgeScrollDirection.NONE -> {}
            }
        }

        // 2. İMLEÇ VE DOKUNMA/BASILI TUTMA ÇİZİMİ
        val currentR = if (isTouching) radius * 1.30f else radius

        canvas.drawCircle(cx, cy, currentR, fillPaint)
        canvas.drawCircle(cx, cy, currentR, borderPaint)
        canvas.drawCircle(cx, cy, 3f, centerDotPaint)

        if (isTouching) {
            canvas.drawCircle(cx, cy, currentR + 7f, touchRingPaint)
        }
    }
}
