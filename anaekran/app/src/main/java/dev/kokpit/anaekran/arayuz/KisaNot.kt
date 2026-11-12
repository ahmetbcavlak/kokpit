package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi

/**
 * A short, calm line above the bottom bar ("ZLink bulunamadı"…). Not a Toast: no extra window,
 * so immersive mode and focus stay untouched. Shows for a few seconds, no animation in 0.1.
 */
class KisaNot(context: Context) : TextView(context) {

    private val gizle = Runnable { visibility = GONE }

    init {
        typeface = Yazi.sans
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, uf(15))
        includeFontPadding = false
        gravity = Gravity.CENTER
        setPadding(u(20), u(14), u(20), u(14))
        maxWidth = u(640)
        visibility = GONE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        temaUygula(Palet.GECE)
    }

    fun goster(metin: CharSequence) {
        text = metin
        visibility = VISIBLE
        removeCallbacks(gizle)
        postDelayed(gizle, SURE_MS)
    }

    fun temaUygula(p: Palet) {
        setTextColor(p.tx)
        background = GradientDrawable().apply {
            cornerRadius = uf(18)
            setColor(p.glass)
            setStroke(u(1).coerceAtLeast(1), p.gline)
        }
    }

    companion object {
        const val SURE_MS = 3500L
    }
}
