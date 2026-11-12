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

    private fun konum(kmh: Double?, ns: Long = SystemClock.elapsedRealtimeNanos()) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 41.0; longitude = 29.0
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = ns
        if (kmh != null) speed = (kmh / 3.6).toFloat()
    }

    private fun kaynak(): HizKaynagi {
        shadowOf(uyg).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        return HizKaynagi(lm, { VeriBaglayici.izinVar(uyg) }).also { it.basla() }
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
}
