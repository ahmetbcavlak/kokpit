package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.widget.TextView
import dev.kokpit.anaekran.veri.Sicaklik
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** TextView with a design-px size, no extra font padding (positions match the reference HTML). */
fun Context.metin(
    yazi: Typeface,
    boyut: Number,
    renk: Int,
    harfAraligi: Float = 0f,
    tabular: Boolean = false,
): TextView = TextView(this).apply {
    typeface = yazi
    setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(boyut))
    setTextColor(renk)
    includeFontPadding = false
    letterSpacing = harfAraligi
    if (tabular) fontFeatureSettings = "'tnum'"
}

/** Formatting for user-visible numbers (Turkish: decimal comma). */
object Bicim {
    private val TR = Locale("tr", "TR")
    const val YOK = "—"

    /** 21.5 → "21,5", 14.0 → "14", -3.0 → "−3". */
    fun sayi(c: Double): String {
        val yuvarlak = (c * 10).roundToLong() / 10.0
        val metin = if (abs(yuvarlak - yuvarlak.roundToLong()) < 0.05) yuvarlak.roundToLong().toString()
        else String.format(TR, "%.1f", yuvarlak)
        return metin.replace('-', '−')
    }

    /** Set temperature without the degree sign: "21,5", "LOW", "HI" or null. */
    fun klima(s: Sicaklik?): String? = when (s) {
        is Sicaklik.Derece -> sayi(s.c)
        Sicaklik.Low -> "LOW"
        Sicaklik.Hi -> "HI"
        null -> null
    }

    /** "21,5°" for numbers, "LOW"/"HI" as they are, "—" when unknown. */
    fun klimaDerece(s: Sicaklik?): String = when (s) {
        is Sicaklik.Derece -> sayi(s.c) + "°"
        null -> YOK
        else -> klima(s)!!
    }

    fun derece(c: Double?): String = c?.let { sayi(it) + "°" } ?: YOK
}

/**
 * Big number with a smaller, top-aligned degree sign (the reference's
 * `<span style="font-size:26px;vertical-align:top">°</span>`). The sign's ascent is aligned
 * with the number's; [asagiPx] moves it down from there. CSS vertical-align:top with
 * line-height L puts it (big − small) × (1.2 − L) / 2 lower for Barlow (ascent 1.0, descent 0.2),
 * plus any `top:` offset — about 0 for the bottom bar (L = 1), 34 px for the Y-5 number (L = 0.8).
 */
fun ustDereceli(sayi: String, kucukPx: Float, asagiPx: Float = 0f): CharSequence {
    if (!sayi.endsWith("°")) return sayi
    val s = SpannableString(sayi)
    s.setSpan(UstHizali(kucukPx, asagiPx), sayi.length - 1, sayi.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return s
}

private class UstHizali(private val boyutPx: Float, private val asagiPx: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) = uygula(tp)
    override fun updateDrawState(tp: TextPaint) = uygula(tp)
    private fun uygula(tp: TextPaint) {
        val buyukUst = tp.ascent()
        tp.textSize = boyutPx
        tp.baselineShift += (buyukUst - tp.ascent() + asagiPx).toInt()
    }
}
