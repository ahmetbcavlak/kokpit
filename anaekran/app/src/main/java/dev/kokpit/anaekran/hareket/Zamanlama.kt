package dev.kokpit.anaekran.hareket

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.PathInterpolator
import dev.kokpit.anaekran.arayuz.uf

/**
 * The calm timings of design pack 0.2 (K-93), in one place.
 *
 * Scenes (clips and scene pictures):
 *  - a clip ends, its last frame is held [KLIP_SONU_TUT_MS], then the scene fades in over
 *    [SAHNE_GECIS_MS] on [SAHNE_EGRISI] (intro and park ↔ drive alike; replaces 0.1's "1.6 s");
 *    a plain scene cross-fade (day, no clip) takes the same [SAHNE_GECIS_MS];
 *  - a tap cuts with [KESME_MS] on [KESME_EGRISI].
 *
 * Interface layers (texts, cards, a whole screen):
 *  - enter: alpha 0 → 1 over [ARAYUZ_SAYDAMLIK_MS] and a rise of [ARAYUZ_KAYMA_PX] design px
 *    over [ARAYUZ_KONUM_MS], both after [gecikme] (0.20 s for the first layer, 0.25 s for the
 *    ones after it), on [ARAYUZ_EGRISI];
 *  - leave: alpha 1 → 0 over [ARAYUZ_SAYDAMLIK_MS], no delay, no movement.
 * The old 0.45 s / 0.6 s were too fast (design pack 0.2 §1).
 *
 * Curves. The 0.2 demo (referans/Y-3h-SurusHareketi.dc.html) uses `cubic-bezier(.4,0,.2,1)` for
 * every eased transition (camera, speed arc) — [ARAYUZ_EGRISI]. The clips' camera moves on a
 * smootherstep, so the scene fade that follows a clip uses the same curve ([SAHNE_EGRISI]): the
 * motion dies out and the picture change starts and ends without a kink. The demo gives no
 * distance for the interface rise; [ARAYUZ_KAYMA_PX] keeps 0.1's 6 px (see the report).
 *
 * How a screen uses this (step 3 wiring; nothing calls it yet besides [YumusakGecis]):
 * ```
 * // the screen's own layers, e.g. in EkranGorunumu.gosterildi():
 * Zamanlama.giris(baslik, sira = 0).start()   // 0.20 s
 * Zamanlama.giris(kart, sira = 1).start()     // 0.25 s
 * ```
 * [giris] sets the start state at once (alpha 0, translated) and leaves the end state (alpha 1,
 * translation 0) when it ends or is cancelled through [bitir]. A whole screen changes through
 * [YumusakGecis] (EkranYoneticisi.gecis), which already uses these values: animate only the
 * layers that should move on their own, not a screen that is itself fading in.
 */
object Zamanlama {
    /** Hold of the clip's last frame before the scene fade (K-93). */
    const val KLIP_SONU_TUT_MS = 300L

    /** Scene fade after a clip, and the plain scene cross-fade (K-93). */
    const val SAHNE_GECIS_MS = 600L

    /** Tap cut: fast but not a jump. */
    const val KESME_MS = 250L

    /** Interface layer alpha (K-93). */
    const val ARAYUZ_SAYDAMLIK_MS = 800L

    /** Interface layer rise (K-93). */
    const val ARAYUZ_KONUM_MS = 900L

    /** Delay of the first interface layer (K-93: 0.2–0.25 s). */
    const val ARAYUZ_GECIKME_MS = 200L

    /** Delay of every later layer. */
    const val ARAYUZ_GECIKME_SON_MS = 250L

    /** Rise distance, design px (not in the demo; 0.1's value). */
    const val ARAYUZ_KAYMA_PX = 6

    /** Demo `cubic-bezier(.4,0,.2,1)`. */
    val ARAYUZ_EGRISI: TimeInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** Smootherstep (6t⁵ − 15t⁴ + 10t³): the clips' camera curve. */
    val SAHNE_EGRISI: TimeInterpolator = TimeInterpolator { t -> t * t * t * (t * (t * 6f - 15f) + 10f) }

    /** Tap: starts at once, lands soft. */
    val KESME_EGRISI: TimeInterpolator = PathInterpolator(0f, 0f, 0.2f, 1f)

    /** Hold from the clip start before the fade may begin: clip length + [KLIP_SONU_TUT_MS]. */
    fun klipTutMs(klip: Klip): Long = klip.sureMs + KLIP_SONU_TUT_MS

    /** Delay of the [sira]-th interface layer (0 = first). */
    fun gecikme(sira: Int): Long = if (sira <= 0) ARAYUZ_GECIKME_MS else ARAYUZ_GECIKME_SON_MS

    /** Whole interface entrance length for layer [sira]: delay + the longer of alpha and rise. */
    fun girisSuresi(sira: Int = 0): Long = gecikme(sira) + maxOf(ARAYUZ_SAYDAMLIK_MS, ARAYUZ_KONUM_MS)

    /**
     * Entrance of one interface layer: alpha 0 → 1 ([ARAYUZ_SAYDAMLIK_MS]) and translationY
     * [kayma] → 0 ([ARAYUZ_KONUM_MS]) after [gecikme] ([sira]). The start state is applied at
     * once; the caller starts the returned animator. While it runs, a measured view is drawn
     * through a hardware layer (alpha and translation on one texture; removed at the end).
     */
    fun giris(v: View, sira: Int = 0, kayma: Float = uf(ARAYUZ_KAYMA_PX)): Animator {
        v.alpha = 0f
        v.translationY = kayma
        v.visibility = View.VISIBLE
        val a = ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).setDuration(ARAYUZ_SAYDAMLIK_MS)
        val t = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, kayma, 0f).setDuration(ARAYUZ_KONUM_MS)
        return AnimatorSet().apply {
            playTogether(a, t)
            interpolator = ARAYUZ_EGRISI
            startDelay = gecikme(sira)
            katmanli(v)
        }
    }

    /** Exit of one interface layer: alpha → 0 over [ARAYUZ_SAYDAMLIK_MS], no delay. */
    fun cikis(v: View): Animator =
        ObjectAnimator.ofFloat(v, View.ALPHA, v.alpha, 0f).setDuration(ARAYUZ_SAYDAMLIK_MS).apply {
            interpolator = ARAYUZ_EGRISI
            katmanli(v)
        }

    /** Ends [a] at once on its end values (cancel() would leave them half-way). */
    fun bitir(a: Animator?) {
        if (a != null && (a.isStarted || a.isRunning)) a.end()
    }

    /**
     * Hardware layer for the animation's span, for a measured view only. The layer is built on
     * the next draw (no synchronous buildLayer on the UI thread, İ-F2 K-3). The previous layer
     * type comes back at the end or on cancel.
     */
    private fun Animator.katmanli(v: View) {
        addListener(object : AnimatorListenerAdapter() {
            private var onceki = View.LAYER_TYPE_NONE
            private var acik = false

            override fun onAnimationStart(animation: Animator) {
                if (v.width <= 0 || v.height <= 0 || v.layerType == View.LAYER_TYPE_HARDWARE) return
                onceki = v.layerType
                acik = true
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!acik) return
                acik = false
                v.setLayerType(onceki, null)
            }
        })
    }
}
