package dev.kokpit.anaekran.arayuz

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import dev.kokpit.anaekran.hareket.Zamanlama

/**
 * The CarPlay transition (K-115, design Y-22; named "geçiş", not "kapı", since 0.2.2 R2 K-8: "kapı"
 * is the car's door everywhere else): Kokpit in front, CarPlay behind. On the CarPlay tap the
 * whole of Kokpit ([kok], every root layer) steps back — scale 1 → [OLCEK] about the CarPlay
 * button's centre and a black cover ([ortu]) 0 → [ORTU_ALFA] (brightness 42 %) — in [ACILIS_MS],
 * while ZLINK grows out of the button's rectangle (the system window transition, started by
 * MainActivity with ActivityOptions.makeScaleUpAnimation in the same turn). When Kokpit comes
 * back to the front after such an exit it steps forward again: [OLCEK] → 1 and the cover away
 * in [DONUS_MS]. Nothing else changes: the intro clip is not replayed (it only plays in onCreate,
 * and not even there when the system re-creates the activity on a return, AcilisKarari).
 *
 * One animator drives one value, the darkness `k` (0 = normal, 1 = stepped back): scale and
 * cover cannot drift apart, and a reversal (ZLINK did not come, the user came straight back)
 * always starts from where the picture is. While it runs [kok] is drawn through a hardware layer
 * (its picture is drawn once and only scaled; the cover is a separate flat fill above it, outside
 * the layer, so its alpha does not redraw the layer). [kap], the content view holding both, gets
 * a dark fill only while Kokpit is smaller than the screen (the window background is dropped
 * once the scene is drawn, MainActivity).
 *
 * The cover is one flat colour: [ortu] says it has no overlapping rendering (R2 K-3), so HWUI
 * applies its alpha to the single fill instead of promoting it to an off-screen layer (a 768×1024
 * texture and one more full-screen pass per frame on the AC8227L). Same result: a single draw.
 *
 * States: [Durum.KAPALI] → (tap, ZLINK started) [Durum.ACILIYOR] → (onPause) [Durum.ARKADA] →
 * (onResume) [Durum.DONUYOR] → [Durum.KAPALI]. From ACILIYOR without an onPause within
 * [ZAMAN_ASIMI_MS] (ZLINK never came to the front) it goes back like a return. While ACILIYOR the
 * cover swallows taps, so a second tap (CarPlay, or Harita/Telefon which open ZLINK too) cannot
 * start a second launch; [acilabilir] is the same guard for direct calls.
 *
 * [dondur] is true only while an animation runs (R2 K-1): MainActivity holds the per-frame loops
 * (drive scene, HUD loop) so the layer's picture stays still — a moving scene would redraw the
 * whole layer every frame on the AC8227L. Once the step back has ended it is released again: if
 * ZLINK does not come, the speed and the road run on (smaller, behind the cover) while the
 * timeout waits; when ZLINK did come we are paused by then and the loops stay off anyway.
 */
class CarPlayGecisi(
    private val kap: ViewGroup,
    private val kok: View,
    private val ortu: View,
    private val ana: Handler = Handler(Looper.getMainLooper()),
) {
    enum class Durum { KAPALI, ACILIYOR, ARKADA, DONUYOR }

    var durum = Durum.KAPALI
        private set

    /** Darkness now: 0 = normal, 1 = stepped back (scale [OLCEK], cover [ORTU_ALFA]). */
    var kararma = 0f
        private set

    /** Hold / release the per-frame loops for the animation's span (see the class notes). */
    var dondur: ((Boolean) -> Unit)? = null

    /** Tap → onPause of the last opening, ms, from the tap itself (diagnostics: tunes [ZAMAN_ASIMI_MS] in the car). */
    var sonAyrilmaMs: Long? = null
        private set

    /** Times the transition went back without ZLINK coming (timeout), for diagnostics. */
    var geriAlmaSayisi = 0
        private set

    private var animator: ValueAnimator? = null

    /** The loops are held ([dondur] true was the last call). MainActivity re-holds after onResume's start. */
    var donuk = false
        private set
    private var acilisAni = 0L
    private var oncekiKatman = View.LAYER_TYPE_NONE
    private var katmanAcik = false
    private var cizimBekleyen: ViewTreeObserver.OnPreDrawListener? = null

    private val zamanAsimi = Runnable {
        if (durum == Durum.ACILIYOR) {
            geriAlmaSayisi++
            donusuBaslat()
        }
    }

    private val donusYedek = Runnable { donusuOynat() }

    init {
        ortu.setBackgroundColor(Color.BLACK)
        ortu.alpha = 0f
        ortu.visibility = View.GONE
        ortu.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // One flat fill: no off-screen layer for its alpha (class notes).
        ortu.forceHasOverlappingRendering(false)
    }

    /** An animation runs now (tests: the return starts with the first drawn frame). */
    val oynuyor: Boolean get() = animator != null

    /** A new opening may start (not while one is already opening). */
    val acilabilir: Boolean get() = durum != Durum.ACILIYOR

    /**
     * ZLINK was started (MainActivity, same turn as the tap): Kokpit steps back about
     * ([pivotX], [pivotY]) in [kok]'s own coordinates — the CarPlay button's centre. [dokunusAni]:
     * uptimeMillis when the tap was handled, before the start call (R2 K-2: the start is a
     * synchronous binder call; measuring after it would make [sonAyrilmaMs] look shorter).
     */
    fun acildi(pivotX: Float, pivotY: Float, dokunusAni: Long = SystemClock.uptimeMillis()) {
        if (!acilabilir) return
        ana.removeCallbacks(zamanAsimi)
        ana.removeCallbacks(donusYedek)
        cizimBekleyeniKaldir()
        kok.pivotX = pivotX
        kok.pivotY = pivotY
        durum = Durum.ACILIYOR
        acilisAni = dokunusAni
        ortu.isClickable = true // swallows taps until we are paused (or the transition goes back)
        oynat(1f, ACILIS_MS)
        ana.postDelayed(zamanAsimi, ZAMAN_ASIMI_MS)
    }

    /**
     * ZLINK could not be started (missing, refused): nothing stays stepped back. The tap starts
     * nothing when the launch fails before any frame, so from rest this is a no-op; from a
     * half-way state (a return still running) it finishes the return.
     */
    fun geriAl() {
        ana.removeCallbacks(zamanAsimi)
        if (durum == Durum.DONUYOR) return // a return is already on its way
        if (kararma == 0f && animator == null) {
            durum = Durum.KAPALI
            bitir()
            return
        }
        donusuBaslat()
    }

    /** onPause: an opening transition means another app is coming to the front. */
    fun duraklatildi() {
        if (durum != Durum.ACILIYOR) return
        ana.removeCallbacks(zamanAsimi)
        sonAyrilmaMs = SystemClock.uptimeMillis() - acilisAni
        durum = Durum.ARKADA
        // The step-back animation keeps running while our window is still on screen under ZLINK's.
    }

    /**
     * onResume: if we left through this transition, step forward again. The animation starts with the
     * first frame drawn after the resume (the window is shown again then; started at onResume
     * itself, its first frames would run while the window is still hidden), at the latest after
     * [DONUS_YEDEK_MS].
     */
    fun devamEdildi() {
        if (durum != Durum.ARKADA && durum != Durum.ACILIYOR) return
        donusuBaslat()
    }

    /** onDestroy. */
    fun birak() {
        ana.removeCallbacks(zamanAsimi)
        ana.removeCallbacks(donusYedek)
        cizimBekleyeniKaldir()
        durdur()
        dondur = null
    }

    // --- inside ---

    private fun donusuBaslat() {
        ana.removeCallbacks(zamanAsimi)
        durum = Durum.DONUYOR
        ortu.isClickable = false
        durdur()
        dondurmeyiAyarla(true)
        cizimBekleyeniKaldir()
        val l = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                cizimBekleyeniKaldir()
                ana.removeCallbacks(donusYedek)
                // Next turn: never start an animator from inside a traversal.
                ana.post(donusYedek)
                return true
            }
        }
        cizimBekleyen = l
        kok.viewTreeObserver.addOnPreDrawListener(l)
        kok.invalidate()
        ana.postDelayed(donusYedek, DONUS_YEDEK_MS)
    }

    private fun donusuOynat() {
        ana.removeCallbacks(donusYedek)
        cizimBekleyeniKaldir()
        if (durum != Durum.DONUYOR || animator != null) return
        oynat(0f, DONUS_MS)
    }

    private fun cizimBekleyeniKaldir() {
        val l = cizimBekleyen ?: return
        cizimBekleyen = null
        val vto = kok.viewTreeObserver
        if (vto.isAlive) vto.removeOnPreDrawListener(l)
    }

    /** Stops the running animation where it is; its layer stays for the next one. */
    private fun durdur() {
        val a = animator ?: return
        animator = null
        a.cancel()
    }

    private fun oynat(hedef: Float, sureMs: Long) {
        durdur()
        dondurmeyiAyarla(true)
        kap.setBackgroundColor(CEVRE)
        ortu.visibility = View.VISIBLE
        katmanAc()
        val a = ValueAnimator.ofFloat(kararma, hedef).setDuration(sureMs)
        a.interpolator = Zamanlama.ARAYUZ_EGRISI
        a.addUpdateListener { uygula(it.animatedValue as Float) }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Stopped by durdur() (a reversal): the next animation owns the state and the layer.
                if (animator !== animation) return
                animator = null
                katmanKapat()
                uygula(hedef)
                if (hedef == 0f) {
                    durum = Durum.KAPALI
                    bitir()
                } else {
                    // Stepped back (ACILIYOR / ARKADA): stays so until onResume or the timeout, the
                    // loops released (paused: they stay off; ZLINK did not come: they run on).
                    dondurmeyiAyarla(false)
                }
            }
        })
        animator = a
        a.start()
    }

    private fun uygula(k: Float) {
        kararma = k
        val s = 1f - (1f - OLCEK) * k
        kok.scaleX = s
        kok.scaleY = s
        ortu.alpha = ORTU_ALFA * k
    }

    /** Back at rest: no cover, no fill, no layer, loops running again. */
    private fun bitir() {
        uygula(0f)
        ortu.isClickable = false
        ortu.visibility = View.GONE
        kap.background = null
        katmanKapat()
        dondurmeyiAyarla(false)
    }

    private fun dondurmeyiAyarla(d: Boolean) {
        if (donuk == d) return
        donuk = d
        dondur?.invoke(d)
    }

    /** Hardware layer for the animation's span, measured views only (as Zamanlama's). */
    private fun katmanAc() {
        if (katmanAcik || kok.width <= 0 || kok.height <= 0) return
        oncekiKatman = kok.layerType
        if (oncekiKatman == View.LAYER_TYPE_HARDWARE) return
        katmanAcik = true
        kok.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private fun katmanKapat() {
        if (!katmanAcik) return
        katmanAcik = false
        kok.setLayerType(oncekiKatman, null)
    }

    companion object {
        /** Y-22: Kokpit's scale behind the open CarPlay. */
        const val OLCEK = 0.94f

        /** Y-22: brightness 42 % → a black cover of 58 %. */
        const val ORTU_ALFA = 0.58f

        /** Y-22 opening: 0–320 ms. */
        const val ACILIS_MS = 320L

        /** Y-22 return: 360 ms. */
        const val DONUS_MS = 360L

        /**
         * No onPause this long after the start: ZLINK did not come, the transition goes back. The
         * system pauses us as soon as it accepts the start (before ZLINK's process even runs),
         * normally well under 0.5 s; the diagnostics line shows the measured value.
         */
        const val ZAMAN_ASIMI_MS = 2000L

        /** Return without a drawn frame after onResume: start anyway. */
        const val DONUS_YEDEK_MS = 250L

        /** Fill around the smaller Kokpit (Y-22 frame #05070B), under the cover. */
        val CEVRE: Int = Color.rgb(0x05, 0x07, 0x0B)
    }
}
