package com.vocativa.service

import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/**
 * SmoothScrollController
 *
 * - scrollAction: her frame çağrılan (dx, dy) lambda. Örn: recyclerView.scrollBy(dx, dy)
 * - updateFrequencyMs: frame aralığı (genelde 16ms ~ 60fps)
 *
 * Bu sınıf sürekli bir velocity (px/sec) kabul eder ve her frame için
 * scrollAction(dx, dy) çağırır. Friction / damping uygulanır.
 */
class SmoothScrollController(
    private val scrollAction: (dx: Int, dy: Int) -> Unit,
    private val updateFrequencyMs: Long = 16L
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    // px per second
    @Volatile var velocityY: Float = 0f
    @Volatile var velocityX: Float = 0f

    // Basit sürüklenme: her frame velocity'i çarpanla azalt, 1.0 = hiç azaltma
    var dampingPerFrame: Float = 0.98f

    // Küçük hızları sıfıra yuvarla
    var stopThresholdPxPerSec: Float = 5f

    private var lastRunNanos: Long = 0L

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            val dtSec = if (lastRunNanos == 0L) {
                updateFrequencyMs / 1000f
            } else {
                (now - lastRunNanos) / 1_000_000_000f
            }
            lastRunNanos = now

            val dy = (velocityY * dtSec).toInt()
            val dx = (velocityX * dtSec).toInt()

            if (dx != 0 || dy != 0) {
                try {
                    scrollAction(dx, dy)
                } catch (t: Throwable) {
                    stop()
                    return
                }
            }

            // damping uygula
            velocityX *= dampingPerFrame
            velocityY *= dampingPerFrame

            if (abs(velocityX) < stopThresholdPxPerSec) velocityX = 0f
            if (abs(velocityY) < stopThresholdPxPerSec) velocityY = 0f

            handler.postDelayed(this, updateFrequencyMs)
        }
    }

    fun startIfNeeded() {
        if (running) return
        running = true
        lastRunNanos = 0L
        handler.post(loop)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(loop)
        lastRunNanos = 0L
        velocityX = 0f
        velocityY = 0f
    }

    fun isRunning(): Boolean = running
}
