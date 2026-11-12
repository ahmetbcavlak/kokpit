package dev.kokpit.anaekran.ekran

import android.content.Context
import android.graphics.Canvas
import android.graphics.PointF
import android.view.View
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.AracHaritasi
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.Cizgiler
import dev.kokpit.anaekran.arayuz.Cizim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.SahneKatmani
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.HareketHali
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Park screen, Y-2 (night) / Y-7 (day). The car itself is in the scene layer below; this view
 * draws the status line, the serif title with what is known about the car, the HUD labels
 * (climate, doors, boot) with their dotted guide lines to cars.json anchors, the "+" corner
 * marks and the ruler — all at the reference's coordinates.
 *
 * Not drawn (K-74, no verified source): the media card, the CarPlay / "Eve git" / "Sinema" row,
 * the "iPhone" badge. Their place (y 728–916) holds the one-off hint card when there is one and
 * a quiet mono date line above the light line (the same voice as Y-1's bottom line), so the
 * lower third is not a hole but stays calm.
 *
 * While the door or boot is open the title block is left to the warning layer (Y-4/Y-10 put
 * "Kapı açık" in its place); the HUD stays and turns the open item to the accent colour.
 */
class ParkEkrani(
    context: Context,
    private val ipucuSec: () -> Ipucu?,
    ipucuDokunuldu: (Ipucu, Boolean) -> Unit,
) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE
    private var durum = AracDurumu()
    private var noktalar = AracHaritasi.noktalar(null)
    private val tarihFormat = SimpleDateFormat("EEEE · d MMMM", DurumSatiri.TR)

    private class Hud(val etiket: TextView, val deger: TextView, val ucX: Float, val cizgiY: Float) {
        var vurgu = false
        var nokta: PointF? = null
    }

    private val cizim = Cizim(context) { ciz(it) }
    private val satir: DurumSatiri
    private val baslik = context.metin(Yazi.serif, 60, palet.tx, -0.01f).apply { setText(R.string.park_baslik) }
    private val alt = context.metin(Yazi.sans, 16, palet.mu)
    private val klima: Hud
    private val kapilar: Hud
    private val bagaj: Hud
    private val tarih = context.metin(Yazi.mono, 12, palet.hud, 0.04f)
    val ipucuKarti = IpucuKarti(context, ipucuDokunuldu)

    init {
        kok.ekle(cizim, 0, 0, 768, 932)
        satir = DurumSatiri(kok)
        // Y-2: title block at top 92 — serif 60 / line-height 1, subtitle 16 / 1.4 after a 12 px gap.
        kok.ekleMetin(baslik, 40, 92, satir = 1f)
        kok.ekleMetin(alt, 40, 92 + 60 + 12, satir = 1.4f, w = 600)
        klima = hud(context, R.string.klima, 40f, 300f, Hiza.SOL)
        kapilar = hud(context, R.string.kapilar, 300f, 268f, Hiza.SOL)
        bagaj = hud(context, R.string.bagaj, 728f, 316f, Hiza.SAG)
        kok.ekle(ipucuKarti, 24, 744, 720, 88)
        kok.ekleMetin(tarih, 384, 884, hiza = Hiza.ORTA)
        tarihiYaz()
    }

    /** Label (Geist Mono 12 / 1.3) and value (Geist 500 16 / 1.25, 4 px below), guide line 44 px below the label top. */
    private fun hud(c: Context, etiket: Int, x: Float, ust: Float, hiza: Hiza): Hud {
        val e = c.metin(Yazi.mono, 12, palet.hud, 0.04f).apply { setText(etiket) }
        val d = c.metin(Yazi.sansOrta, 16, palet.tx)
        kok.ekleMetin(e, x, ust, satir = 1.3f, hiza = hiza)
        kok.ekleMetin(d, x, ust + 12f * 1.3f + 4f, satir = 1.25f, hiza = hiza)
        return Hud(e, d, x, ust + 44f)
    }

    private fun ciz(c: Canvas) {
        val p = palet
        Cizgiler.cetvel(c, p, 662f)
        for (h in listOf(klima, kapilar, bagaj)) h.nokta?.let { Cizgiler.kilavuz(c, p, it, h.cizgiY, h.ucX, h.vurgu) }
        Cizgiler.arti(c, p, 33f, 225f)
        Cizgiler.arti(c, p, 721f, 225f)
        Cizgiler.arti(c, p, 33f, 705f)
        Cizgiler.arti(c, p, 721f, 705f)
    }

    override fun veriDegisti(d: AracDurumu) {
        durum = d
        val c = gorunum.context
        satir.veri(d)

        alt.yaz(altSatir(c, d))
        baslikGorunurlugu()

        val k = d.klima
        klima.deger.yaz(
            when {
                k == null || k.sicaklik == null -> Bicim.YOK
                k.oto == true -> "${Bicim.klimaDerece(k.sicaklik)} · ${c.getString(R.string.oto)}"
                else -> Bicim.klimaDerece(k.sicaklik)
            },
        )
        kapilar.deger.yaz(
            when (d.kapiAcik) {
                true -> c.getString(R.string.acik) // which door, or how many, is not known
                false -> c.getString(R.string.kapali)
                null -> Bicim.YOK
            },
        )
        bagaj.deger.yaz(
            when (d.bagajAcik) {
                true -> c.getString(R.string.acik)
                false -> c.getString(R.string.kapali)
                null -> Bicim.YOK
            },
        )
        kapilar.vurgu = d.kapiAcik == true
        bagaj.vurgu = d.bagajAcik == true
        noktalariKur()
        renkleriUygula()
        cizim.invalidate()
    }

    /**
     * Set by MainActivity from the warning layer: true while it draws in the title area (Y-4/Y-10
     * title, the "closed" confirmation). The open door/boot hides the title on its own too, so the
     * two never show at once even for one frame.
     */
    var baslikUyarida = false
        set(v) {
            field = v
            baslikGorunurlugu()
        }

    /**
     * Set by MainActivity: true while the user has hidden the door/boot warning with a long press
     * (UyariKatmani.susturuldu). Then the open door no longer hides the title on its own.
     */
    var uyariSusturuldu = false
        set(v) {
            field = v
            baslikGorunurlugu()
        }

    private fun baslikGorunurlugu() {
        val gizli = baslikUyarida || (!uyariSusturuldu && (durum.kapiAcik == true || durum.bagajAcik == true))
        val b = if (gizli) View.INVISIBLE else View.VISIBLE
        if (baslik.visibility != b) baslik.visibility = b
        val a = if (gizli || alt.text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
        if (alt.visibility != a) alt.visibility = a
    }

    /** Only what is known: "Araç duruyor" needs GPS's PARK, door/boot words need the door service. */
    private fun altSatir(c: Context, d: AracDurumu): String {
        val parcalar = ArrayList<String>(2)
        if (d.hareketHali == HareketHali.PARK) parcalar += c.getString(R.string.arac_duruyor)
        if (d.kapiAcik == false) parcalar += c.getString(R.string.kapilar_kapali)
        if (d.bagajAcik == false) parcalar += c.getString(R.string.bagaj_kapali)
        val s = parcalar.joinToString(c.getString(R.string.ayrac))
        return s.replaceFirstChar { it.titlecase(DurumSatiri.TR) }
    }

    /** Anchors of the scene that is on screen: the boot-open picture has its own (night only). */
    private fun noktalariKur() {
        val sahne = SahneKatmani.kaynak(Ekran.PARK, palet.gunduz, durum.bagajAcik == true)
        noktalar = AracHaritasi.noktalar(SahneKatmani.kare(sahne))
        klima.nokta = noktalar.tavan
        kapilar.nokta = noktalar.kapi
        bagaj.nokta = noktalar.bagaj
    }

    private fun renkleriUygula() {
        val p = palet
        for (h in listOf(klima, kapilar, bagaj)) {
            h.etiket.renkle(p.hud)
            h.deger.renkle(if (h.vurgu) p.acc else p.tx)
        }
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        satir.tema(p)
        baslik.renkle(p.tx)
        alt.renkle(p.mu)
        tarih.renkle(p.hud)
        ipucuKarti.tema(p)
        veriDegisti(durum)
    }

    override fun dakikaGecti() {
        satir.saatiYaz()
        tarihiYaz()
    }

    override fun gosterildi() {
        dakikaGecti()
        ipucuYenile()
    }

    /** Re-reads which hint to show (on resume, after one was closed). */
    fun ipucuYenile() {
        ipucuKarti.goster(ipucuSec())
    }

    private fun tarihiYaz() {
        tarih.yaz(tarihFormat.format(Date()).lowercase(DurumSatiri.TR))
    }
}
