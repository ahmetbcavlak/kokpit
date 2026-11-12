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
 * screens read [durum] and listen with [dinle]. Any thread may call [guncelle]:
 * the write is moved to the main thread, so readers never need locks.
 */
object AracVerisi {

    @Volatile
    var durum: AracDurumu = AracDurumu()
        private set

    /** Clock for the per-field update times; tests replace it. */
    var saat: () -> Long = { SystemClock.elapsedRealtime() }

    private val dinleyiciler = ArrayList<AracDinleyici>()
    private val ana by lazy { Handler(Looper.getMainLooper()) }

    fun dinle(d: AracDinleyici) {
        if (d !in dinleyiciler) dinleyiciler.add(d)
    }

    fun birak(d: AracDinleyici) {
        dinleyiciler.remove(d)
    }

    /** `AracVerisi.guncelle { hiz(62.0) }` — one write path for every source. */
    fun guncelle(blok: Yazici.() -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) yaz(blok) else ana.post { yaz(blok) }
    }

    /** Back to "nothing known" (tests, previews). Main thread only. */
    fun sifirla() {
        yayinla(AracDurumu())
    }

    private fun yaz(blok: Yazici.() -> Unit) {
        val y = Yazici(durum, saat())
        y.blok()
        yayinla(y.sonuc)
    }

    private fun yayinla(yeni: AracDurumu) {
        if (yeni == durum) return
        durum = yeni
        for (d in dinleyiciler.toList()) d.durumDegisti(yeni)
    }

    /** Writes values and stamps their update time. */
    class Yazici internal constructor(baslangic: AracDurumu, private val simdi: Long) {
        internal var sonuc: AracDurumu = baslangic
            private set

        fun klima(k: Klima?) { sonuc = sonuc.copy(klima = k, klimaZamani = simdi) }
        fun kapi(k: KapiDurumu?) { sonuc = sonuc.copy(kapi = k, kapiZamani = simdi) }
        fun disSicaklik(c: Double?) { sonuc = sonuc.copy(disSicaklik = c, disSicaklikZamani = simdi) }
        fun hiz(kmh: Double?) { sonuc = sonuc.copy(hiz = kmh, hizZamani = simdi) }

        /** Whole snapshot at once (fake data, restoring a saved state). */
        fun hepsi(d: AracDurumu) {
            sonuc = d.copy(
                klimaZamani = if (d.klima != null) simdi else 0L,
                kapiZamani = if (d.kapi != null) simdi else 0L,
                disSicaklikZamani = if (d.disSicaklik != null) simdi else 0L,
                hizZamani = if (d.hiz != null) simdi else 0L,
            )
        }
    }
}
