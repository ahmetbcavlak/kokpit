package dev.kokpit.anaekran.hareket

import android.view.View
import android.view.animation.PathInterpolator
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.ekran.Gecis

/**
 * Screen change: old view alpha 1 → 0, new view alpha 0 → 1 rising a few pixels, [sureMs]
 * (default 220 ms). Alpha and translation only, both run on hardware layers (withLayer():
 * screens are many overlapping views — glass, text — so a layer is both correct and cheap).
 * A view not measured yet (the first screen change, width or height 0) animates without a
 * layer: View.buildLayer on a 0-size view only logs "Incorrectly called buildLayer" and drops it.
 *
 * Contract (ekran.Gecis): at the end the old view is GONE with alpha 1 / translation 0 (ready
 * to be shown again), the new view is VISIBLE with alpha 1, and `bitti` is called exactly once.
 * A new [gec] while one runs first finishes the running one at once (animations cancelled,
 * end state applied, its `bitti` called), then starts — so rapid calls never leave a view
 * half-faded, even if the caller ignores `bitti`. Main thread only.
 */
class YumusakGecis(
    private val sureMs: Long = SURE_MS,
    /** Rise of the new view in device pixels (design 6 px). */
    private val kayma: () -> Float = { uf(KAYMA_PX) },
) : Gecis {

    private class Calisan(val eski: View?, val yeni: View, val bitti: () -> Unit) {
        var cagrildi = false
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
        yeni.alpha = 0f
        yeni.translationY = kayma()
        yeni.visibility = View.VISIBLE

        e?.let {
            it.animate().cancel()
            it.visibility = View.VISIBLE
            it.animate().alpha(0f).setDuration(sureMs).setInterpolator(EGRI).katmanla(it).start()
        }
        yeni.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(sureMs)
            .setInterpolator(EGRI)
            .katmanla(yeni)
            .withEndAction { if (calisan === c) tamamla(c) }
            .start()
    }

    /** Finishes [c] now: final state, `bitti` once. Safe to call twice. */
    private fun tamamla(c: Calisan) {
        if (calisan === c) calisan = null
        if (!c.cagrildi) izci?.hareketBitti()
        c.yeni.animate().cancel()
        c.eski?.animate()?.cancel()
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

    /** withLayer() only for a measured view (the one condition, see the class notes). */
    private fun android.view.ViewPropertyAnimator.katmanla(v: View) =
        if (v.width > 0 && v.height > 0) withLayer() else this

    companion object {
        const val SURE_MS = 220L
        const val KAYMA_PX = 6

        /** Standard ease (fast out, slow in): the new screen settles softly. */
        val EGRI = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
