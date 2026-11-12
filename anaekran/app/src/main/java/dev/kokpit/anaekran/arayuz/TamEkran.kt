@file:Suppress("DEPRECATION") // systemUiVisibility is the only immersive API on API 27 (WindowInsetsController is API 30).

package dev.kokpit.anaekran.arayuz

import android.app.Activity
import android.view.View

/**
 * Immersive sticky, research decision 1: all six flags together, applied in onCreate,
 * on every onWindowFocusChanged(true) and from the visibility listener — only when the
 * value differs, so the three paths cannot loop. The app itself has no edge gesture (K-66).
 */
object TamEkran {

    const val BAYRAKLAR: Int =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    /** Delay before re-hiding after the system showed the bars (lets a transient bar finish). */
    private const val YENIDEN_MS = 1500L

    fun kur(activity: Activity) {
        val decor = activity.window.decorView
        val yeniden = Runnable { uygula(activity) }
        decor.setOnSystemUiVisibilityChangeListener { vis ->
            if (vis and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decor.removeCallbacks(yeniden)
                decor.postDelayed(yeniden, YENIDEN_MS)
            }
        }
        uygula(activity)
    }

    fun uygula(activity: Activity) {
        val decor = activity.window.decorView
        if (decor.systemUiVisibility != BAYRAKLAR) decor.systemUiVisibility = BAYRAKLAR
    }
}
