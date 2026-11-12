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
        Tema.saat = { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
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

    @Test
    fun gunduzGeceKurali() {
        assertTrue(!Tema.gunduzMu(6))
        assertTrue(Tema.gunduzMu(7))
        assertTrue(Tema.gunduzMu(18))
        assertTrue(!Tema.gunduzMu(19))
        assertTrue(!Tema.gunduzMu(0))

        var saat = 6
        Tema.saat = { saat }
        Tema.kontrolEt()
        assertEquals(Palet.GECE, Tema.palet)
        var duyulan: Palet? = null
        val d = dev.kokpit.anaekran.tema.TemaDinleyici { duyulan = it }
        Tema.dinle(d)
        saat = 7
        Tema.kontrolEt()
        assertEquals(Palet.GUNDUZ, duyulan)
        duyulan = null
        Tema.kontrolEt()
        assertNull(duyulan) // no change, no event
        Tema.birak(d)
    }
}
