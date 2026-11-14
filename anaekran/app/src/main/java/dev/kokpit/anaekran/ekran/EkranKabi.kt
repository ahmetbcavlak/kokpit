package dev.kokpit.anaekran.ekran

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * The screen container (root layer 3). Touches go to the screen in front ([gecerli]) and to no
 * other child (finding E1, İ-M2): during the 1.1 s screen change the old screen is still
 * VISIBLE while it fades out, and a plain FrameLayout handed it every tap the new screen did not
 * take — a tap 0.9 s after closing the apps panel, on the spot of an app cell, opened that app.
 *
 * Rules (decided per gesture, on ACTION_DOWN):
 *  - only [gecerli] can get the gesture; a screen fading out never does;
 *  - while a screen change runs ([gecisSuruyor], YumusakGecis.suruyor), [gecerli] gets it only
 *    once it is at least half visible ([DOKUNMA_ALFA]): nothing reacts to a tap on a control the
 *    user cannot see yet (the new screen is at alpha 0 for its first 0.2 s);
 *  - if the screen in front changes in the middle of a gesture, the gesture's screen gets
 *    ACTION_CANCEL and the rest of the gesture is dropped.
 * Dropped touches return false, so they fall through to the layers below (video, scene), which
 * take none. The bottom bar, the warning layer and the diagnostics are other root layers and
 * are not affected.
 *
 * The event is handed to the child in its own coordinates (left/top and its transform, e.g. the
 * 6 px rise of the entrance), like ViewGroup does for a hit child.
 */
class EkranKabi(context: Context) : FrameLayout(context) {

    /** The screen in front (EkranYoneticisi sets it with every screen change). */
    var gecerli: View? = null

    /** True while a screen change runs (MainActivity: YumusakGecis.suruyor). */
    var gecisSuruyor: () -> Boolean = { false }

    /** The screen that owns the current gesture; null = the gesture is dropped. */
    private var hedef: View? = null
    private val ters = Matrix()

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val g = gecerli
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hedef = g?.takeIf { alabilir(it) }
            }
            else -> {
                val h = hedef
                if (h != null && h !== g) {
                    // The screen changed under the finger: end its gesture, drop the rest.
                    hedef = null
                    val iptal = MotionEvent.obtain(ev)
                    iptal.action = MotionEvent.ACTION_CANCEL
                    ilet(h, iptal)
                    iptal.recycle()
                    return false
                }
            }
        }
        val h = hedef ?: return false
        val sonuc = ilet(h, ev)
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) hedef = null
        return sonuc
    }

    /** Whether [v] may start a gesture now. */
    private fun alabilir(v: View): Boolean {
        if (v.parent !== this || v.visibility != VISIBLE) return false
        return !gecisSuruyor() || v.alpha >= DOKUNMA_ALFA
    }

    private fun ilet(v: View, ev: MotionEvent): Boolean {
        val e = MotionEvent.obtain(ev)
        e.offsetLocation((scrollX - v.left).toFloat(), (scrollY - v.top).toFloat())
        val m = v.matrix
        if (!m.isIdentity && m.invert(ters)) e.transform(ters)
        val sonuc = v.dispatchTouchEvent(e)
        e.recycle()
        return sonuc
    }

    companion object {
        /** A screen that is coming in takes touches from this alpha on. */
        const val DOKUNMA_ALFA = 0.5f
    }
}
