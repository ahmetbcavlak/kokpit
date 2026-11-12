package dev.kokpit.anaekran.ekran

import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import dev.kokpit.anaekran.veri.AracDurumu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The 56 px status line of the references (Y-2…Y-5): time on the left (Geist 500 17), outside
 * temperature on the right (Geist 16, muted). The reference's "iPhone" badge is not drawn (no
 * verified phone link, K-74), so the temperature moves to the right edge (728).
 * Text is written only when it changes; the time is refreshed once a minute by the owner.
 */
class DurumSatiri(kok: MutlakDuzen) {

    private val c = kok.context
    private val saatFormat = SimpleDateFormat("HH:mm", TR)

    val saat = c.metin(Yazi.sansOrta, 17, Palet.GECE.tx, 0.01f, tabular = true)
    val dis = c.metin(Yazi.sans, 16, Palet.GECE.mu, tabular = true)

    init {
        // A 56 px flex row centres each line: line-height = 56 px.
        kok.ekleMetin(saat, 40, 0, satir = 56f / 17f)
        kok.ekleMetin(dis, 728, 0, satir = 56f / 16f, hiza = MutlakDuzen.Hiza.SAG)
        saatiYaz()
    }

    fun veri(d: AracDurumu) {
        dis.yaz(c.getString(R.string.dis_derece, Bicim.derece(d.disSicaklik)))
    }

    fun saatiYaz(simdi: Date = Date()) {
        saat.yaz(saatFormat.format(simdi))
    }

    fun tema(p: Palet) {
        saat.renkle(p.tx)
        dis.renkle(p.mu)
    }

    companion object {
        val TR = Locale("tr", "TR")
    }
}
