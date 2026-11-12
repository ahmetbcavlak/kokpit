package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Absolute layout in design pixels, straight from the reference HTML (left/top/width/height).
 * One measure pass per child, no nesting cost — cheaper than stacked Linear/RelativeLayouts.
 */
open class MutlakDuzen(context: Context) : ViewGroup(context) {

    enum class Hiza { SOL, ORTA, SAG }

    /** [x] is the left edge (SOL), the centre (ORTA) or the right edge (SAG); all in device px. */
    class Yer(val x: Int, val y: Int, w: Int, h: Int, val hiza: Hiza) : LayoutParams(w, h)

    /** Adds [v] at design coordinates; [w]/[h] null = wrap content. */
    fun ekle(v: View, x: Number, y: Number, w: Number? = null, h: Number? = null, hiza: Hiza = Hiza.SOL): View {
        addView(v, Yer(u(x), u(y), w?.let { u(it) } ?: LayoutParams.WRAP_CONTENT, h?.let { u(it) } ?: LayoutParams.WRAP_CONTENT, hiza))
        return v
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as Yer
            c.measure(spec(lp.width, w), spec(lp.height, h))
        }
        setMeasuredDimension(w, h)
    }

    private fun spec(boyut: Int, ust: Int) =
        if (boyut >= 0) MeasureSpec.makeMeasureSpec(boyut, MeasureSpec.EXACTLY)
        else MeasureSpec.makeMeasureSpec(ust, MeasureSpec.AT_MOST)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as Yer
            val cw = c.measuredWidth
            val left = when (lp.hiza) {
                Hiza.SOL -> lp.x
                Hiza.ORTA -> lp.x - cw / 2
                Hiza.SAG -> lp.x - cw
            }
            c.layout(left, lp.y, left + cw, lp.y + c.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is Yer
    override fun generateDefaultLayoutParams(): LayoutParams = Yer(0, 0, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Hiza.SOL)
    override fun shouldDelayChildPressedState() = false
}
