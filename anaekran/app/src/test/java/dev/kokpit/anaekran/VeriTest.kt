package dev.kokpit.anaekran

import dev.kokpit.anaekran.arayuz.Bicim
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.veri.AracDurumu
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.KapiDurumu
import dev.kokpit.anaekran.veri.SahteVeri
import dev.kokpit.anaekran.veri.Sicaklik
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Data contract, number formatting and the day/night rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class VeriTest {

    @After
    fun temizle() {
        AracVerisi.sifirla()
        AracVerisi.saat = { android.os.SystemClock.elapsedRealtime() }
        Tema.simdi = { System.currentTimeMillis() }
        Tema.konum = { null }
        Tema.zorla(null)
    }

    @Test
    fun guncelleZamanDamgasiVurur_dinleyiciYalnizDegisinceDuyar() {
        AracVerisi.saat = { 1234L }
        val gelen = ArrayList<AracDurumu>()
        val d = dev.kokpit.anaekran.veri.AracDinleyici { gelen.add(it) }
        AracVerisi.dinle(d)

        AracVerisi.guncelle { hiz(62.0) }
        assertEquals(62.0, AracVerisi.durum.hiz!!, 0.0)
        assertEquals(1234L, AracVerisi.durum.hizZamani)
        assertEquals(0L, AracVerisi.durum.klimaZamani)
        assertNull(AracVerisi.durum.kapi) // unknown stays unknown

        AracVerisi.guncelle { hiz(62.0) } // same value, same time: no event
        assertEquals(1, gelen.size)

        AracVerisi.guncelle { kapi(KapiDurumu.BAGAJ_ACIK); disSicaklik(14.0) }
        assertEquals(2, gelen.size)
        assertEquals(KapiDurumu.BAGAJ_ACIK, gelen.last().kapi)

        AracVerisi.birak(d)
        AracVerisi.guncelle { hiz(10.0) }
        assertEquals(2, gelen.size)
    }

    @Test
    fun sahteSenaryolar() {
        assertEquals(KapiDurumu.KAPALI, SahteVeri.parkGece().kapi)
        assertEquals(62.0, SahteVeri.surus62().hiz!!, 0.0)
        assertEquals(KapiDurumu.KAPI_ACIK, SahteVeri.kapiAcik().kapi)
        assertEquals(KapiDurumu.BAGAJ_ACIK, SahteVeri.bagajAcik().kapi)
        val k = SahteVeri.klima215Oto()
        assertEquals(Sicaklik.Derece(21.5), k.klima?.sicaklik)
        assertEquals(true, k.klima?.oto)
        assertNull(k.disSicaklik)

        AracVerisi.saat = { 99L }
        SahteVeri.uygula(SahteVeri.klima215Oto())
        assertEquals(99L, AracVerisi.durum.klimaZamani)
        assertEquals(0L, AracVerisi.durum.hizZamani)
    }

    @Test
    fun bicim() {
        assertEquals("21,5", Bicim.sayi(21.5))
        assertEquals("14", Bicim.sayi(14.0))
        assertEquals("−3", Bicim.sayi(-3.0))
        assertEquals("22", Bicim.sayi(21.98))
        assertEquals("21,5°", Bicim.klimaDerece(Sicaklik.Derece(21.5)))
        assertEquals("LOW", Bicim.klimaDerece(Sicaklik.Low))
        assertEquals("HI", Bicim.klimaDerece(Sicaklik.Hi))
        assertEquals("—", Bicim.klimaDerece(null))
        assertEquals("—", Bicim.derece(null))
    }

    private fun an(g: Int, s: Int, dk: Int, ay: Int = 11) =
        java.time.ZonedDateTime.of(2026, ay, g, s, dk, 0, 0, java.time.ZoneOffset.ofHours(3)).toInstant().toEpochMilli()

    /**
     * K-88 (İ-4): day = sunrise ≤ now < sunset, no longer 07–19. Istanbul 13 Nov 2026: rise 07:49,
     * set 17:47 (USNO, see tema.GunesTest).
     */
    @Test
    fun gunduzGeceKurali() {
        Tema.konum = { null } // no GPS fix: Istanbul
        var simdi = an(13, 7, 30)
        Tema.simdi = { simdi }
        Tema.kontrolEt()
        assertEquals(Palet.GECE, Tema.palet) // the old rule said day at 07:30
        assertEquals(Tema.ISTANBUL, Tema.gecerliKonum())

        var duyulan: Palet? = null
        val d = dev.kokpit.anaekran.tema.TemaDinleyici { duyulan = it }
        Tema.dinle(d)
        simdi = an(13, 8, 0)
        Tema.kontrolEt()
        assertEquals(Palet.GUNDUZ, duyulan)
        duyulan = null
        Tema.kontrolEt()
        assertNull(duyulan) // no change, no event

        simdi = an(13, 17, 40)
        Tema.kontrolEt()
        assertNull(duyulan) // still day
        simdi = an(13, 17, 55)
        Tema.kontrolEt()
        assertEquals(Palet.GECE, duyulan) // after sunset; the old rule kept day until 19:00

        // Summer: 20:30 in June is still day in Istanbul.
        simdi = an(21, 20, 30, ay = 6)
        Tema.kontrolEt()
        assertEquals(Palet.GUNDUZ, Tema.palet)
        Tema.birak(d)
    }

    @Test
    fun temaSonGpsKonumunuKullanir() {
        // 13 Nov 17:20: still day in Istanbul (set 17:47), already night in Van (≈38.5 N 43.4 E, set ≈ 16:50).
        Tema.simdi = { an(13, 17, 20) }
        Tema.konum = { null }
        Tema.kontrolEt()
        assertEquals(Palet.GUNDUZ, Tema.palet)
        Tema.konum = { Tema.Konum(38.50, 43.38) }
        Tema.kontrolEt()
        assertEquals(Palet.GECE, Tema.palet)

        // Unusable fixes and a failing source fall back to Istanbul, never crash.
        for (k in listOf(Tema.Konum(0.0, 0.0), Tema.Konum(Double.NaN, 29.0), Tema.Konum(95.0, 29.0), Tema.Konum(41.0, 200.0))) {
            Tema.konum = { k }
            assertEquals(Tema.ISTANBUL, Tema.gecerliKonum())
        }
        Tema.konum = { throw SecurityException("izin yok") }
        assertEquals(Tema.ISTANBUL, Tema.gecerliKonum())
        Tema.kontrolEt()
        assertEquals(Palet.GUNDUZ, Tema.palet)
    }

    @Test
    fun sonKonumIzinsizVeIzinliOkur() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        // No permission: null, and the location service is not even asked.
        assertNull(dev.kokpit.anaekran.tema.SonKonum.oku(app))
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(dev.kokpit.anaekran.tema.SonKonum.oku(app)) // no fix yet
        val lm = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val l = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply { latitude = 40.99; longitude = 29.03 }
        org.robolectric.Shadows.shadowOf(lm).setLastKnownLocation(android.location.LocationManager.GPS_PROVIDER, l)
        assertEquals(Tema.Konum(40.99, 29.03), dev.kokpit.anaekran.tema.SonKonum.oku(app))
    }
}
