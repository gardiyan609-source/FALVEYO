package com.vocativa.service

import kotlin.math.abs

/**
 * Kenar/joystick pozisyonunu px/s cinsinden hız değerine çeviren yardımcı fonksiyonlar.
 * Bu sınıf UI tarafında direkt OnTouchListener yerine herhangi bir pozisyon kaynağıyla da kullanılabilir:
 * örn. GlobalCursorState.position akışından gelen koordinatları kullanmak için uygun.
 */
object JoystickEdgeMath {

    // normalized: -1..1, negatif = sol/üst, pozitif = sağ/aşağı
    // deadZonePx: merkezde yok sayılacak px
    fun normalizedEdgeFraction(posPx: Float, totalLengthPx: Int, deadZonePx: Int = 10): Float {
        val center = totalLengthPx / 2f
        val distFromCenter = posPx - center
        val half = totalLengthPx / 2f
        if (half <= 0f) return 0f
        val norm = (distFromCenter / half).coerceIn(-1f, 1f)
        val absNorm = abs(norm)
        // Deadzone: merkezde küçük titreşimleri yok say
        val deadNorm = deadZonePx / half
        if (absNorm <= deadNorm) return 0f
        // 0..1 arası kenara yakınlık (işaret korunur)
        val scaled = (absNorm - deadNorm) / (1f - deadNorm)
        return if (norm >= 0f) scaled else -scaled
    }

    fun sign(v: Float): Float = if (v >= 0f) 1f else -1f
}
