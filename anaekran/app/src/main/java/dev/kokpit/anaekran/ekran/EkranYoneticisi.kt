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
 *
 * Order inside [goster], so everything that depends on the screen moves together:
 *  1. [gecisOncesi] (MainActivity: starts the scene cross-fade / clip BEFORE anything changes),
 *  2. the new view gets theme and data, [gecerli]/[temel] change, the new view comes to the
 *     front of [kap] and, if [kap] is an [EkranKabi], becomes the only one that takes touches
 *     (E1: the old one keeps fading for 1.1 s but never gets a tap),
 *  3. [gecis] starts (old fades out, new fades in), the new view's `gosterildi()`,
 *  4. listeners hear [EkranDinleyici.ekranDegisti] — at the START of the transition (bottom bar
 *     highlight, warning layer, light line follow at once),
 *  5. when the transition's `bitti` comes: [gecisSuruyor] false, the old view's `gizlendi()`.
 * A [goster] while a transition runs: the Gecis finishes the running one at once (YumusakGecis
 * contract), then the new one starts — views are never left half-faded.
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

    /** Called first in [goster] with the old and the new screen (scene cross-fade hook). */
    var gecisOncesi: ((eski: Ekran?, yeni: Ekran) -> Unit)? = null

    /** True between a transition's start and its `bitti`. */
    var gecisSuruyor = false
        private set

    private var gecisNo = 0
    private val gorunumler = EnumMap<Ekran, EkranGorunumu>(Ekran::class.java)
    private val dinleyiciler = ArrayList<EkranDinleyici>()

    fun dinle(d: EkranDinleyici) {
        if (d !in dinleyiciler) dinleyiciler.add(d)
    }

    fun birak(d: EkranDinleyici) {
        dinleyiciler.remove(d)
    }

    /** The view of [e], built on first use. */
    fun gorunum(e: Ekran): EkranGorunumu = gorunumler.getOrPut(e) {
        uret(e).also {
            it.gorunum.visibility = View.GONE
            kap.addView(it.gorunum, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** The view of [e] if it was built already. */
    fun kurulduysa(e: Ekran): EkranGorunumu? = gorunumler[e]

    fun goster(e: Ekran) {
        val eski = gecerli
        if (e == eski) return
        val yeni = gorunum(e)
        gecisOncesi?.invoke(eski, e)
        yeni.temaDegisti(Tema.palet)
        yeni.veriDegisti(AracVerisi.durum)
        gecerli = e
        if (e.temel) temel = e
        val eskiGorunum = eski?.let { gorunumler[it] }
        val v = yeni.gorunum
        // On top of the screen fading out (bringToFront re-lays out the container: skip when it already is).
        if (kap.indexOfChild(v) != kap.childCount - 1) v.bringToFront()
        (kap as? EkranKabi)?.gecerli = v
        val no = ++gecisNo
        gecisSuruyor = true
        gecis.gec(eskiGorunum?.gorunum, yeni.gorunum) {
            if (no == gecisNo) gecisSuruyor = false
            if (eski != null && eski != gecerli) eskiGorunum?.gizlendi()
        }
        yeni.gosterildi()
        for (d in dinleyiciler.toList()) d.ekranDegisti(eski, e)
    }

    /**
     * The base screen changes (park ↔ drive). With a base screen in front it is shown; with a
     * panel in front only [temel] changes, so closing the panel lands on the right one.
     */
    fun temeliAyarla(e: Ekran) {
        require(e.temel) { "$e is not a base screen" }
        val g = gecerli
        if (g == null || g.temel) goster(e) else temel = e
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
