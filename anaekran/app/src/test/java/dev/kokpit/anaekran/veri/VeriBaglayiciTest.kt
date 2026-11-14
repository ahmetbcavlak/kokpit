package dev.kokpit.anaekran.veri

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** VeriBaglayici's life cycle and the listener rule ("only a value change wakes the screen"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class VeriBaglayiciTest {

    private val uyg get() = RuntimeEnvironment.getApplication()
    private val lm get() = uyg.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @After
    fun temizle() {
        AracVerisi.sifirla()
        Teshis.sifirla()
        Hiz.yumusatici.sifirla()
    }

    private fun bosalt() = shadowOf(Looper.getMainLooper()).idle()
    private fun bekle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun klimaAlicilari() = shadowOf(uyg).registeredReceivers.count {
        it.intentFilter.hasAction(KlimaCozucu.EYLEM_KLIMA) &&
            it.intentFilter.hasAction(KlimaCozucu.EYLEM_DIS_SICAKLIK) &&
            it.intentFilter.hasAction(KlimaCozucu.EYLEM_DIS_SICAKLIK_2)
    }

    @Test
    fun izinIstegi_aliciUygulamaOmrunce_gpsBaslaDur() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val b = VeriBaglayici()
        b.hazirla(a)

        // No permission yet: asked (FINE with COARSE), nothing crashes. The receiver is already on.
        val istek = shadowOf(a).lastRequestedPermission
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            istek.requestedPermissions.toList(),
        )
        assertEquals(VeriBaglayici.IZIN_ISTEGI, istek.requestCode)
        assertEquals(1, klimaAlicilari())

        b.basla()
        assertFalse(b.hizKaynagi!!.dinliyorMu)
        assertEquals("izin yok", Teshis.gps)
        b.basla() // twice: still one receiver
        assertEquals(1, klimaAlicilari())

        uyg.sendBroadcast(Intent(KlimaCozucu.EYLEM_KLIMA).putExtra(KlimaCozucu.SOL_SICAKLIK, 20))
        bosalt()
        assertEquals(Sicaklik.Derece(20.0), AracVerisi.durum.klima?.sicaklik)

        // Paused (another app in front): the climate receiver stays, so the value is current on return.
        b.dur()
        assertEquals(1, klimaAlicilari())
        uyg.sendBroadcast(Intent(KlimaCozucu.EYLEM_KLIMA).putExtra(KlimaCozucu.SOL_SICAKLIK, 22))
        bosalt()
        assertEquals(Sicaklik.Derece(22.0), AracVerisi.durum.klima?.sicaklik)

        // Dialog answered "allow" → onResume → basla picks it up.
        shadowOf(uyg).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        b.basla()
        assertTrue(b.hizKaynagi!!.dinliyorMu)
        assertEquals(1, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size)
        // Every GPS speed also reaches the shared smoother; stopping GPS resets it.
        shadowOf(lm).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
            speed = 12.5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        bosalt()
        assertEquals(12.5f, Hiz.yumusatici.sonHam!!, 0f)
        assertEquals(12.5f, AracVerisi.durum.hamHiz!!, 0f)
        b.dur()
        assertNull(Hiz.yumusatici.sonHam)
        assertEquals(0, shadowOf(lm).getLocationRequests(LocationManager.GPS_PROVIDER).size)

        // Destroyed: receiver gone, broadcasts no longer arrive.
        b.birak()
        assertEquals(0, klimaAlicilari())
        uyg.sendBroadcast(Intent(KlimaCozucu.EYLEM_KLIMA).putExtra(KlimaCozucu.SOL_SICAKLIK, 25))
        bosalt()
        assertEquals(Sicaklik.Derece(22.0), AracVerisi.durum.klima?.sicaklik)
        assertNull(b.hizKaynagi)
    }

    @Test
    fun izinRedSonrasiYenidenSorulmaz() {
        val a1 = Robolectric.buildActivity(Activity::class.java).setup().get()
        val b1 = VeriBaglayici()
        b1.hazirla(a1)
        assertTrue(shadowOf(a1).lastRequestedPermission != null)
        b1.birak()

        // Denied; the Activity is created again (cold start, ignition): no second dialog, only the hint.
        shadowOf(uyg).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val a2 = Robolectric.buildActivity(Activity::class.java).setup().get()
        val b2 = VeriBaglayici()
        b2.hazirla(a2)
        assertNull(shadowOf(a2).lastRequestedPermission)
        assertEquals(false, Teshis.konumIzni)
        b2.basla()
        assertNull(AracVerisi.durum.hiz)
        b2.birak()
    }

    @Test
    fun yapiskanYayinIlkDeger_birKezSayilir() {
        shadowOf(uyg).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        @Suppress("DEPRECATION")
        uyg.sendStickyBroadcast(Intent(KlimaCozucu.EYLEM_KLIMA).putExtra(KlimaCozucu.SOL_SICAKLIK, "LO").putExtra(KlimaCozucu.FAN, 2))
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val b = VeriBaglayici()
        b.hazirla(a)
        assertNull(shadowOf(a).lastRequestedPermission) // already granted: not asked
        bosalt()
        assertTrue(Teshis.yapiskanGeldi)
        assertEquals(Sicaklik.Low, AracVerisi.durum.klima?.sicaklik)
        assertEquals(2, AracVerisi.durum.klima?.fan)
        assertEquals(1, Teshis.klimaYayinSayisi)
        b.birak()
    }

    @Test
    fun dinleyiciYalnizDegerDegisinceUyanir_gercekSaat() {
        // Real clock (no fixed saat): the update times move, the values do not.
        var sayi = 0
        val d = AracDinleyici { sayi++ }
        AracVerisi.dinle(d)
        try {
            var oncekiZaman = -1L
            repeat(5) {
                bekle(1000)
                AracVerisi.guncelle { hiz(50.0) }
                assertTrue(AracVerisi.durum.hizZamani > oncekiZaman)
                oncekiZaman = AracVerisi.durum.hizZamani
            }
            assertEquals(1, sayi)
            assertEquals(50.0, AracVerisi.durum.hiz!!, 0.0)

            // From another thread: runs later on main, with the value given at the call.
            sayi = 0
            val v = 51.0
            val t = Thread { AracVerisi.guncelle { hiz(v) } }
            t.start(); t.join()
            assertEquals(50.0, AracVerisi.durum.hiz!!, 0.0)
            bosalt()
            assertEquals(51.0, AracVerisi.durum.hiz!!, 0.0)
            assertEquals(1, sayi)
        } finally {
            AracVerisi.birak(d)
        }
    }

    @Test
    fun ayniGpsHiziBesKez_yuvarlakHizAyni_herOrnekUyandirir_eskimeDegerOlarakYazilir() {
        shadowOf(uyg).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val h = HizKaynagi(lm, { true })
        h.basla()
        fun fix(kmh: Double) {
            bekle(1000)
            shadowOf(lm).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                speed = (kmh / 3.6).toFloat()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                time = System.currentTimeMillis()
            })
            bosalt()
        }
        repeat(3) { fix(36.0) }
        assertEquals(HareketHali.SURUS, AracVerisi.durum.hareketHali)

        var sayi = 0
        val d = AracDinleyici { sayi++ }
        AracVerisi.dinle(d)
        try {
            val zaman = AracVerisi.durum.hizZamani
            repeat(5) { fix(36.0) }
            // 0.2: the raw stream carries each fix's arrival time as a value, so every fix wakes the
            // listeners (accepted: about once a second while driving). The rounded speed is unchanged.
            assertEquals(5, sayi)
            assertEquals(36.0, AracVerisi.durum.hiz!!, 0.0)
            assertTrue(AracVerisi.durum.hizZamani > zaman)
            bekle(6000) // GPS quiet > 5 s: speed → null, written as a value change by the timer
            assertNull(AracVerisi.durum.hiz)
            assertNull(AracVerisi.durum.hamHiz)
            assertEquals(6, sayi)
        } finally {
            AracVerisi.birak(d)
            h.dur()
        }
    }
}
