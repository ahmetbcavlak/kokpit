package dev.kokpit.anaekran.ekran

import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.Cizgiler
import dev.kokpit.anaekran.arayuz.Cizim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.uf
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import kotlin.math.roundToInt

/**
 * Drive screen, Y-3 (night) / Y-11 (day): the GPS speed in big Barlow Condensed 200 with the
 * 0–180 arc above it, "km/sa · gps", the climate HUD on the left, speed lines behind the car's
 * tail, the ruler. Not drawn: "bu yolculuk" (no trip source), the CarPlay/Haritalar row, the
 * media card (K-74); no door/boot HUD (not in Y-3 — the warning layer handles open doors).
 *
 * Speed unknown (no fix for 5 s, GPS starting): "—", no arc progress, and the unit line says
 * calmly that GPS is awaited (or that the location permission is missing).
 *
 * The speed TextView has a fixed size (wide enough for "188" in tabular digits), so a new
 * number only invalidates it, never re-measures the screen.
 */
class SurusEkrani(context: Context, private val konumIzni: () -> Boolean) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE
    private var hiz: Double? = null

    private val cizim = Cizim(context) { ciz(it) }
    private val satir: DurumSatiri
    private val sayi = context.metin(Yazi.ince, 232, palet.tx, -0.02f, tabular = true).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        maxLines = 1
    }
    private val birim = context.metin(Yazi.mono, 13, palet.mu, 0.06f)
    private val klimaEtiket = context.metin(Yazi.mono, 12, palet.hud, 0.04f).apply { setText(R.string.klima) }
    private val klimaDeger = context.metin(Yazi.sansOrta, 18, palet.tx, tabular = true)

    init {
        kok.ekle(cizim, 0, 0, 768, 932)
        satir = DurumSatiri(kok)
        // 232 px, line-height 0.8 at top 168, centred. Fixed box: "188" with tabular digits + slack.
        val genislik = Math.ceil((sayi.paint.measureText("188") / uf(1)).toDouble()).toInt() + 24
        val fm = sayi.paint.fontMetrics
        val yukseklik = Math.ceil(((fm.descent - fm.ascent) / uf(1)).toDouble()).toInt()
        kok.ekle(sayi, 384, 168f + (232f * 0.8f - yukseklik) / 2f, genislik, yukseklik, Hiza.ORTA)
        kok.ekleMetin(birim, 384, 364, hiza = Hiza.ORTA)
        kok.ekleMetin(klimaEtiket, 40, 250)
        kok.ekleMetin(klimaDeger, 40, 250 + 12 * 1.3f + 8)
    }

    private fun ciz(c: Canvas) {
        val p = palet
        Cizgiler.hizCizgileri(c, p)
        Cizgiler.hizYayi(c, p, hiz)
        Cizgiler.cetvel(c, p, 602f)
        Cizgiler.arti(c, p, 721f, 396f)
    }

    override fun veriDegisti(d: AracDurumu) {
        val c = gorunum.context
        satir.veri(d)
        sayi.yaz(d.hiz?.let { Math.max(0, it.roundToInt()).toString() } ?: Bicim.YOK)
        // Unknown speed: the dash in the muted colour, so it reads as "waiting", not as a value.
        sayi.renkle(if (d.hiz == null) palet.mu else palet.tx)
        birim.yaz(
            c.getString(
                when {
                    d.hiz != null -> R.string.hiz_birim
                    !konumIzni() -> R.string.hiz_izin_yok
                    else -> R.string.hiz_bekleniyor
                },
            ),
        )
        val k = d.klima
        klimaDeger.yaz(
            when {
                k == null || k.sicaklik == null -> Bicim.YOK
                k.oto == true -> "${Bicim.klimaDerece(k.sicaklik)} · ${c.getString(R.string.oto)}"
                else -> Bicim.klimaDerece(k.sicaklik)
            },
        )
        // The arc shows whole km/h like the number: no redraw for a change below 0.5 km/h.
        val yuvarlak = d.hiz?.roundToInt()?.toDouble()
        if (yuvarlak != hiz) {
            hiz = yuvarlak
            cizim.invalidate()
        }
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        satir.tema(p)
        sayi.renkle(if (hiz == null) p.mu else p.tx)
        birim.renkle(p.mu)
        klimaEtiket.renkle(p.hud)
        klimaDeger.renkle(p.tx)
        cizim.invalidate()
    }

    override fun dakikaGecti() = satir.saatiYaz()
    override fun gosterildi() = satir.saatiYaz()

    /** The number on screen (tests). */
    val hizMetni: CharSequence get() = sayi.text
    val birimMetni: CharSequence get() = birim.text
}
