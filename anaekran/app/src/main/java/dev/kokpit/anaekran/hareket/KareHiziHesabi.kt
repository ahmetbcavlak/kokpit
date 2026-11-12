package dev.kokpit.anaekran.hareket

/**
 * Pure frame-rate arithmetic (no Android), fed with nanosecond timestamps.
 *
 * Two inputs, because they answer two different questions:
 *  - [cizildi]: the app drew a frame (ViewTreeObserver.OnDrawListener — every ViewRootImpl
 *    draw of the window). FPS = drawn frames per second *while drawing*: only gaps up to
 *    [boslukNs] count, so a 350 ms fade inside a 500 ms window reads ~60, not 42, and a
 *    30 fps clip reads 30. This is "how smooth did the motion look".
 *  - [vsync]: Choreographer frame callback (frameTimeNanos). Its rate is the display's VSYNC
 *    (≈60 Hz), whether anything is drawn or not, so it is NOT shown as FPS. The longest gap
 *    between two callbacks is the longest main-thread frame (a stall delays the next
 *    callback); it is shown as "en uzun kare" in ms.
 *
 * Every [pencereNs] of VSYNC time [vsync] returns a [Sonuc]: [Sonuc.Olcum] if at least
 * [enAzAralik] drawn-frame gaps fell in the window, otherwise [Sonuc.Bos] (nothing moved).
 */
class KareHiziHesabi(
    private val pencereNs: Long = 500_000_000L,
    private val boslukNs: Long = 100_000_000L,
    private val enAzAralik: Int = 3,
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

    fun cizildi(t: Long) {
        if (sonCizim >= 0) {
            val d = t - sonCizim
            if (d in 1..boslukNs) {
                aralikSayisi++
                aralikToplam += d
            }
        }
        sonCizim = t
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

        val s = if (aralikSayisi >= enAzAralik && aralikToplam > 0) {
            Sonuc.Olcum(
                fps = Math.round(aralikSayisi * 1e9 / aralikToplam).toInt(),
                enUzunMs = Math.round(enUzunVsync / 1e6).toInt(),
            )
        } else {
            Sonuc.Bos
        }
        pencereBasi = t
        enUzunVsync = 0L
        aralikSayisi = 0
        aralikToplam = 0L
        return s
    }

    /** The VSYNC loop paused: the next [vsync] opens a fresh window (no fake idle gap). */
    fun pencereyiKapat() {
        pencereBasi = -1L
        sonVsync = -1L
        enUzunVsync = 0L
        aralikSayisi = 0
        aralikToplam = 0L
    }

    fun sifirla() {
        pencereyiKapat()
        sonCizim = -1L
    }
}
