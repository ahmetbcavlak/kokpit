package dev.kokpit.anaekran.ekran

import android.content.Context
import android.graphics.Canvas
import android.view.View
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.Cizgiler
import dev.kokpit.anaekran.arayuz.Cizim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.MutlakDuzen.Hiza
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * Intro, Y-1, laid over `klip_giris` (the clip plays in the video layer below this view):
 * a time-of-day greeting in Instrument Serif, "Gece mavisi hazır", the "+" marks, the ruler,
 * Y-1's own light line at y 900 and the mono line "21:42 · dış 14°" at the bottom (the
 * reference's "iphone bağlanıyor…" is not drawn: no verified phone link). The bottom bar is
 * hidden while this screen is in front (Y-1 has none). A tap cuts the clip ([dokunma]).
 *
 * Always the NIGHT palette, by day too: klip_giris, its poster and its last frame
 * (sahne_karsilama) exist only in the night look, so the day palette's dark ink (#0B0F16) would
 * be black on black. [temaDegisti] is therefore ignored here.
 */
class AcilisEkrani(context: Context, dokunma: () -> Unit) : EkranGorunumu {

    private val kok = MutlakDuzen(context)
    override val gorunum: View = kok

    private val palet = Palet.GECE
    private var durum = AracDurumu()
    private val saatFormat = SimpleDateFormat("HH:mm", DurumSatiri.TR)

    private val cizim = Cizim(context) { ciz(it) }
    private val selam = context.metin(Yazi.serif, 76, palet.tx, -0.01f)
    private val alt = context.metin(Yazi.sans, 18, palet.mu).apply { setText(R.string.acilis_alt) }
    private val satir = context.metin(Yazi.mono, 12, palet.hud, 0.04f, tabular = true)

    init {
        kok.ekle(cizim, 0, 0, 768, 1024)
        kok.ekleMetin(selam, 384, 150, satir = 1f, hiza = Hiza.ORTA)
        kok.ekleMetin(alt, 384, 150 + 76 + 16, hiza = Hiza.ORTA)
        kok.ekleMetin(satir, 384, 944, hiza = Hiza.ORTA)
        kok.setOnClickListener { dokunma() }
        yaz()
    }

    private fun ciz(c: Canvas) {
        val p = palet
        Cizgiler.arti(c, p, 33f, 113f)
        Cizgiler.arti(c, p, 721f, 113f)
        Cizgiler.arti(c, p, 33f, 753f)
        Cizgiler.arti(c, p, 721f, 753f)
        Cizgiler.cetvel(c, p, 662f)
        Cizgiler.acilisIsigi(c, p, 900f)
    }

    override fun veriDegisti(d: AracDurumu) {
        durum = d
        yaz()
    }

    /** Ignored: the intro is drawn over night-only pictures (see the class notes). */
    override fun temaDegisti(p: Palet) = Unit

    override fun dakikaGecti() = yaz()
    override fun gosterildi() = yaz()

    private fun yaz() {
        val c = gorunum.context
        selam.yaz(c.getString(selamlama(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))))
        satir.yaz("${saatFormat.format(Date())} · ${c.getString(R.string.dis_derece, Bicim.derece(durum.disSicaklik))}")
    }

    companion object {
        /** 05–10 morning, 11–16 day, 17–21 evening, 22–04 night. */
        fun selamlama(saat: Int): Int = when (saat) {
            in 5..10 -> R.string.selam_sabah
            in 11..16 -> R.string.selam_gun
            in 17..21 -> R.string.selam_aksam
            else -> R.string.selam_gece
        }
    }
}
