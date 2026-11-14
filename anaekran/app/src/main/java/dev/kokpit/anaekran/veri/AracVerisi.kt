package dev.kokpit.anaekran.veri

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Listener for state changes; always called on the main thread. */
fun interface AracDinleyici {
    fun durumDegisti(durum: AracDurumu)
}

/**
 * The single vehicle state of the app. Sources write only through [guncelle];
 * screens read [durum] and listen with [dinle].
 *
 * Threads:
 *  - [dinle], [birak] and [sifirla]: main thread only.
 *  - [guncelle]: any thread. On the main thread the block runs at once; from another thread it runs
 *    LATER on the main thread (posted to the main looper), so readers never need locks. The update time
 *    is taken at the call, but the block itself runs later: compute the values before the call and only
 *    hand them to the block (`val v = …; AracVerisi.guncelle { hiz(v) }`), never read changing state inside it.
 *
 * Listeners hear only a change of a VALUE. A write that repeats the same values updates the
 * per-field times silently (AC8227L: no screen work for a speed that did not change).
 * Exception by design (0.2): [AracDurumu.hamHizOrnekNs] is a value, so every GPS fix wakes the
 * listeners (≈ once a second while the fixes come).
 *
 * The smoothed speed for animation is not here: it lives in [Hiz.yumusatici] (frame loop, m/s).
 */
object AracVerisi {

    @Volatile
    var durum: AracDurumu = AracDurumu()
        private set

    /** Clock for the per-field update times; tests replace it. */
    var saat: () -> Long = { SystemClock.elapsedRealtime() }

    private val dinleyiciler = ArrayList<AracDinleyici>()
    private val ana by lazy { Handler(Looper.getMainLooper()) }

    /** Main thread only. */
    fun dinle(d: AracDinleyici) {
        if (d !in dinleyiciler) dinleyiciler.add(d)
    }

    /** Main thread only. */
    fun birak(d: AracDinleyici) {
        dinleyiciler.remove(d)
    }

    /** `AracVerisi.guncelle { hiz(62.0) }` — one write path for every source. See the class notes on threads. */
    fun guncelle(blok: Yazici.() -> Unit) {
        val simdi = saat()
        if (Looper.myLooper() == Looper.getMainLooper()) yaz(simdi, blok) else ana.post { yaz(simdi, blok) }
    }

    /** Back to "nothing known" (tests, previews). Main thread only. */
    fun sifirla() {
        yayinla(AracDurumu())
    }

    private fun yaz(simdi: Long, blok: Yazici.() -> Unit) {
        val y = Yazici(durum, simdi)
        y.blok()
        yayinla(y.sonuc)
    }

    private fun yayinla(yeni: AracDurumu) {
        if (yeni == durum) return
        val degisti = !yeni.ayniDegerler(durum)
        durum = yeni
        if (!degisti) return
        for (d in dinleyiciler.toList()) d.durumDegisti(yeni)
    }

    /** Writes values and stamps their update time. */
    class Yazici internal constructor(baslangic: AracDurumu, private val simdi: Long) {
        internal var sonuc: AracDurumu = baslangic
            private set

        fun klima(k: Klima?) { sonuc = sonuc.copy(klima = k, klimaZamani = simdi) }
        fun kapiAcik(a: Boolean?) { sonuc = sonuc.copy(kapiAcik = a, kapiAcikZamani = simdi) }
        fun bagajAcik(a: Boolean?) { sonuc = sonuc.copy(bagajAcik = a, bagajAcikZamani = simdi) }
        fun disSicaklik(c: Double?) { sonuc = sonuc.copy(disSicaklik = c, disSicaklikZamani = simdi) }
        fun hiz(kmh: Double?) { sonuc = sonuc.copy(hiz = kmh, hizZamani = simdi) }
        /** Raw speed in m/s and its fix's arrival time (elapsedRealtimeNanos); see [AracDurumu.hamHiz]. */
        fun hamHiz(ms: Float?, ornekNs: Long) { sonuc = sonuc.copy(hamHiz = ms, hamHizOrnekNs = ornekNs) }
        fun hareketHali(h: HareketHali?) { sonuc = sonuc.copy(hareketHali = h, hareketHaliZamani = simdi) }

        /**
         * Old single-state writer, kept for the skeleton's tests: KAPI_ACIK = door open + boot closed,
         * BAGAJ_ACIK = the reverse, KAPALI = both closed, null = both unknown.
         */
        @Deprecated("Use kapiAcik and bagajAcik")
        fun kapi(k: KapiDurumu?) {
            kapiAcik(k?.let { it == KapiDurumu.KAPI_ACIK })
            bagajAcik(k?.let { it == KapiDurumu.BAGAJ_ACIK })
        }

        /** Whole snapshot at once (fake data, restoring a saved state). */
        fun hepsi(d: AracDurumu) {
            sonuc = d.copy(
                klimaZamani = if (d.klima != null) simdi else 0L,
                kapiAcikZamani = if (d.kapiAcik != null) simdi else 0L,
                bagajAcikZamani = if (d.bagajAcik != null) simdi else 0L,
                disSicaklikZamani = if (d.disSicaklik != null) simdi else 0L,
                hizZamani = if (d.hiz != null) simdi else 0L,
                hareketHaliZamani = if (d.hareketHali != null) simdi else 0L,
            )
        }
    }
}
