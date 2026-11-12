package dev.kokpit.anaekran.hareket

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** Shared test set-up for the motion package. */
object HareketTestYardim {
    /**
     * By default Robolectric's Choreographer advances the clock by itself while an animation
     * runs (a 220 ms ViewPropertyAnimator finished inside one 16 ms idle step), so timing
     * assertions are meaningless. Paused, it delivers a frame every 16 ms of looper time only.
     */
    fun gercekSure() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    /**
     * Advances the main looper [ms] in frame-sized steps. One long idleFor() with a paused
     * Choreographer delivered too few animation frames (a fade did not finish in time).
     */
    fun bekle(ms: Long) {
        var kalan = ms
        while (kalan > 0) {
            val adim = minOf(16L, kalan)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(adim))
            kalan -= adim
        }
    }
}
