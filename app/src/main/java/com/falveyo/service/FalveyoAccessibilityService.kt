package com.falveyo.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

private const val TAG = "FalveyoAccessibility"

/**
 * FalveyoAccessibilityService
 * Android'de TYPE_ACCESSIBILITY_OVERLAY pencere türü ile imlecin
 * Bildirim Paneli (Notification Shade), Hızlı Ayarlar, Kilit Ekranı ve tüm sistem pencerelerinin
 * en üst katmanında (en yüksek z-order) kesintisiz görünmesini sağlar.
 */
class FalveyoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FalveyoAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private var cursorOverlay: CursorOverlayService? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "FalveyoAccessibilityService bağlandı - TYPE_ACCESSIBILITY_OVERLAY aktif ediliyor")
        GlobalCursorState.setAccessibilityActive(true)

        try {
            cursorOverlay = CursorOverlayService(this, isAccessibility = true)
            cursorOverlay?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Accessibility overlay başlatma hatası: ${e.message}", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // İhtiyaç halinde erişilebilirlik olayları işlenebilir
    }

    override fun onInterrupt() {
        Log.d(TAG, "FalveyoAccessibilityService kesintiye uğradı")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FalveyoAccessibilityService durduruldu")
        GlobalCursorState.setAccessibilityActive(false)
        cursorOverlay?.stop()
        cursorOverlay = null
        if (instance == this) {
            instance = null
        }
    }
}
