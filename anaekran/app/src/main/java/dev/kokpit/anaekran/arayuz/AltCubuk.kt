package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu

/**
 * Bottom bar, design y 932–1024 (Y-2 / Y-7): climate set temperature on the left, five icons
 * in the middle, outside temperature on the right. Every target is at least 84×92 design px.
 * Only taps; what a tap does is decided by [Olaylar] (MainActivity).
 */
class AltCubuk(context: Context, private val olaylar: Olaylar) : MutlakDuzen(context) {

    enum class Simge(val ikon: Int, val ad: Int) {
        CARPLAY(R.drawable.ic_carplay, R.string.carplay),
        MUZIK(R.drawable.ic_muzik, R.string.muzik),
        HARITA(R.drawable.ic_harita, R.string.harita),
        TELEFON(R.drawable.ic_telefon, R.string.telefon),
        UYGULAMALAR(R.drawable.ic_uygulamalar, R.string.uygulamalar),
    }

    interface Olaylar {
        fun klimaDokunuldu()
        fun simgeDokunuldu(s: Simge)
    }

    private var palet = Palet.GECE
    private var etkin: Simge? = null
    private var sonDurum = AracDurumu()

    private val klimaDeger = context.metin(Yazi.hafif, 40, palet.tx, tabular = true)
    private val klimaEtiket = context.metin(Yazi.sans, 13, palet.mu).apply { setText(R.string.klima) }
    private val disDeger = context.metin(Yazi.hafif, 40, palet.tx, tabular = true)
    private val disEtiket = context.metin(Yazi.sans, 13, palet.mu).apply { setText(R.string.disarisi) }

    /** Left block: tap opens/closes the climate panel. */
    val klimaAlani: View = blok(klimaDeger, klimaEtiket, sag = false).apply {
        setOnClickListener { olaylar.klimaDokunuldu() }
    }
    private val disAlani: View = blok(disDeger, disEtiket, sag = true).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val simgeler = HashMap<Simge, ImageView>()
    private val noktalar = HashMap<Simge, View>()
    private val dugmeler = HashMap<Simge, View>()

    init {
        ekle(klimaAlani, 0, 0, BLOK_GENISLIK, YUKSEKLIK)
        Simge.values().forEachIndexed { i, s -> ekle(simgeDugmesi(s), SIMGE_BASLA + i * SIMGE_GENISLIK, 0, SIMGE_GENISLIK, YUKSEKLIK) }
        ekle(disAlani, Olcek.TASARIM_GENISLIK - BLOK_GENISLIK, 0, BLOK_GENISLIK, YUKSEKLIK)
        temaUygula(palet)
        guncelle(sonDurum)
    }

    /** The button for [s] (tests tap it). */
    fun dugme(s: Simge): View = dugmeler.getValue(s)

    fun guncelle(d: AracDurumu) {
        sonDurum = d
        val klima = Bicim.klimaDerece(d.klima?.sicaklik)
        klimaDeger.text = ustDereceli(klima, uf(26))
        klimaAlani.contentDescription = "${context.getString(R.string.klima_paneli)}: ${context.getString(R.string.klima)} $klima"
        val dis = Bicim.derece(d.disSicaklik)
        disDeger.text = ustDereceli(dis, uf(26))
        disAlani.contentDescription = "${context.getString(R.string.disarisi)} $dis"
    }

    /** Highlights the icon of the screen in front (dot under it), or none. */
    fun etkinYap(s: Simge?) {
        etkin = s
        renkle()
    }

    fun temaUygula(p: Palet) {
        palet = p
        setBackgroundColor(p.dock)
        klimaDeger.setTextColor(p.tx)
        disDeger.setTextColor(p.tx)
        klimaEtiket.setTextColor(p.mu)
        disEtiket.setTextColor(p.mu)
        val dalga = ColorStateList.valueOf(p.hair)
        klimaAlani.background = RippleDrawable(dalga, null, null)
        disAlani.background = null
        for (d in dugmeler.values) d.background = RippleDrawable(dalga, null, null)
        for (n in noktalar.values) (n.background as GradientDrawable).setColor(p.acc)
        renkle()
    }

    private fun renkle() {
        for ((s, iv) in simgeler) {
            iv.imageTintList = ColorStateList.valueOf(if (s == etkin) palet.tx else palet.mu)
            noktalar.getValue(s).visibility = if (s == etkin) VISIBLE else INVISIBLE
        }
    }

    /**
     * Value + label at the reference's text positions: the 92 px block centres a 40 px line,
     * 4 px gap and a 13 px label → value top 11.5, label top 59.5 (no font padding, so a
     * TextView's top is its CSS line top plus half-leading).
     */
    private fun blok(deger: TextView, etiket: TextView, sag: Boolean) = MutlakDuzen(context).apply {
        val x = if (sag) BLOK_GENISLIK - 32 else 32
        val hiza = if (sag) MutlakDuzen.Hiza.SAG else MutlakDuzen.Hiza.SOL
        ekle(deger, x, 11.5f, hiza = hiza)
        ekle(etiket, x, 59.5f, hiza = hiza)
    }

    private fun simgeDugmesi(s: Simge): View {
        val ikon = ImageView(context).apply { setImageResource(s.ikon) }
        val nokta = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
            visibility = INVISIBLE
        }
        simgeler[s] = ikon
        noktalar[s] = nokta
        return FrameLayout(context).apply {
            addView(ikon, FrameLayout.LayoutParams(u(SIMGE_BOYUT), u(SIMGE_BOYUT), Gravity.CENTER))
            addView(nokta, FrameLayout.LayoutParams(u(4), u(4), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = u(16) })
            contentDescription = context.getString(s.ad)
            isClickable = true
            setOnClickListener { olaylar.simgeDokunuldu(s) }
            dugmeler[s] = this
        }
    }

    companion object {
        /** Design px (tokens.json alt_cubuk_ust 932 → 1024). */
        const val YUKSEKLIK = 92
        private const val BLOK_GENISLIK = 150
        private const val SIMGE_GENISLIK = 84
        private const val SIMGE_BOYUT = 26
        /** Five 84 px icons centred: (768 − 5×84) / 2. */
        private const val SIMGE_BASLA = 174
    }
}
