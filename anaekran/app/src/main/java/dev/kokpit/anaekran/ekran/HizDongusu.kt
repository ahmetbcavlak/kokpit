package dev.kokpit.anaekran.ekran

import android.view.Choreographer
import dev.kokpit.anaekran.hareket.KareKaynagi
import dev.kokpit.anaekran.hareket.surus.DurgunSaglayici
import dev.kokpit.anaekran.hareket.surus.HizSaglayici
import dev.kokpit.anaekran.hareket.surus.KareDinleyici

/**
 * The drive screen's own small frame loop for the speed digits and arc (K-96), for the times
 * the drive scene does not run: by day, while the park → drive clip plays, before the scene's
 * bitmaps are ready, and on a drive screen whose scene is hidden.
 *
 * Single reader (HizYumusatici.deger advances its spring on every call): when [sahneCalisiyor]
 * is true the scene reads the speed and hands it to the HUD itself (HareketBaglayici.surusKaresi),
 * so this loop neither reads nor even asks for a frame. The test is made again inside every
 * callback, so a frame on which the scene has started is not read twice.
 *
 * Runs only while [etkin] (drive screen in front, activity resumed, diagnostics closed). Stops
 * by itself when [durgun] says the speed is at rest (the digits hold "0"/"—"); [uyandir] (wired
 * to Hiz.yumusatici.uyandir together with the scene's) and [yenile] start it again.
 * Time = the callback's frameTimeNanos only. Main thread only; no allocation per frame.
 */
class HizDongusu(
    private val kaynak: KareKaynagi,
    private val hiz: HizSaglayici,
    private val durgun: DurgunSaglayici,
    private val sahneCalisiyor: () -> Boolean,
    private val kare: KareDinleyici,
) {
    /** Drive screen in front, resumed, not covered: the loop may run. */
    var etkin = false
        set(value) {
            if (field == value) return
            field = value
            yenile()
        }

    /** A frame callback is pending (tests, diagnostics). */
    var bekliyor = false
        private set

    /** Speed reads this loop made (tests, diagnostics). */
    var okumaSayisi = 0L
        private set

    private val geriCagri = Choreographer.FrameCallback { t ->
        bekliyor = false
        kareGeldi(t)
    }

    /** Re-checks whether the loop must run (scene started/stopped, screen changed). */
    fun yenile() {
        if (etkin && !sahneCalisiyor()) iste() else birak()
    }

    /** A new speed may have arrived: restart a stopped loop. */
    fun uyandir() = yenile()

    private fun iste() {
        if (bekliyor) return
        bekliyor = true
        kaynak.iste(geriCagri)
    }

    private fun birak() {
        if (!bekliyor) return
        bekliyor = false
        kaynak.birak(geriCagri)
    }

    private fun kareGeldi(t: Long) {
        if (!etkin || sahneCalisiyor()) return
        val v = hiz.hiz(t)
        okumaSayisi++
        kare.kare(t, if (v.isNaN() || v < 0f) 0f else v)
        if (!durgun.durgun(t)) iste()
    }
}
