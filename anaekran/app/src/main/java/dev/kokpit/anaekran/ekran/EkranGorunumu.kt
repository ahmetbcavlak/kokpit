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
    /** Just came to the front (called when its transition starts, so it fades in with fresh content). */
    fun gosterildi() {}

    /** Its transition out has finished and it is GONE (it is not in front any more). */
    fun gizlendi() {}
}

/**
 * How one screen view replaces another. Default: instantly; MainActivity sets the motion
 * layer's YumusakGecis (alpha/translation only).
 * Contract: when done, [eski] is GONE (or null) and [yeni] is VISIBLE; call [bitti] once. A new
 * [gec] while one runs must finish the running one first (its [bitti] included).
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
