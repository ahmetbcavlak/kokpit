package dev.kokpit.anaekran.ekran

import android.view.View
import android.widget.FrameLayout
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.AracVerisi
import java.util.EnumMap

/**
 * The only class that changes the screen. Views are built lazily, once, and kept in [kap]
 * (hidden ones are GONE, so they cost no drawing). Main thread only.
 */
class EkranYoneticisi(
    private val kap: FrameLayout,
    private val uret: (Ekran) -> EkranGorunumu,
) {
    var gecerli: Ekran? = null
        private set

    /** Last base screen (ACILIS/PARK/SURUS); panels return here. */
    var temel: Ekran = Ekran.PARK
        private set

    var gecis: Gecis = Gecis.ANINDA

    private val gorunumler = EnumMap<Ekran, EkranGorunumu>(Ekran::class.java)
    private val dinleyiciler = ArrayList<EkranDinleyici>()

    fun dinle(d: EkranDinleyici) {
        if (d !in dinleyiciler) dinleyiciler.add(d)
    }

    fun birak(d: EkranDinleyici) {
        dinleyiciler.remove(d)
    }

    fun gorunum(e: Ekran): EkranGorunumu = gorunumler.getOrPut(e) {
        uret(e).also {
            it.gorunum.visibility = View.GONE
            kap.addView(it.gorunum, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    fun goster(e: Ekran) {
        val eski = gecerli
        if (e == eski) return
        val yeni = gorunum(e)
        yeni.temaDegisti(Tema.palet)
        yeni.veriDegisti(AracVerisi.durum)
        gecerli = e
        if (e.temel) temel = e
        gecis.gec(eski?.let { gorunumler[it]?.gorunum }, yeni.gorunum) {}
        yeni.gosterildi()
        for (d in dinleyiciler.toList()) d.ekranDegisti(eski, e)
    }

    /** From a panel back to the base screen. False when already on a base screen. */
    fun geri(): Boolean {
        val g = gecerli ?: return false
        if (g.temel) return false
        goster(temel)
        return true
    }

    /** Opens [e], or closes it if it is already in front (bottom-bar toggles). */
    fun acKapa(e: Ekran) {
        if (gecerli == e && !e.temel) geri() else goster(e)
    }

    fun veriDegisti(d: AracDurumu) {
        gecerli?.let { gorunumler[it]?.veriDegisti(d) }
    }

    fun temaDegisti(p: Palet) {
        gecerli?.let { gorunumler[it]?.temaDegisti(p) }
    }

    fun dakikaGecti() {
        gecerli?.let { gorunumler[it]?.dakikaGecti() }
    }
}
