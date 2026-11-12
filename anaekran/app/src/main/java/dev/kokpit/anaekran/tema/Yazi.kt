package dev.kokpit.anaekran.tema

import android.content.res.Resources
import android.graphics.Typeface
import dev.kokpit.anaekran.R

/**
 * Embedded Google Fonts (OFL, subset to Latin + Latin-1 + Latin Extended-A).
 *
 * Each weight is loaded from its own TTF resource, not picked from the family XML:
 * on API 27 there is no call that asks a family for weight 200 or 500
 * (Typeface.create(family, weight, italic) is API 28), and Typeface.create(family, BOLD)
 * would fake-bold the 500 file. Typefaces from resources keep the system fallback
 * chain, so app names in other scripts still render.
 */
object Yazi {
    /** Geist 400 — interface text. */
    lateinit var sans: Typeface; private set
    /** Geist 500 — emphasised interface text. */
    lateinit var sansOrta: Typeface; private set
    /** Geist Mono 400 — HUD labels. */
    lateinit var mono: Typeface; private set
    /** Instrument Serif Italic 400 — personal moments, greeting. */
    lateinit var serif: Typeface; private set
    /** Barlow Condensed 200 — the biggest numbers. */
    lateinit var ince: Typeface; private set
    /** Barlow Condensed 300 — big numbers (bottom bar). */
    lateinit var hafif: Typeface; private set

    @Volatile private var hazir = false

    fun yukle(res: Resources) {
        if (hazir) return
        sans = res.getFont(R.font.geist_regular)
        sansOrta = res.getFont(R.font.geist_medium)
        mono = res.getFont(R.font.geist_mono_regular)
        serif = res.getFont(R.font.instrument_serif_italic)
        ince = res.getFont(R.font.barlow_condensed_extralight)
        hafif = res.getFont(R.font.barlow_condensed_light)
        hazir = true
    }
}
