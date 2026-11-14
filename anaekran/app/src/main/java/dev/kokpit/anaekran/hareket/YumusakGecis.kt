package dev.kokpit.anaekran.hareket

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.view.View
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.ekran.Gecis

/**
 * Screen change with the calm interface timings of K-93 ([Zamanlama]):
 *  - old view: alpha 1 → 0 over 0.8 s, at once;
 *  - new view: after 0.2 s, alpha 0 → 1 over 0.8 s and a rise of 6 design px over 0.9 s;
 *  - curve `cubic-bezier(.4,0,.2,1)` (the 0.2 demo's); total 1.1 s.
 * Alpha and translation only, each view on a hardware layer while it animates (screens are many
 * overlapping views — glass, text — so a layer is both correct and cheap). A view not measured
 * yet (first screen change) animates without a layer (see [Zamanlama.giris]).
 *
 * Contract (ekran.Gecis): at the end the old view is GONE with alpha 1 / translation 0 (ready
 * to be shown again), the new view is VISIBLE with alpha 1, and `bitti` is called exactly once.
 * A new [gec] while one runs first finishes the running one at once (animations ended, end
 * state applied, its `bitti` called), then starts — so rapid calls never leave a view
 * half-faded, even if the caller ignores `bitti`. Main thread only.
 */
class YumusakGecis(
    /** Rise of the new view in device pixels (design [Zamanlama.ARAYUZ_KAYMA_PX] px). */
    private val kayma: () -> Float = { uf(Zamanlama.ARAYUZ_KAYMA_PX) },
) : Gecis {

    private class Calisan(val eski: View?, val yeni: View, val bitti: () -> Unit) {
        var cagrildi = false
        var anim: Animator? = null
    }

    private var calisan: Calisan? = null

    /** Told when a transition starts/ends (frame-rate counter). */
    var izci: HareketIzci? = null

    /** True while a transition runs (tests, debugging). */
    val suruyor: Boolean get() = calisan != null

    override fun gec(eski: View?, yeni: View, bitti: () -> Unit) {
        calisan?.let { tamamla(it) }
        val e = eski?.takeIf { it !== yeni }
        val c = Calisan(e, yeni, bitti)
        calisan = c
        izci?.hareketBasladi()

        yeni.animate().cancel()
        e?.animate()?.cancel()
        e?.visibility = View.VISIBLE
        val giris = Zamanlama.giris(yeni, 0, kayma())
        val set = AnimatorSet()
        if (e != null) set.playTogether(Zamanlama.cikis(e), giris) else set.play(giris)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (calisan === c) tamamla(c)
            }
        })
        c.anim = set
        set.start()
    }

    /** Finishes [c] now: final state, `bitti` once. Safe to call twice. */
    private fun tamamla(c: Calisan) {
        if (calisan === c) calisan = null
        if (!c.cagrildi) izci?.hareketBitti()
        // end() runs the listeners (layers off); calisan is already moved on, so ours is a no-op.
        val a = c.anim
        c.anim = null
        Zamanlama.bitir(a)
        c.eski?.let {
            it.visibility = View.GONE
            it.alpha = 1f
            it.translationY = 0f
        }
        c.yeni.alpha = 1f
        c.yeni.translationY = 0f
        c.yeni.visibility = View.VISIBLE
        if (!c.cagrildi) {
            c.cagrildi = true
            c.bitti()
        }
    }

    companion object {
        /** Whole transition length (delay + the longer of alpha and rise): 1.1 s. */
        val SURE_MS: Long = Zamanlama.girisSuresi(0)
    }
}
