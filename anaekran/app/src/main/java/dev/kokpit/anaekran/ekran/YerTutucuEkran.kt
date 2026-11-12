package dev.kokpit.anaekran.ekran

import android.content.Context
import android.view.View
import android.widget.TextView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.KapiDurumu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Placeholder for ACILIS / PARK / SURUS (final screens: task 4). Uses the reference positions
 * of Y-2 for the status line, the serif title and the HUD labels, so fonts and theme can be
 * checked on the car. Draws only verified data (K-74).
 */
class YerTutucuEkran(context: Context, private val ekran: Ekran, dokunma: (() -> Unit)? = null) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private var palet = Palet.GECE
    private val saatFormat = SimpleDateFormat("HH:mm", Locale("tr", "TR"))

    private val saat = context.metin(Yazi.sansOrta, 17, palet.tx, 0.01f, tabular = true)
    private val disUst = context.metin(Yazi.sans, 16, palet.mu, tabular = true)
    private val baslik = context.metin(Yazi.serif, 60, palet.tx, -0.01f)
    private val alt = context.metin(Yazi.sans, 16, palet.mu)

    private class Hud(val etiket: TextView, val deger: TextView)

    private val etiketler = ArrayList<Hud>()
    private val surus = ekran == Ekran.SURUS
    private val klima = hud(context, R.string.klima, 40, if (surus) 252 else 300, Hiza.SOL)
    private val kapilar = hud(context, R.string.kapilar, if (surus) 40 else 300, if (surus) 316 else 268, Hiza.SOL)
    private val bagaj = hud(context, R.string.bagaj, 728, if (surus) 252 else 316, Hiza.SAG)
    private val hizDeger = context.metin(Yazi.ince, 236, palet.tx, -0.02f, tabular = true)
    private val hizBirim = context.metin(Yazi.mono, 13, palet.mu, 0.06f).apply { text = "km/sa · gps" }

    init {
        // Status line: 56 px flex row, text centred (CSS normal line height = font box).
        kok.ekle(saat, 40, 17)
        kok.ekle(disUst, 728, 18, hiza = Hiza.SAG)
        if (ekran == Ekran.SURUS) {
            // Y-3: big GPS speed (Barlow 200), no title.
            kok.ekle(alt, 40, 72)
            // Barlow 200, 236 px: digits sit on the reference baseline (~355) → TextView top 119.
            kok.ekle(hizDeger, 384, 119, hiza = Hiza.ORTA)
            kok.ekle(hizBirim, 384, 366, hiza = Hiza.ORTA)
        } else {
            // Y-2: serif title 60 px, line-height 1 at top 92 → TextView top 83.
            kok.ekle(baslik, 40, 83)
            kok.ekle(alt, 40, 164)
        }
        baslik.setText(
            when (ekran) {
                Ekran.ACILIS -> R.string.ekran_acilis
                Ekran.SURUS -> R.string.ekran_surus
                else -> R.string.ekran_park
            },
        )
        alt.setText(R.string.yer_tutucu)
        if (dokunma != null) kok.setOnClickListener { dokunma() }
        saatiYaz()
    }

    private fun hud(c: Context, etiket: Int, x: Int, y: Int, hiza: Hiza): Hud {
        val e = c.metin(Yazi.mono, 12, palet.hud, 0.04f).apply { setText(etiket) }
        val d = c.metin(Yazi.sansOrta, 16, palet.tx)
        kok.ekle(e, x, y, hiza = hiza)
        kok.ekle(d, x, y + 20, hiza = hiza)
        return Hud(e, d).also { etiketler.add(it) }
    }

    private var sonDurum = AracDurumu()

    override fun veriDegisti(d: AracDurumu) {
        sonDurum = d
        disUst.text = "dış ${Bicim.derece(d.disSicaklik)}"
        val k = d.klima
        klima.deger.text = when {
            k == null -> Bicim.YOK
            k.oto == true -> "${Bicim.klimaDerece(k.sicaklik)} · Oto"
            else -> Bicim.klimaDerece(k.sicaklik)
        }
        val c = gorunum.context
        val (kapiMetin, kapiUyari) = when (d.kapi) {
            null -> Bicim.YOK to false
            KapiDurumu.KAPI_ACIK -> c.getString(R.string.acik) to true
            else -> c.getString(R.string.kapali) to false
        }
        val (bagajMetin, bagajUyari) = when (d.kapi) {
            null -> Bicim.YOK to false
            KapiDurumu.BAGAJ_ACIK -> c.getString(R.string.acik) to true
            else -> c.getString(R.string.kapali) to false
        }
        kapilar.deger.text = kapiMetin
        kapilar.deger.setTextColor(if (kapiUyari) palet.danger else palet.tx)
        bagaj.deger.text = bagajMetin
        bagaj.deger.setTextColor(if (bagajUyari) palet.danger else palet.tx)
        hizDeger.text = d.hiz?.let { Math.round(it).toString() } ?: Bicim.YOK
    }

    override fun temaDegisti(p: Palet) {
        palet = p
        saat.setTextColor(p.tx)
        disUst.setTextColor(p.mu)
        baslik.setTextColor(p.tx)
        alt.setTextColor(p.mu)
        hizDeger.setTextColor(p.tx)
        hizBirim.setTextColor(p.mu)
        for (h in etiketler) {
            h.etiket.setTextColor(p.hud)
            h.deger.setTextColor(p.tx)
        }
        veriDegisti(sonDurum) // re-colours open door/boot values
    }

    override fun dakikaGecti() = saatiYaz()
    override fun gosterildi() = saatiYaz()

    private fun saatiYaz() {
        saat.text = saatFormat.format(Date())
    }
}
