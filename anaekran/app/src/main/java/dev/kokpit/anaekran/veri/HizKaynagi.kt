package dev.kokpit.anaekran.veri

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * GPS speed (LocationManager.GPS_PROVIDER, 1 s / 0 m) and the parked/driving state.
 *
 * Staleness, checked once a second on the main thread:
 *  - no fix with speed for more than [HIZ_ESKIME_NS] (5 s): speed -> null, so the screen never shows a
 *    frozen number. The parked/driving state is kept: in a tunnel the car is still driving, and
 *    flipping to the park screen mid-tunnel would be wrong.
 *  - no fix with speed for more than [HAL_ESKIME_NS] (60 s): state -> null (unknown). After a minute
 *    without GPS the old state is a guess (long tunnel, or parked in an underground garage).
 *    null is "unknown", not PARK; the screens decide what to show for it.
 *
 * Starts only when the location permission is granted; without it, or without a GPS provider,
 * nothing crashes and the speed stays null. Main thread only.
 */
class HizKaynagi(
    private val lm: LocationManager?,
    private val izinVar: () -> Boolean,
    private val simdiNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
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
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, ARALIK_MS, 0f, this, Looper.getMainLooper())
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
        // Nobody sees the screen now, and the next value must be fresh: drop the speed.
        if (AracVerisi.durum.hiz != null) AracVerisi.guncelle { hiz(null) }
    }

    override fun onLocationChanged(loc: Location) {
        val hizVar = loc.hasSpeed() && !loc.speed.isNaN()
        Teshis.konumGeldi(hizVar)
        // Without a speed the fix says nothing about moving: keep the state, do not advance the hysteresis.
        if (!hizVar) return
        val kmh = loc.speed.coerceAtLeast(0f) * 3.6
        val simdi = simdiNs()
        sonOrnekNs = simdi
        val ns = loc.elapsedRealtimeNanos.takeIf { it > 0L } ?: simdi
        val hal = karar.ornek(kmh, ns)
        AracVerisi.guncelle {
            hiz(kmh)
            hareketHali(hal)
        }
    }

    /** Also called by tests. */
    internal fun eskimeyiDenetle() {
        val bos = if (sonOrnekNs == YOK) Long.MAX_VALUE else simdiNs() - sonOrnekNs
        val d = AracVerisi.durum
        if (bos > HIZ_ESKIME_NS && d.hiz != null) AracVerisi.guncelle { hiz(null) }
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
        const val ARALIK_MS = 1000L
        const val DENETIM_MS = 1000L
        const val HIZ_ESKIME_NS = 5_000_000_000L
        const val HAL_ESKIME_NS = 60_000_000_000L
        private const val YOK = Long.MIN_VALUE
    }
}
