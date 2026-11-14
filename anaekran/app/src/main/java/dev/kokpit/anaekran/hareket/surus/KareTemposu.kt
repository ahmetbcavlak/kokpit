package dev.kokpit.anaekran.hareket.surus

/**
 * 60 ↔ 30 fps decision for the drive scene's frame loop (research 0.2 §4, decision 8): an even
 * 30 fps looks better than a 60/30 mix. Pure Kotlin; fed with every Choreographer callback time.
 *
 * - Full rate: every callback is drawn. The interval between drawn frames is averaged (EMA);
 *   when it stays ≥ [YAVAS] × VSYNC (20 ms at 60 Hz) for [YARIYA_INIS_NS] (1 s), switch to half.
 * - Half rate: a callback is drawn only when ≥ 1.5 VSYNC have passed since the last drawn one
 *   (every second VSYNC). Measure: the time from a drawn frame to the NEXT callback. If drawing
 *   fits in one VSYNC, the next callback comes one VSYNC later (≤ [YAVAS] × VSYNC); when that
 *   holds for [geriDonusNs] (3 s), return to full rate. Each fall back to half rate within
 *   [GERI_DONUS_BASARI_NS] doubles the wait (3, 6, 12 … up to 48 s): no 60/30 flapping.
 * Long gaps (a GC pause, the loop resumed) are clamped before averaging so a single stall does
 * not switch the mode. The jank-free case costs nothing: one compare per frame.
 */
class KareTemposu(vsyncNs: Long = VARSAYILAN_VSYNC_NS) {

    var vsyncNs: Long = vsyncNs
        set(value) {
            if (value in 4_000_000L..50_000_000L) field = value
        }

    /** True: every second VSYNC is drawn (30 fps on a 60 Hz panel). */
    var yariHiz = false
        private set

    /** How many times half rate was entered (diagnostics). */
    var yariyaInisSayisi = 0
        private set

    private var oncekiNs = YOK
    private var sonCizimNs = YOK
    private var ortalama = 0.0
    private var esikBasNs = YOK
    private var geriDonusNs = GERI_DONUS_ILK_NS
    private var tamaDonusNs = YOK
    private var sonrakiOlcum = false

    /** The loop (re)starts: forget timings, keep the learnt mode and wait. */
    fun sifirla() {
        oncekiNs = YOK
        sonCizimNs = YOK
        esikBasNs = YOK
        sonrakiOlcum = false
        ortalama = vsyncNs.toDouble()
    }

    /** Called with every callback's frame time; true = draw (advance the scene) on this frame. */
    fun ciz(t: Long): Boolean {
        val onceki = oncekiNs
        oncekiNs = t
        if (onceki == YOK || t <= onceki) {
            sonCizimNs = t
            ortalama = vsyncNs.toDouble()
            return true
        }
        val esik = vsyncNs * YAVAS
        if (!yariHiz) {
            besle((t - onceki).toDouble())
            if (ortalama >= esik) {
                if (esikBasNs == YOK) esikBasNs = t
                if (t - esikBasNs >= YARIYA_INIS_NS) yariyaGec(t)
            } else {
                esikBasNs = YOK
            }
            sonCizimNs = t
            return true
        }
        if (sonrakiOlcum) {
            sonrakiOlcum = false
            besle((t - onceki).toDouble())
            if (ortalama <= esik) {
                if (esikBasNs == YOK) esikBasNs = t
                if (t - esikBasNs >= geriDonusNs) {
                    yariHiz = false
                    esikBasNs = YOK
                    tamaDonusNs = t
                    ortalama = vsyncNs.toDouble()
                    sonCizimNs = t
                    return true
                }
            } else {
                esikBasNs = YOK
            }
        }
        if (t - sonCizimNs >= vsyncNs * 3 / 2) {
            sonCizimNs = t
            sonrakiOlcum = true
            return true
        }
        return false
    }

    private fun yariyaGec(t: Long) {
        yariHiz = true
        yariyaInisSayisi++
        esikBasNs = YOK
        // Fell back soon after returning: 60 did not hold, wait longer before the next try.
        if (tamaDonusNs != YOK && t - tamaDonusNs < GERI_DONUS_BASARI_NS) {
            geriDonusNs = (geriDonusNs * 2).coerceAtMost(GERI_DONUS_EN_COK_NS)
        }
        ortalama = vsyncNs.toDouble()
    }

    private fun besle(araNs: Double) {
        val a = araNs.coerceAtMost(vsyncNs * 3.0)
        ortalama += (a - ortalama) * EMA
    }

    companion object {
        const val VARSAYILAN_VSYNC_NS = 16_666_667L
        const val YAVAS = 1.2
        const val YARIYA_INIS_NS = 1_000_000_000L
        const val GERI_DONUS_ILK_NS = 3_000_000_000L
        const val GERI_DONUS_EN_COK_NS = 48_000_000_000L
        const val GERI_DONUS_BASARI_NS = 10_000_000_000L
        private const val EMA = 0.1
        private const val YOK = Long.MIN_VALUE
    }
}
