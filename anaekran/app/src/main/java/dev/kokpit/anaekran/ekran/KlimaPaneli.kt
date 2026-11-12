package dev.kokpit.anaekran.ekran

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.arayuz.ustDereceli
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.HavaYonu
import dev.kokpit.anaekran.veri.Klima

/**
 * Climate panel placeholder (Y-5 positions; final panel: task 4). Display only — no command
 * ever goes to the car (K-29, K-75). Closes with the X, with a tap outside the card, or with
 * another tap on the bottom bar's climate block.
 */
class KlimaPaneli(context: Context, private val kapat: () -> Unit) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE

    private val baslik = context.metin(Yazi.sansOrta, 34, palet.tx, -0.015f).apply { setText(R.string.ekran_klima) }
    private val alt = context.metin(Yazi.sans, 16, palet.mu)
    private val kapatDugmesi = ImageView(context).apply {
        setImageResource(R.drawable.ic_kapat)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(u(25), u(25), u(25), u(25))
        contentDescription = context.getString(R.string.kapat)
        setOnClickListener { kapat() }
    }

    /** The rows card; taps on it are swallowed (buttons are passive in 0.1), taps outside close. */
    private val kart = MutlakDuzen(context).apply {
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    // The big number swallows taps too: only the background closes the panel.
    private val buyuk = context.metin(Yazi.ince, 216, palet.tx, -0.02f, tabular = true).apply { isClickable = true }
    private val mod = context.metin(Yazi.mono, 13, palet.mu, 0.06f)
    private val satirlar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fan = satir(context, R.string.fan)
    private val yon = satir(context, R.string.hava_yonu)
    private val buz = satir(context, R.string.buz_cozme)
    private val pasif = context.metin(Yazi.mono, 12, palet.hud, 0.04f).apply { setText(R.string.pasif_not) }

    private var sonDurum = AracDurumu()

    init {
        kok.setOnClickListener { kapat() }
        kok.ekle(baslik, 40, 84)
        kok.ekle(alt, 40, 136)
        kok.ekle(kapatDugmesi, 668, 76, 76, 76)
        // Y-5: Barlow 200, 216 px, line-height 0.8 at top 206 → baseline 378.8 → TextView top 163.
        kok.ekle(buyuk, 384, 163, hiza = Hiza.ORTA)
        kok.ekle(mod, 384, 396, hiza = Hiza.ORTA)
        // Card for the rows: design x 24–744, y 440–900 (above the bar's light line).
        kok.ekle(kart, 24, 440, 720, 460)
        kart.ekle(satirlar, 32, 16, 656)
        kart.ekle(pasif, 32, 412)
    }

    private fun satir(c: Context, etiket: Int): TextView {
        val sat = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = u(76)
        }
        val e = c.metin(Yazi.sans, 17, palet.mu).apply { setText(etiket) }
        val d = c.metin(Yazi.sansOrta, 17, palet.tx).apply { gravity = Gravity.END }
        sat.addView(e, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sat.addView(d, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        satirlar.addView(sat)
        return d
    }

    override fun veriDegisti(d: AracDurumu) {
        sonDurum = d
        val k: Klima? = d.klima
        val c = gorunum.context
        alt.text = "${c.getString(R.string.tek_bolge)} · dış ${Bicim.derece(d.disSicaklik)}"
        buyuk.text = ustDereceli(Bicim.klimaDerece(k?.sicaklik), uf(96), uf(34))
        mod.text = listOfNotNull(
            k?.oto?.let { if (it) "oto" else "elle" },
            k?.ac?.let { if (it) "a/c açık" else "a/c kapalı" },
        ).joinToString(" · ").ifEmpty { Bicim.YOK }
        fan.text = k?.fan?.let { "$it / 8" } ?: Bicim.YOK
        yon.text = when (k?.havaYonu) {
            HavaYonu.YUZ -> "Yüz"
            HavaYonu.YUZ_AYAK -> "Yüz ve ayak"
            HavaYonu.AYAK -> "Ayak"
            HavaYonu.AYAK_CAM -> "Ayak ve cam"
            HavaYonu.CAM -> "Cam"
            null -> Bicim.YOK
        }
        buz.text = k?.buzCozme?.let { if (it) c.getString(R.string.acik) else c.getString(R.string.kapali) } ?: Bicim.YOK
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        baslik.setTextColor(p.tx)
        alt.setTextColor(p.mu)
        kapatDugmesi.imageTintList = android.content.res.ColorStateList.valueOf(p.tx)
        // Flat translucent fill + hairline: the "glass" look without live blur (rules: no blur on AC8227L).
        kart.background = GradientDrawable().apply {
            cornerRadius = uf(24)
            setColor(p.glass)
            setStroke(u(1).coerceAtLeast(1), p.gline)
        }
        buyuk.setTextColor(p.tx)
        mod.setTextColor(p.mu)
        pasif.setTextColor(p.hud)
        for (i in 0 until satirlar.childCount) {
            val s = satirlar.getChildAt(i) as LinearLayout
            (s.getChildAt(0) as TextView).setTextColor(p.mu)
            (s.getChildAt(1) as TextView).setTextColor(p.tx)
            s.background = if (i > 0) ustCizgi(p) else null
        }
        veriDegisti(sonDurum)
    }

    private fun ustCizgi(p: Palet) = android.graphics.drawable.LayerDrawable(
        arrayOf(GradientDrawable().apply { setColor(p.hair) }),
    ).apply { setLayerHeight(0, u(1).coerceAtLeast(1)) }
}
