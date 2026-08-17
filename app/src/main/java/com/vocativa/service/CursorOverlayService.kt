package com.vocativa.service

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

    fun start() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        scope.launch {
            GlobalCursorState.position.collectLatest { point ->
                cursorView?.updatePosition(point.x, point.y)
            }
        }

        scope.launch {
            GlobalCursorState.connected.collectLatest { connected ->
                cursorView?.setVisible(connected)
            }
        }

        scope.launch {
            GlobalCursorState.touching.collectLatest { touching ->
                cursorView?.setTouching(touching)
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
    }

    fun stop() {
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
    private var isTouching = false
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

    fun updatePosition(x: Float, y: Float) {
        cx = x
        cy = y
        postInvalidateOnAnimation()
    }

    fun setVisible(v: Boolean) {
        visible = v
        postInvalidate()
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
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return

        val currentR = if (isTouching) radius * 1.25f else radius

        // Dış / Dolgu Çemberi
        canvas.drawCircle(cx, cy, currentR, fillPaint)
        canvas.drawCircle(cx, cy, currentR, borderPaint)

        // Merkez Hassasiyet Noktası (Nişangah / Center Precision Dot)
        canvas.drawCircle(cx, cy, 3f, centerDotPaint)

        // Basılı Tutma / Dokunma Efekti (Metin seçerken veya sürüklerken görsel geri bildirim)
        if (isTouching) {
            canvas.drawCircle(cx, cy, currentR + 6f, touchRingPaint)
        }
    }
}
