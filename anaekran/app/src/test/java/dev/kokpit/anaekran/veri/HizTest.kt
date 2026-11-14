package dev.kokpit.anaekran.veri

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** GPS hysteresis and staleness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class HizTest {

    private val S = 1_000_000_000L

    @After
    fun temizle() {
        AracVerisi.sifirla()
        Teshis.sifirla()
        Hiz.yumusatici.sifirla()
    }

    // --- Pure hysteresis (decision 9), samples once a second ---

    /** Feeds [kmh] from second [bas] to [son] inclusive; returns the state after the last one. */
    private fun HareketKarari.besle(kmh: Double, bas: Int, son: Int): HareketHali? {
        var h: HareketHali? = null
        for (t in bas..son) h = ornek(kmh, t * S)
        return h
    }

    private fun parktaBasla(): HareketKarari = HareketKarari().also {
        assertEquals(HareketHali.PARK, it.besle(0.0, 0, 5))
    }

    @Test
    fun suruseGecis_ikiSaniye() {
        val k = parktaBasla()
        assertEquals(HareketHali.PARK, k.ornek(9.0, 6 * S))
        assertEquals(HareketHali.PARK, k.ornek(11.0, 7 * S))
        assertEquals(HareketHali.PARK, k.ornek(11.0, 8 * S)) // 1 s above 10: stays parked
        assertEquals(HareketHali.SURUS, k.ornek(11.0, 9 * S)) // 2 s: driving
    }

    @Test
    fun parkaGecis_besSaniye() {
        val k = parktaBasla()
        assertEquals(HareketHali.SURUS, k.besle(30.0, 6, 8))
        assertEquals(HareketHali.SURUS, k.besle(4.0, 9, 13)) // 4 s below 5: still driving
        assertEquals(HareketHali.PARK, k.ornek(4.0, 14 * S)) // 5 s: parked
    }

    @Test
    fun araBantKosuyuKirar() {
        val k = parktaBasla()
        k.ornek(11.0, 6 * S)
        k.ornek(7.0, 7 * S) // 5–10 band: run restarts
        k.ornek(11.0, 8 * S)
        assertEquals(HareketHali.PARK, k.ornek(11.0, 9 * S))
        assertEquals(HareketHali.SURUS, k.ornek(11.0, 10 * S))
    }

    @Test
    fun baslangictaBilinmiyor_bosluklarKosuyuKirar() {
        val k = HareketKarari()
        assertNull(k.ornek(0.0, 0))
        assertNull(k.besle(0.0, 1, 4))
        assertEquals(HareketHali.PARK, k.ornek(0.0, 5 * S))
        k.ornek(20.0, 6 * S)
        // 7 s without samples (tunnel): the run does not carry over.
        assertEquals(HareketHali.PARK, k.ornek(20.0, 13 * S))
        assertEquals(HareketHali.PARK, k.ornek(20.0, 14 * S))
        assertEquals(HareketHali.SURUS, k.ornek(20.0, 15 * S))
        k.unut()
        assertNull(k.hal)
    }

    // --- HizKaynagi with Robolectric's LocationManager ---

    private val uyg get() = RuntimeEnvironment.getApplication()
    private val lm get() = uyg.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun bekle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun konum(kmh: Double?, ns: Long = SystemClock.elapsedRealtimeNanos(), dogruluk: Float? = null) =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 41.0; longitude = 29.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = ns
            if (kmh != null) speed = (kmh / 3.6).toFloat()
            if (dogruluk != null) speedAccuracyMetersPerSecond = dogruluk
        }

    private fun kaynak(y: HizYumusatici? = null): HizKaynagi {
        shadowOf(uyg).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        // Frame clock = Robolectric's elapsed clock, so tests can check the smoother's sample times.
        return HizKaynagi(lm, { VeriBaglayici.izinVar(uyg) }, yumusatici = y, kareNs = { SystemClock.elapsedRealtimeNanos() })
            .also { it.basla() }
    }

    private fun fix(l: Location) {
        shadowOf(lm).simulateLocation(l)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** One fix per second, like the real 1 s request. */
    private fun sur(kmh: Double?, saniye: Int) {
        repeat(saniye) {
            bekle(1000)
            shadowOf(lm).simulateLocation(konum(kmh))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun gpsHiziVeHisterezis() {
        val h = kaynak()
        assertTrue(h.dinliyorMu)
        assertEquals(1, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size)

        sur(0.0, 6)
        assertEquals(0.0, AracVerisi.durum.hiz!!, 0.001)
        assertEquals(HareketHali.PARK, AracVerisi.durum.hareketHali)

        sur(9.0, 1)
        sur(11.0, 2)
        assertEquals(11.0, AracVerisi.durum.hiz!!, 0.01)
        assertEquals(HareketHali.PARK, AracVerisi.durum.hareketHali) // 1 s above 10
        sur(11.0, 1)
        assertEquals(HareketHali.SURUS, AracVerisi.durum.hareketHali) // 2 s

        // A fix without speed changes nothing.
        sur(null, 1)
        assertEquals(11.0, AracVerisi.durum.hiz!!, 0.01)
        assertEquals(1, Teshis.hizsizKonumSayisi)

        h.dur()
    }

    @Test
    fun gpsSessizligi_hizNullHalKalir() {
        val h = kaynak()
        sur(50.0, 4)
        assertEquals(HareketHali.SURUS, AracVerisi.durum.hareketHali)

        bekle(4000) // 4 s quiet: the speed is still shown
        assertEquals(50.0, AracVerisi.durum.hiz!!, 0.01)
        bekle(2000) // past 5 s: no frozen number
        assertNull(AracVerisi.durum.hiz)
        assertEquals(HareketHali.SURUS, AracVerisi.durum.hareketHali) // tunnel: not parked

        bekle(60_000) // a minute without GPS: the state is no longer known
        assertNull(AracVerisi.durum.hareketHali)

        // GPS back: the hysteresis starts from scratch.
        sur(50.0, 2)
        assertNull(AracVerisi.durum.hareketHali)
        sur(50.0, 1)
        assertEquals(HareketHali.SURUS, AracVerisi.durum.hareketHali)
        h.dur()
    }

    @Test
    fun durHiziBirakir_gpsKaydiniKaldirir() {
        val h = kaynak()
        sur(30.0, 1)
        h.dur()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(h.dinliyorMu)
        assertNull(AracVerisi.durum.hiz)
        assertEquals(0, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size)
    }

    @Test
    fun izinYok_cokmezHizNull() {
        shadowOf(uyg).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val h = HizKaynagi(lm, { VeriBaglayici.izinVar(uyg) })
        h.basla()
        assertFalse(h.dinliyorMu)
        assertEquals("izin yok", Teshis.gps)
        assertEquals(false, Teshis.konumIzni)
        assertNull(AracVerisi.durum.hiz)
        h.dur() // harmless when never started

        // No LocationManager at all.
        val y = HizKaynagi(null, { true })
        y.basla()
        assertFalse(y.dinliyorMu)
    }

    // --- 0.2: request, raw stream, smoother feed, measured intervals ---

    @Test
    fun konumIstegi_minTime0_minDistance0_gpsSaglayicisi() {
        kaynak()
        val istekler = shadowOf(lm).getLegacyLocationRequests(LocationManager.GPS_PROVIDER)
        assertEquals(1, istekler.size)
        assertEquals(0L, istekler[0].intervalMillis)
        assertEquals(0f, istekler[0].minUpdateDistanceMeters, 0f)
        assertEquals(0, shadowOf(lm).getLegacyLocationRequests(LocationManager.NETWORK_PROVIDER).size)
    }

    @Test
    fun hamHizVeOrnekZamani_herOrnekte_yumusaticiyaGider() {
        val y = HizYumusatici()
        val h = kaynak(y)
        var uyanma = 0
        val d = AracDinleyici { uyanma++ }
        AracVerisi.dinle(d)
        try {
            bekle(1000)
            val t1 = SystemClock.elapsedRealtimeNanos()
            fix(konum(36.4))
            assertEquals(36.0, AracVerisi.durum.hiz!!, 0.0) // rounded as in 0.1
            assertEquals((36.4 / 3.6).toFloat(), AracVerisi.durum.hamHiz!!, 1e-5f) // not rounded, m/s
            assertEquals(t1, AracVerisi.durum.hamHizOrnekNs) // arrival time
            assertEquals((36.4 / 3.6).toFloat(), y.sonHam!!, 1e-5f)
            assertEquals(1, uyanma)

            // Same speed again: the sample time moves, so listeners wake (≈ 1/s, accepted).
            bekle(1000)
            val t2 = SystemClock.elapsedRealtimeNanos()
            fix(konum(36.4))
            assertEquals(t2, AracVerisi.durum.hamHizOrnekNs)
            assertEquals(2, uyanma)
            // The smoother got the arrival on the frame clock: 2 s later it is past the prediction.
            assertEquals(y.sonHam!!.toDouble(), y.hedef(t2 + 2_000_000_000L), 1e-6)

            // 5 s quiet: raw speed goes with the rounded one.
            bekle(6000)
            assertNull(AracVerisi.durum.hiz)
            assertNull(AracVerisi.durum.hamHiz)
            assertEquals(t2, AracVerisi.durum.hamHizOrnekNs)
        } finally {
            AracVerisi.birak(d)
            h.dur()
        }
        // GPS stopped: the smoother forgets (the next value must be fresh).
        assertNull(y.sonHam)
    }

    @Test
    fun hizYalnizGetSpeedden_hasSpeedYoksaHicbirSeyGuncellenmez() {
        val y = HizYumusatici()
        val h = kaynak(y)
        bekle(1000)
        fix(konum(20.0))
        val once = AracVerisi.durum
        // A fix without speed, far away from the last one: no speed is derived from positions.
        bekle(1000)
        fix(konum(null).apply { latitude = 41.01; longitude = 29.01 })
        assertEquals(once, AracVerisi.durum)
        assertEquals((20.0 / 3.6).toFloat(), y.sonHam!!, 1e-5f)
        assertEquals(1, Teshis.hizsizKonumSayisi)
        // … but it still counts as a fix for the interval measurement.
        assertEquals(1, Teshis.konumAraligi.sayi)
        h.dur()
    }

    @Test
    fun anaIsParcacigiDisindanGelenKonum_anaya_gonderilir() {
        val y = HizYumusatici()
        val h = kaynak(y)
        bekle(1000)
        val t = Thread { h.onLocationChanged(konum(54.0)) }
        t.start(); t.join()
        assertNull(y.sonHam) // not touched off the main thread
        assertNull(AracVerisi.durum.hamHiz)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(15f, y.sonHam!!, 1e-5f)
        assertEquals(54.0, AracVerisi.durum.hiz!!, 0.0)
        h.dur()
    }

    @Test
    fun hizDogrulugu_teshiseVeSifirEsigine() {
        val y = Hiz.yumusatici // the diagnostics line reads the shared one
        val h = kaynak(y)
        bekle(1000); fix(konum(18.0))
        assertNull(Teshis.hizDogrulugu)
        // 0.9 m/s would not be zero mode with the 0.5 threshold; with accuracy 1.2 the threshold is 1.2.
        bekle(1000); fix(konum(0.9 * 3.6, dogruluk = 1.2f))
        bekle(1000); fix(konum(0.9 * 3.6, dogruluk = 1.2f))
        assertEquals(1.2f, Teshis.hizDogrulugu!!, 1e-6f)
        assertEquals(2, Teshis.dogrulukluKonumSayisi)
        assertTrue(y.sifirda)
        assertTrue(Teshis.satirlar().any { it.startsWith("GPS hız:") && it.contains("doğruluk 1,20 m/sn") && it.contains("sıfır kipi") })
        h.dur()
    }

    @Test
    fun ornekAraligiOlculur_teshiseYazilir() {
        val h = kaynak()
        for (i in 0..4) { bekle(500); fix(konum(30.0)) } // 2 Hz
        for (ms in longArrayOf(1000, 1000, 1000)) { bekle(ms); fix(konum(30.0)) } // 1 Hz
        for (ms in longArrayOf(300, 1700, 900)) { bekle(ms); fix(konum(30.0)) } // irregular
        val a = Teshis.konumAraligi
        assertEquals(10, a.sayi)
        assertEquals(300_000_000L, a.enKisaNs)
        assertEquals(1_700_000_000L, a.enUzunNs)
        assertEquals(790_000_000L, a.ortalamaNs()) // (4·0.5 + 3·1 + 0.3 + 1.7 + 0.9) / 10
        assertEquals(listOf(500L, 500, 500, 1000, 1000, 1000, 300, 1700, 900).map { it * 1_000_000 }, a.son().takeLast(9))
        assertTrue(Teshis.satirlar().contains(
            "GPS aralığı: ort 0,79 sn · en kısa 0,30 · en uzun 1,70 · 10 aralık · son: 0,50 0,50 0,50 0,50 1,00 1,00 1,00 0,30 1,70 0,90",
        ))
        // A pause (GPS stopped and started again) is not an interval.
        h.dur()
        bekle(30_000)
        h.basla()
        bekle(1000); fix(konum(30.0))
        assertEquals(10, a.sayi)
        bekle(1000); fix(konum(30.0))
        assertEquals(11, a.sayi)
        assertEquals(1_700_000_000L, a.enUzunNs)
        h.dur()
    }
}
