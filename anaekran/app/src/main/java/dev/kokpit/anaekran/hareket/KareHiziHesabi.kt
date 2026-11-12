package dev.kokpit.anaekran.hareket

/**
 * Pure frame-rate arithmetic (no Android), fed with nanosecond timestamps.
 *
 * Inputs:
 *  - [cizildi]: the app drew a frame (ViewTreeObserver.OnDrawListener — every ViewRootImpl
 *    draw of the window).
 *  - [hareketBasladi]/[hareketBitti]: a known motion runs (clip frames, scene fade, screen
 *    transition — told by KlipOynatici/YumusakGecis). Inside these spans
 *    FPS = drawn frames / span length, however long the gaps are: a decoder stuttering at
 *    8 fps reads 8, a frozen clip reads 0. This is the number the in-car check asks for.
 *  - [vsync]: Choreographer frame callback (frameTimeNanos). Its rate is the display's VSYNC
 *    (≈60 Hz) whether anything is drawn or not, so it is NOT shown as FPS. The longest gap
 *    between two callbacks is the longest main-thread frame.
 *
 * "en uzun kare" (ms) = the longest main-thread frame or, inside a motion span, the longest
 * gap between drawn frames (a stuttering decoder does not block the main thread, but its
 * frames are 125 ms apart — that is what the eye sees).
 *
 * Outside motion spans (other redraws: clock, data, taps) the old rule applies: gaps up to
 * [boslukNs] count, at least [enAzAralik] of them per window, else [Sonuc.Bos].
 *
 * Every [pencereNs] of VSYNC time [vsync] returns a [Sonuc].
 */
class KareHiziHesabi(
    private val pencereNs: Long = 500_000_000L,
    private val boslukNs: Long = 300_000_000L,
    private val enAzAralik: Int = 3,
    /** A window needs at least this much motion time to report motion fps. */
    private val enAzHareketNs: Long = 50_000_000L,
) {
    sealed interface Sonuc {
        data class Olcum(val fps: Int, val enUzunMs: Int) : Sonuc
        data object Bos : Sonuc
    }

    private var pencereBasi = -1L
    private var sonVsync = -1L
    private var enUzunVsync = 0L
    private var sonCizim = -1L
    private var aralikSayisi = 0
    private var aralikToplam = 0L

    private var hareketVar = false
    private var hareketBasi = -1L
    private var hareketSure = 0L
    private var hareketCizim = 0
    private var sonHareketOlayi = -1L
    private var enUzunHareket = 0L

    val harekette: Boolean get() = hareketVar

    fun hareketBasladi(t: Long) {
        if (hareketVar) return
        hareketVar = true
        hareketBasi = t
        sonHareketOlayi = t
    }

    fun hareketBitti(t: Long) {
        if (!hareketVar) return
        hareketVar = false
        if (t > hareketBasi) hareketSure += t - hareketBasi
        araligiKaydet(t)
        hareketBasi = -1L
    }

    fun cizildi(t: Long) {
        if (hareketVar) {
            hareketCizim++
            araligiKaydet(t)
        }
        if (sonCizim >= 0) {
            val d = t - sonCizim
            if (d in 1..boslukNs) {
                aralikSayisi++
                aralikToplam += d
            }
        }
        sonCizim = t
    }

    private fun araligiKaydet(t: Long) {
        if (sonHareketOlayi >= 0 && t - sonHareketOlayi > enUzunHareket) enUzunHareket = t - sonHareketOlayi
        sonHareketOlayi = t
    }

    fun vsync(t: Long): Sonuc? {
        if (pencereBasi < 0) {
            pencereBasi = t
            sonVsync = t
            return null
        }
        if (t - sonVsync > enUzunVsync) enUzunVsync = t - sonVsync
        sonVsync = t
        if (t - pencereBasi < pencereNs) return null

        if (hareketVar) {
            if (t > hareketBasi) hareketSure += t - hareketBasi
            hareketBasi = t
            // A frozen motion: the frame still on screen counts as one long frame so far.
            if (sonHareketOlayi >= 0 && t - sonHareketOlayi > enUzunHareket) enUzunHareket = t - sonHareketOlayi
        }
        val s = when {
            hareketSure >= enAzHareketNs -> Sonuc.Olcum(
                fps = Math.round(hareketCizim * 1e9 / hareketSure).toInt(),
                enUzunMs = Math.round(maxOf(enUzunVsync, enUzunHareket) / 1e6).toInt(),
            )
            aralikSayisi >= enAzAralik && aralikToplam > 0 -> Sonuc.Olcum(
                fps = Math.round(aralikSayisi * 1e9 / aralikToplam).toInt(),
                enUzunMs = Math.round(enUzunVsync / 1e6).toInt(),
            )
            else -> Sonuc.Bos
        }
        pencereBasi = t
        pencereSayaclariniSifirla()
        return s
    }

    private fun pencereSayaclariniSifirla() {
        enUzunVsync = 0L
        aralikSayisi = 0
        aralikToplam = 0L
        hareketSure = 0L
        hareketCizim = 0
        enUzunHareket = 0L
    }

    /** The VSYNC loop paused (only when no motion runs): the next [vsync] opens a fresh window. */
    fun pencereyiKapat() {
        pencereBasi = -1L
        sonVsync = -1L
        pencereSayaclariniSifirla()
    }

    /** Everything back to start, including the motion state (the caller re-opens a running motion). */
    fun sifirla() {
        pencereyiKapat()
        sonCizim = -1L
        hareketVar = false
        hareketBasi = -1L
        sonHareketOlayi = -1L
    }
}
