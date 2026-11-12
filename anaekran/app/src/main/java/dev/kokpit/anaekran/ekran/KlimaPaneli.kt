package dev.kokpit.anaekran.ekran

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.Cam
import dev.kokpit.anaekran.arayuz.Cizgiler
import dev.kokpit.anaekran.arayuz.Cizim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.arayuz.ustDereceli
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.Sicaklik

/**
 * Climate panel, Y-5 — display only (K-29, K-75): nothing here sends, broadcasts or starts
 * anything. Shows what ACTION_AIR_INFO reports: set temperature (big number + 16–28 scale,
 * LOW/HI at the ends), auto/manual and A/C (mode line and the A/C·Oto card), fan level (number
 * and the 8 bars; the decoder only accepts 0–8).
 *
 * Passive controls: the −/+ buttons (temperature, fan) are drawn at 35 % opacity, and the
 * mono line under the card says they work from 0.2 on; a tap on them, on the A/C·Oto card or
 * on the big number does nothing but ask for a calm note ([pasif]). Removed from Y-5 because
 * no verified source exists: air direction, windscreen demist, "/ 8", "Tek bölge", the
 * İç hava / Ön cam MAX / Arka cam / Klima power toggles. The A/C·Oto card takes the free row
 * at y 690.
 *
 * Closes with the X, a tap on the background, or the bottom bar's climate block again.
 */
class KlimaPaneli(
    context: Context,
    private val kapat: () -> Unit,
    private val pasif: () -> Unit,
) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE
    private var durum = AracDurumu()
    private var isaretX: Float? = null
    private var fan: Int? = null

    private val cizim = Cizim(context) { ciz(it) }
    private val satir: DurumSatiri
    private val baslik = context.metin(Yazi.sansOrta, 34, palet.tx, -0.015f).apply { setText(R.string.ekran_klima) }
    private val alt = context.metin(Yazi.sans, 16, palet.mu)
    private val kapatDugmesi = ImageView(context).apply {
        setImageResource(R.drawable.ic_kapat)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(u(25), u(25), u(25), u(25))
        contentDescription = context.getString(R.string.kapat)
        setOnClickListener { kapat() }
    }
    private val buyuk = context.metin(Yazi.ince, 216, palet.tx, -0.02f, tabular = true).apply {
        isClickable = true
        setOnClickListener { pasif() }
    }
    private val mod = context.metin(Yazi.mono, 13, palet.mu, 0.06f)
    private val fanEtiket = context.metin(Yazi.sans, 16, palet.mu).apply { setText(R.string.fan) }
    private val fanDeger = context.metin(Yazi.sansOrta, 16, palet.tx, tabular = true)
    private val pasifNot = context.metin(Yazi.mono, 12, palet.hud, 0.04f).apply { setText(R.string.pasif_not) }

    /** The four −/+ buttons (passive). */
    val pasifDugmeler: List<FrameLayout>

    /** The A/C · Oto card (an indicator; taps only ask for the note). */
    val acOtoKarti = MutlakDuzen(context).apply {
        isClickable = true
        setOnClickListener { pasif() }
    }
    private val acSimge = simge(context, R.drawable.ic_ac)
    private val otoSimge = simge(context, R.drawable.ic_oto)
    private val acEtiket = context.metin(Yazi.sansOrta, 16, palet.tx).apply { setText(R.string.ac) }
    private val otoEtiket = context.metin(Yazi.sansOrta, 16, palet.tx).apply { setText(R.string.oto) }
    private val acDeger = context.metin(Yazi.sans, 15, palet.mu)
    private val otoDeger = context.metin(Yazi.sans, 15, palet.mu)
    private val ayrac = View(context)

    init {
        kok.setOnClickListener { kapat() }
        kok.ekle(cizim, 0, 0, 768, 932)
        satir = DurumSatiri(kok)
        kok.ekleMetin(baslik, 40, 84)
        kok.ekleMetin(alt, 40, 84 + 34 * 1.3f + 8)
        kok.ekle(kapatDugmesi, 668, 76, 76, 76)
        kok.ekleMetin(buyuk, 384, 206, satir = 0.8f, hiza = Hiza.ORTA)
        kok.ekleMetin(mod, 384, 396, hiza = Hiza.ORTA)
        kok.ekleMetin(fanEtiket, 40, 560)
        kok.ekleMetin(fanDeger, 728, 560, hiza = Hiza.SAG)
        pasifDugmeler = listOf(
            pasifDugme(context, R.drawable.ic_eksi, R.string.sicaklik_azalt, 24, 236, 96, 28f, 28),
            pasifDugme(context, R.drawable.ic_arti, R.string.sicaklik_artir, 648, 236, 96, 28f, 28),
            pasifDugme(context, R.drawable.ic_eksi, R.string.fan_azalt, 24, 596, 76, 22f, 24),
            pasifDugme(context, R.drawable.ic_arti, R.string.fan_artir, 668, 596, 76, 22f, 24),
        )
        kok.ekle(acOtoKarti, 24, 690, 720, 76)
        acOtoKarti.ekle(acSimge, 20, 27, 22, 22)
        acOtoKarti.ekleMetin(acEtiket, 52, 0, satir = 76f / 16f)
        acOtoKarti.ekleMetin(acDeger, 340, 0, satir = 76f / 15f, hiza = Hiza.SAG)
        acOtoKarti.ekle(ayrac, 360, 16, 1, 44)
        acOtoKarti.ekle(otoSimge, 380, 27, 22, 22)
        acOtoKarti.ekleMetin(otoEtiket, 412, 0, satir = 76f / 16f)
        acOtoKarti.ekleMetin(otoDeger, 700, 0, satir = 76f / 15f, hiza = Hiza.SAG)
        kok.ekleMetin(pasifNot, 384, 800, hiza = Hiza.ORTA)
    }

    private fun simge(c: Context, id: Int) = ImageView(c).apply {
        setImageResource(id)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun pasifDugme(c: Context, ikon: Int, aciklama: Int, x: Int, y: Int, boyut: Int, yaricap: Float, ikonBoyut: Int): FrameLayout {
        val iv = ImageView(c).apply {
            setImageResource(ikon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val f = FrameLayout(c).apply {
            addView(iv, FrameLayout.LayoutParams(u(ikonBoyut), u(ikonBoyut), Gravity.CENTER))
            contentDescription = c.getString(aciklama)
            isClickable = true
            setOnClickListener { pasif() }
            tag = yaricap
        }
        kok.ekle(f, x, y, boyut, boyut)
        return f
    }

    private fun ciz(c: Canvas) {
        val p = palet
        Cizgiler.sicaklikOlcegi(c, p, isaretX)
        Cizgiler.arti(c, p, 33f, 189f)
        Cizgiler.arti(c, p, 721f, 189f)
        Cizgiler.arti(c, p, 33f, 533f)
        Cizgiler.arti(c, p, 721f, 533f)
        // Bars between the fan buttons: x 116–652, a 42 px box centred in the 76 px row (596).
        Cizgiler.fanCubuklari(c, p, RectF(116f, 613f, 652f, 655f), fan)
    }

    override fun veriDegisti(d: AracDurumu) {
        durum = d
        val c = gorunum.context
        val k = d.klima
        satir.veri(d)
        alt.yaz(c.getString(R.string.klima_alt, c.getString(R.string.dis_derece, Bicim.derece(d.disSicaklik))))
        buyuk.yaz(ustDereceli(Bicim.klimaDerece(k?.sicaklik), uf(96), uf(34)))
        mod.yaz(
            listOfNotNull(
                k?.oto?.let { c.getString(if (it) R.string.mod_oto else R.string.mod_elle) },
                k?.ac?.let { c.getString(if (it) R.string.mod_ac_acik else R.string.mod_ac_kapali) },
            ).joinToString(" · ").ifEmpty { Bicim.YOK },
        )
        fanDeger.yaz(k?.fan?.toString() ?: Bicim.YOK)
        acDeger.yaz(durumMetni(c, k?.ac))
        otoDeger.yaz(durumMetni(c, k?.oto))
        val yeniX = when (val s = k?.sicaklik) {
            is Sicaklik.Derece -> Cizgiler.olcekX(s.c)
            Sicaklik.Low -> Cizgiler.olcekX(0.0)
            Sicaklik.Hi -> Cizgiler.olcekX(99.0)
            null -> null
        }
        val yeniFan = k?.fan
        if (yeniX != isaretX || yeniFan != fan) {
            isaretX = yeniX
            fan = yeniFan
            cizim.invalidate()
        }
        simgeleriRenkle()
    }

    private fun durumMetni(c: Context, b: Boolean?) = when (b) {
        true -> c.getString(R.string.acik_kucuk)
        false -> c.getString(R.string.kapali_kucuk)
        null -> Bicim.YOK
    }

    private fun simgeleriRenkle() {
        val k = durum.klima
        acSimge.imageTintList = ColorStateList.valueOf(if (k?.ac == true) palet.acc else palet.mu)
        otoSimge.imageTintList = ColorStateList.valueOf(if (k?.oto == true) palet.acc else palet.mu)
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        // Y-5 dims the night texture to 70 % (filter: brightness(.7)): a 30 % black veil on this
        // view, so it fades in and out with the panel. Day: no veil (no day reference).
        kok.setBackgroundColor(if (p.gunduz) Color.TRANSPARENT else Color.argb(77, 0, 0, 0))
        satir.tema(p)
        baslik.renkle(p.tx)
        alt.renkle(p.mu)
        kapatDugmesi.imageTintList = ColorStateList.valueOf(p.tx)
        buyuk.renkle(p.tx)
        mod.renkle(p.mu)
        fanEtiket.renkle(p.mu)
        fanDeger.renkle(p.tx)
        pasifNot.renkle(p.hud)
        for (d in pasifDugmeler) {
            // Passive look: background and icon at 35 % (drawable alpha, no offscreen layer).
            d.background = Cam.arka(p, d.tag as Float).apply { alpha = PASIF_ALFA }
            (d.getChildAt(0) as ImageView).apply {
                imageTintList = ColorStateList.valueOf(p.tx)
                imageAlpha = PASIF_ALFA
            }
        }
        acOtoKarti.background = Cam.arka(p, 22f)
        acEtiket.renkle(p.tx)
        otoEtiket.renkle(p.tx)
        acDeger.renkle(p.mu)
        otoDeger.renkle(p.mu)
        ayrac.setBackgroundColor(p.hair)
        cizim.invalidate()
        veriDegisti(durum)
    }

    override fun dakikaGecti() = satir.saatiYaz()
    override fun gosterildi() = satir.saatiYaz()

    /** Every view on this panel that reacts to a tap (tests tap them all). */
    val dokunulabilirler: List<View> get() = pasifDugmeler + listOf(acOtoKarti, buyuk, kapatDugmesi, kok)

    /** Texts for tests. */
    internal val buyukMetin: TextView get() = buyuk
    internal val fanMetin: TextView get() = fanDeger

    companion object {
        private const val PASIF_ALFA = 89 // 35 %
    }
}
