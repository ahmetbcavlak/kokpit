package dev.kokpit.anaekran.veri

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * GPS speed (LocationManager.GPS_PROVIDER, minTime 0 / minDistance 0: design pack 0.2 §3) and the
 * parked/driving state. The speed comes only from Location.getSpeed() when hasSpeed() is true; it is
 * never derived from positions. The fix rate is not assumed: every fix's arrival is measured
 * ([Teshis.konumAraligi]); if it is far from 1 s on the car, go back to minTime 1000 (research decision 11).
 *
 * Every fix with a speed is written three ways:
 *  - [AracDurumu.hiz]: km/h rounded to 1, and [AracDurumu.hareketHali] from the unrounded value (0.1 rules);
 *  - [AracDurumu.hamHiz] + [AracDurumu.hamHizOrnekNs]: raw m/s and the arrival time (elapsedRealtimeNanos);
 *  - [yumusatici] (normally [Hiz.yumusatici], wired by [VeriBaglayici]): raw m/s, arrival time on the
 *    frame clock (System.nanoTime(), same base as Choreographer), speed accuracy if present.
 *
 * Staleness, checked once a second on the main thread:
 *  - no fix with speed for more than [HIZ_ESKIME_NS] (5 s): speed (and raw speed) -> null, so the screen
 *    never shows a frozen number. The parked/driving state is kept: in a tunnel the car is still driving,
 *    and flipping to the park screen mid-tunnel would be wrong.
 *  - no fix with speed for more than [HAL_ESKIME_NS] (60 s): state -> null (unknown). After a minute
 *    without GPS the old state is a guess (long tunnel, or parked in an underground garage).
 *    null is "unknown", not PARK; the screens decide what to show for it.
 *
 * Starts only when the location permission is granted; without it, or without a GPS provider,
 * nothing crashes and the speed stays null. The listener is registered on the main looper, so fixes
 * arrive on the main thread; a fix that arrives elsewhere anyway is posted there first.
 */
class HizKaynagi(
    private val lm: LocationManager?,
    private val izinVar: () -> Boolean,
    private val simdiNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    /** Smoother fed with every speed; null = none (tests of the 0.1 rules). */
    private val yumusatici: HizYumusatici? = null,
    /** The frame loop's clock (Choreographer frameTimeNanos is in this base). */
    private val kareNs: () -> Long = { System.nanoTime() },
) : LocationListener {

    val karar = HareketKarari()
    private val ana = Handler(Looper.getMainLooper())
    private var dinliyor = false
    /** Arrival time of the last fix with speed (our clock), or [YOK]. */
    private var sonOrnekNs = YOK

    val dinliyorMu: Boolean get() = dinliyor

    private val denetim = object : Runnable {
        override fun run() {
            eskimeyiDenetle()
            ana.postDelayed(this, DENETIM_MS)
        }
    }

    fun basla() {
        if (dinliyor) return
        val izin = izinVar()
        Teshis.konumIzni = izin
        if (!izin) { Teshis.gps = "izin yok"; return }
        val lm = lm
        if (lm == null) { Teshis.gps = "konum hizmeti yok"; return }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ARALIK_MS, MESAFE_M, this, Looper.getMainLooper())
            dinliyor = true
            Teshis.gps = if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) "dinliyor" else "dinliyor · GPS kapalı"
        } catch (t: SecurityException) {
            Teshis.gps = "izin reddedildi"
            Teshis.hata("GPS", t)
            return
        } catch (t: IllegalArgumentException) {
            Teshis.gps = "GPS sağlayıcısı yok"
            Teshis.hata("GPS", t)
            return
        } catch (t: RuntimeException) {
            Teshis.gps = "başlatılamadı"
            Teshis.hata("GPS", t)
            return
        }
        ana.removeCallbacks(denetim)
        denetim.run()
    }

    fun dur() {
        ana.removeCallbacks(denetim)
        if (dinliyor) {
            runCatching { lm?.removeUpdates(this) }.onFailure { Teshis.hata("GPS durdurma", it) }
            dinliyor = false
            Teshis.gps = "durdu"
        }
        // The pause is not a fix interval.
        Teshis.konumAraligi.kes()
        // Nobody sees the screen now, and the next value must be fresh: drop the speed.
        yumusatici?.sifirla()
        hizlariBirak()
    }

    private fun hizlariBirak() {
        val d = AracVerisi.durum
        if (d.hiz != null || d.hamHiz != null) {
            val ns = d.hamHizOrnekNs
            AracVerisi.guncelle { hiz(null); hamHiz(null, ns) }
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Arrival times are taken now, on the thread that got the fix; the work runs on main.
            val simdi = simdiNs()
            val kare = kareNs()
            ana.post { isle(loc, simdi, kare) }
            return
        }
        isle(loc, simdiNs(), kareNs())
    }

    private fun isle(loc: Location, simdi: Long, kare: Long) {
        Teshis.konumAraligi.ornek(simdi)
        val hizVar = loc.hasSpeed() && !loc.speed.isNaN() && !loc.speed.isInfinite()
        Teshis.konumGeldi(hizVar)
        // Without a speed the fix says nothing about moving: keep the state, do not advance the hysteresis.
        if (!hizVar) return
        val ms = loc.speed.coerceAtLeast(0f)
        val dogruluk = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.takeIf { it.isFinite() && it > 0f } else null
        Teshis.hizGeldi(ms, dogruluk)
        yumusatici?.ornek(ms, kare, dogruluk)
        val kmh = ms * 3.6
        sonOrnekNs = simdi
        // The fix's own clock, unless it is missing or more than 2 s away from ours (a bad stamp
        // would stretch or shrink the hysteresis durations).
        val damga = loc.elapsedRealtimeNanos
        val ns = if (damga > 0L && Math.abs(damga - simdi) <= SAPMA_NS) damga else simdi
        val hal = karar.ornek(kmh, ns)
        // Shown to 1 km/h: parked GPS noise (0.3, 0.7, 1.1 …) does not wake the screen every second.
        val gosterilen = Math.round(kmh).toDouble()
        AracVerisi.guncelle {
            hiz(gosterilen)
            hamHiz(ms, simdi)
            hareketHali(hal)
        }
    }

    /** Also called by tests. */
    internal fun eskimeyiDenetle() {
        val bos = if (sonOrnekNs == YOK) Long.MAX_VALUE else simdiNs() - sonOrnekNs
        val d = AracVerisi.durum
        if (bos > HIZ_ESKIME_NS && (d.hiz != null || d.hamHiz != null)) hizlariBirak()
        if (bos > HAL_ESKIME_NS && karar.hal != null) {
            karar.unut()
            AracVerisi.guncelle { hareketHali(null) }
        }
    }

    @Deprecated("Deprecated in API 29; required on API 27")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (dinliyor) Teshis.gps = "dinliyor"
    }

    override fun onProviderDisabled(provider: String) {
        if (dinliyor) Teshis.gps = "dinliyor · GPS kapalı"
    }

    companion object {
        /** minTime 0: as fast as the chip delivers (design §3; research decision 11: measured, not assumed). */
        const val ARALIK_MS = 0L
        /** minDistance 0: a parked car still gets fixes (its speed must reach 0). */
        const val MESAFE_M = 0f
        const val DENETIM_MS = 1000L
        const val HIZ_ESKIME_NS = 5_000_000_000L
        const val HAL_ESKIME_NS = 60_000_000_000L
        const val SAPMA_NS = 2_000_000_000L
        private const val YOK = Long.MIN_VALUE
    }
}
