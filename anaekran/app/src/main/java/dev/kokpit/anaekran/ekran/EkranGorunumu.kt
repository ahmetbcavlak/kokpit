package dev.kokpit.anaekran.ekran

import android.view.View
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.veri.AracDurumu

/** One screen's view and the few events it needs. Only the screen in front gets events. */
interface EkranGorunumu {
    val gorunum: View
    fun veriDegisti(d: AracDurumu) {}
    fun temaDegisti(p: Palet) {}
    /** Once a minute while visible (clock). */
    fun dakikaGecti() {}
    /** Just came to the front. */
    fun gosterildi() {}
}

/**
 * How one screen view replaces another. 0.1: instantly. The motion task supplies its own
 * (alpha/translation only) and the screens task sets it on [EkranYoneticisi.gecis].
 * Contract: when done, [eski] is GONE (or null) and [yeni] is VISIBLE; call [bitti] once.
 */
fun interface Gecis {
    fun gec(eski: View?, yeni: View, bitti: () -> Unit)

    companion object {
        val ANINDA = Gecis { eski, yeni, bitti ->
            eski?.visibility = View.GONE
            yeni.visibility = View.VISIBLE
            bitti()
        }
    }
}

fun interface EkranDinleyici {
    fun ekranDegisti(eski: Ekran?, yeni: Ekran)
}
