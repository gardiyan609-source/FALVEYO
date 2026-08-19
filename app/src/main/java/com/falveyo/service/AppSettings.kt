package com.falveyo.service

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TouchInputMethod(val title: String, val description: String) {
    SEND_EVENT("Ultra Düşük Gecikmeli Sendevent (Önerilen)", "Linux çekirdeğine doğrudan donanım sinyali gönderir. Tuş ve dokunma gecikmesini sıfıra indirir."),
    MOTION_EVENT("Modern Motionevent API", "Android motionevent komutları üzerinden çalışır."),
    SWIPE_HYBRID("Swipe & Tap Hibrit", "Android standart input araçları ile çalışır.")
}

object AppSettings {
    private const val PREFS_NAME = "falveyo_settings"

    private const val KEY_CURSOR_SPEED = "cursor_speed"
    private const val KEY_FINE_SENSITIVITY = "fine_sensitivity"
    private const val KEY_DEADZONE = "deadzone"
    private const val KEY_SMOOTHING_ENABLED = "smoothing_enabled"
    private const val KEY_SMOOTHING_FACTOR = "smoothing_factor"
    private const val KEY_LONG_PRESS_MS = "long_press_ms"
    private const val KEY_TOUCH_METHOD = "touch_method"
    private const val KEY_CURSOR_RADIUS = "cursor_radius"
    private const val KEY_CURSOR_COLOR = "cursor_color"
    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_EDGE_SCROLL_ENABLED = "edge_scroll_enabled"
    private const val KEY_EDGE_SCROLL_DELAY_MS = "edge_scroll_delay_ms"
    private const val KEY_EDGE_SCROLL_SPEED = "edge_scroll_speed"
    private const val KEY_EDGE_SCROLL_MARGIN = "edge_scroll_margin"
    private const val KEY_DARK_MODE = "dark_mode"

    // Varsayılan Değerler
    const val DEFAULT_SPEED = 6f
    const val DEFAULT_SENSITIVITY = 1.4f // 1.0 (Lineer) ile 2.0 (Karesel) arası eğri
    const val DEFAULT_DEADZONE = 0.03f // %3 ölü bölge (mikro hareketleri kaçırmaz)
    const val DEFAULT_SMOOTHING_ENABLED = true
    const val DEFAULT_SMOOTHING_FACTOR = 0.45f // Düşük geçiren filtre katsayısı
    const val DEFAULT_LONG_PRESS_MS = 450L // 450ms (Android standart metin ve alan seçimi uzun basış süresi)
    const val DEFAULT_CURSOR_RADIUS = 18f
    const val DEFAULT_CURSOR_COLOR = 0xFF00E5FF.toInt()

    const val DEFAULT_EDGE_SCROLL_ENABLED = false
    const val DEFAULT_EDGE_SCROLL_DELAY_MS = 600L
    const val DEFAULT_EDGE_SCROLL_SPEED = 1.0f
    const val DEFAULT_EDGE_SCROLL_MARGIN = 40f // px
    const val DEFAULT_DARK_MODE = true

    private var prefs: SharedPreferences? = null

    private val _isDarkMode = MutableStateFlow(DEFAULT_DARK_MODE)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _cursorSpeed = MutableStateFlow(DEFAULT_SPEED)
    val cursorSpeed: StateFlow<Float> = _cursorSpeed.asStateFlow()

    private val _fineSensitivity = MutableStateFlow(DEFAULT_SENSITIVITY)
    val fineSensitivity: StateFlow<Float> = _fineSensitivity.asStateFlow()

    private val _deadzone = MutableStateFlow(DEFAULT_DEADZONE)
    val deadzone: StateFlow<Float> = _deadzone.asStateFlow()

    private val _smoothingEnabled = MutableStateFlow(DEFAULT_SMOOTHING_ENABLED)
    val smoothingEnabled: StateFlow<Boolean> = _smoothingEnabled.asStateFlow()

    private val _smoothingFactor = MutableStateFlow(DEFAULT_SMOOTHING_FACTOR)
    val smoothingFactor: StateFlow<Float> = _smoothingFactor.asStateFlow()

    private val _longPressMs = MutableStateFlow(DEFAULT_LONG_PRESS_MS)
    val longPressMs: StateFlow<Long> = _longPressMs.asStateFlow()

    private val _touchMethod = MutableStateFlow(TouchInputMethod.SEND_EVENT)
    val touchMethod: StateFlow<TouchInputMethod> = _touchMethod.asStateFlow()

    private val _cursorRadius = MutableStateFlow(DEFAULT_CURSOR_RADIUS)
    val cursorRadius: StateFlow<Float> = _cursorRadius.asStateFlow()

    private val _cursorColor = MutableStateFlow(DEFAULT_CURSOR_COLOR)
    val cursorColor: StateFlow<Int> = _cursorColor.asStateFlow()

    private val _hapticEnabled = MutableStateFlow(true)
    val hapticEnabled: StateFlow<Boolean> = _hapticEnabled.asStateFlow()

    private val _edgeScrollEnabled = MutableStateFlow(DEFAULT_EDGE_SCROLL_ENABLED)
    val edgeScrollEnabled: StateFlow<Boolean> = _edgeScrollEnabled.asStateFlow()

    private val _edgeScrollDelayMs = MutableStateFlow(DEFAULT_EDGE_SCROLL_DELAY_MS)
    val edgeScrollDelayMs: StateFlow<Long> = _edgeScrollDelayMs.asStateFlow()

    private val _edgeScrollSpeed = MutableStateFlow(DEFAULT_EDGE_SCROLL_SPEED)
    val edgeScrollSpeed: StateFlow<Float> = _edgeScrollSpeed.asStateFlow()

    private val _edgeScrollMargin = MutableStateFlow(DEFAULT_EDGE_SCROLL_MARGIN)
    val edgeScrollMargin: StateFlow<Float> = _edgeScrollMargin.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            load()
        }
    }

    private fun load() {
        val p = prefs ?: return
        _cursorSpeed.value = p.getFloat(KEY_CURSOR_SPEED, DEFAULT_SPEED)
        _fineSensitivity.value = p.getFloat(KEY_FINE_SENSITIVITY, DEFAULT_SENSITIVITY)
        _deadzone.value = p.getFloat(KEY_DEADZONE, DEFAULT_DEADZONE)
        _smoothingEnabled.value = p.getBoolean(KEY_SMOOTHING_ENABLED, DEFAULT_SMOOTHING_ENABLED)
        _smoothingFactor.value = p.getFloat(KEY_SMOOTHING_FACTOR, DEFAULT_SMOOTHING_FACTOR)
        _longPressMs.value = p.getLong(KEY_LONG_PRESS_MS, DEFAULT_LONG_PRESS_MS)
        val methodStr = p.getString(KEY_TOUCH_METHOD, TouchInputMethod.SEND_EVENT.name)
        _touchMethod.value = try {
            TouchInputMethod.valueOf(methodStr ?: TouchInputMethod.SEND_EVENT.name)
        } catch (_: Exception) {
            TouchInputMethod.SEND_EVENT
        }
        _cursorRadius.value = p.getFloat(KEY_CURSOR_RADIUS, DEFAULT_CURSOR_RADIUS)
        _cursorColor.value = p.getInt(KEY_CURSOR_COLOR, DEFAULT_CURSOR_COLOR)
        _hapticEnabled.value = p.getBoolean(KEY_HAPTIC_ENABLED, true)
        _edgeScrollEnabled.value = p.getBoolean(KEY_EDGE_SCROLL_ENABLED, DEFAULT_EDGE_SCROLL_ENABLED)
        _edgeScrollDelayMs.value = p.getLong(KEY_EDGE_SCROLL_DELAY_MS, DEFAULT_EDGE_SCROLL_DELAY_MS)
        _edgeScrollSpeed.value = p.getFloat(KEY_EDGE_SCROLL_SPEED, DEFAULT_EDGE_SCROLL_SPEED)
        _edgeScrollMargin.value = p.getFloat(KEY_EDGE_SCROLL_MARGIN, DEFAULT_EDGE_SCROLL_MARGIN)
        _isDarkMode.value = p.getBoolean(KEY_DARK_MODE, DEFAULT_DARK_MODE)
    }

    fun setDarkMode(darkMode: Boolean) {
        _isDarkMode.value = darkMode
        prefs?.edit()?.putBoolean(KEY_DARK_MODE, darkMode)?.apply()
    }

    fun toggleDarkMode() {
        setDarkMode(!_isDarkMode.value)
    }

    fun setCursorSpeed(speed: Float) {
        _cursorSpeed.value = speed
        prefs?.edit()?.putFloat(KEY_CURSOR_SPEED, speed)?.apply()
    }

    fun setFineSensitivity(sensitivity: Float) {
        _fineSensitivity.value = sensitivity
        prefs?.edit()?.putFloat(KEY_FINE_SENSITIVITY, sensitivity)?.apply()
    }

    fun setDeadzone(deadzone: Float) {
        _deadzone.value = deadzone
        prefs?.edit()?.putFloat(KEY_DEADZONE, deadzone)?.apply()
    }

    fun setSmoothingEnabled(enabled: Boolean) {
        _smoothingEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SMOOTHING_ENABLED, enabled)?.apply()
    }

    fun setSmoothingFactor(factor: Float) {
        _smoothingFactor.value = factor
        prefs?.edit()?.putFloat(KEY_SMOOTHING_FACTOR, factor)?.apply()
    }

    fun setLongPressMs(ms: Long) {
        _longPressMs.value = ms
        prefs?.edit()?.putLong(KEY_LONG_PRESS_MS, ms)?.apply()
    }

    fun setTouchMethod(method: TouchInputMethod) {
        _touchMethod.value = method
        prefs?.edit()?.putString(KEY_TOUCH_METHOD, method.name)?.apply()
    }

    fun setCursorRadius(radius: Float) {
        _cursorRadius.value = radius
        prefs?.edit()?.putFloat(KEY_CURSOR_RADIUS, radius)?.apply()
    }

    fun setCursorColor(color: Int) {
        _cursorColor.value = color
        prefs?.edit()?.putInt(KEY_CURSOR_COLOR, color)?.apply()
    }

    fun setHapticEnabled(enabled: Boolean) {
        _hapticEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_HAPTIC_ENABLED, enabled)?.apply()
    }

    fun setEdgeScrollEnabled(enabled: Boolean) {
        _edgeScrollEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_EDGE_SCROLL_ENABLED, enabled)?.apply()
        if (!enabled) {
            GlobalCursorState.setEdgeScrollingDirection(EdgeScrollDirection.NONE)
            FalveyoService.instance?.stopEdgeScroll()
        }
    }

    fun setEdgeScrollDelayMs(ms: Long) {
        _edgeScrollDelayMs.value = ms
        prefs?.edit()?.putLong(KEY_EDGE_SCROLL_DELAY_MS, ms)?.apply()
    }

    fun setEdgeScrollSpeed(speed: Float) {
        _edgeScrollSpeed.value = speed
        prefs?.edit()?.putFloat(KEY_EDGE_SCROLL_SPEED, speed)?.apply()
    }

    fun setEdgeScrollMargin(marginPx: Float) {
        _edgeScrollMargin.value = marginPx
        prefs?.edit()?.putFloat(KEY_EDGE_SCROLL_MARGIN, marginPx)?.apply()
    }

    fun resetToDefaults() {
        setCursorSpeed(DEFAULT_SPEED)
        setFineSensitivity(DEFAULT_SENSITIVITY)
        setDeadzone(DEFAULT_DEADZONE)
        setSmoothingEnabled(DEFAULT_SMOOTHING_ENABLED)
        setSmoothingFactor(DEFAULT_SMOOTHING_FACTOR)
        setLongPressMs(DEFAULT_LONG_PRESS_MS)
        setTouchMethod(TouchInputMethod.SEND_EVENT)
        setCursorRadius(DEFAULT_CURSOR_RADIUS)
        setCursorColor(DEFAULT_CURSOR_COLOR)
        setHapticEnabled(true)
        setEdgeScrollEnabled(DEFAULT_EDGE_SCROLL_ENABLED)
        setEdgeScrollDelayMs(DEFAULT_EDGE_SCROLL_DELAY_MS)
        setEdgeScrollSpeed(DEFAULT_EDGE_SCROLL_SPEED)
        setEdgeScrollMargin(DEFAULT_EDGE_SCROLL_MARGIN)
    }
}
