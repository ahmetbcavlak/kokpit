package dev.kokpit.anaekran.hareket

import dev.kokpit.anaekran.hareket.KareHiziHesabi.Sonuc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FPS arithmetic with fake timestamps (no Android). */
class KareHiziHesabiTest {

    private val vs = 16_666_667L // 60 Hz VSYNC
    private val ms = 1_000_000L

    /** Runs [n] VSYNCs from [t0]; draws on every [her]-th one. Returns the window results. */
    private fun KareHiziHesabi.calistir(t0: Long, n: Int, her: Int = 1, cizimVar: (Int) -> Boolean = { true }): List<Sonuc> {
        val sonuclar = ArrayList<Sonuc>()
        for (k in 0 until n) {
            val t = t0 + k * vs
            if (k % her == 0 && cizimVar(k)) cizildi(t + 5 * ms)
            vsync(t)?.let { sonuclar += it }
        }
        return sonuclar
    }

    @Test
    fun altmisKareCizilinceAltmis() {
        val h = KareHiziHesabi()
        val s = h.calistir(0, 31)
        assertEquals(Sonuc.Olcum(60, 17), s.single())
    }

    @Test
    fun otuzFpsKlipOtuzOkunur() {
        val h = KareHiziHesabi()
        val s = h.calistir(0, 31, her = 2)
        assertEquals(30, (s.single() as Sonuc.Olcum).fps)
    }

    @Test
    fun cizimYoksaVsyncAltmisOlsaBileBos() {
        val h = KareHiziHesabi()
        val s = h.calistir(0, 31) { false }
        assertEquals(listOf(Sonuc.Bos), s)
    }

    @Test
    fun kisaSolmaPencereyiDoldurmasaDaAltmis() {
        // 350 ms of 60 fps drawing (a fade), then still, in one 500 ms window.
        val h = KareHiziHesabi()
        val s = h.calistir(0, 31) { it < 21 }
        assertEquals(60, (s.single() as Sonuc.Olcum).fps)
    }

    @Test
    fun takilmaEnUzunKareOlarakGorunur() {
        val h = KareHiziHesabi()
        var t = 0L
        h.vsync(t)
        repeat(10) { t += vs; h.cizildi(t); h.vsync(t) }
        t += 120 * ms // main thread blocked 120 ms
        h.cizildi(t)
        h.vsync(t)
        var son: Sonuc? = null
        while (son == null) { t += vs; h.cizildi(t); son = h.vsync(t) }
        assertEquals(120, (son as Sonuc.Olcum).enUzunMs)
        // Gaps up to 300 ms count: the stall shows in the fps too.
        assertTrue("fps ${son.fps}", son.fps in 40..55)
    }

    @Test
    fun hareketDisindaTekTukCizimSayilmaz() {
        // Two isolated redraws (clock, data) outside any known motion: not "motion".
        val h = KareHiziHesabi()
        val s = h.calistir(0, 31) { it == 3 || it == 9 }
        assertEquals(listOf(Sonuc.Bos), s)
    }

    @Test
    fun hareketIcindeSeyrekCizimDeSayilir() {
        // The same sparse draws during a known motion are a real (bad) frame rate.
        val h = KareHiziHesabi()
        h.vsync(0)
        h.hareketBasladi(0)
        val s = h.calistir(vs, 30) { it == 3 || it == 9 }
        assertEquals(4, (s.single() as Sonuc.Olcum).fps)
    }

    @Test
    fun sekizFpsTakilanKlipSekizOkunur() {
        // A weak decoder: the clip delivers a frame every 125 ms; the main thread is free.
        val h = KareHiziHesabi()
        h.vsync(0)
        h.hareketBasladi(0)
        val sonuclar = ArrayList<Sonuc>()
        var t = 0L
        var sonrakiKare = 125 * ms
        while (t < 1000 * ms) {
            t += vs
            if (t >= sonrakiKare) { h.cizildi(t); sonrakiKare += 125 * ms }
            h.vsync(t)?.let { sonuclar += it }
        }
        h.hareketBitti(t)
        assertEquals(2, sonuclar.size)
        for (o in sonuclar) {
            o as Sonuc.Olcum
            assertEquals(8, o.fps)
            assertTrue("en uzun kare ≈125 ms, ${o.enUzunMs}", o.enUzunMs in 120..140)
        }
    }

    @Test
    fun donmusKlipSifirOkunur() {
        val h = KareHiziHesabi()
        h.vsync(0)
        h.hareketBasladi(0)
        val s = h.calistir(vs, 30) { false }
        val o = s.single() as Sonuc.Olcum
        assertEquals(0, o.fps)
        assertTrue(o.enUzunMs >= 480)
    }

    @Test
    fun hareketPencereninBirKismindaysa_yalnizOAraliktanHesaplanir() {
        // 200 ms of 60 fps motion, then still; the window is 500 ms.
        val h = KareHiziHesabi()
        h.vsync(0)
        h.hareketBasladi(0)
        var t = 0L
        var s: Sonuc? = null
        for (k in 1..30) {
            t = k * vs
            if (k <= 12) h.cizildi(t)
            if (k == 12) h.hareketBitti(t)
            h.vsync(t)?.let { s = it }
        }
        assertEquals(60, (s as Sonuc.Olcum).fps)
    }

    @Test
    fun pencereKapaninca_bekleme_bosluguSayilmaz() {
        val h = KareHiziHesabi()
        h.calistir(0, 31)
        h.pencereyiKapat()
        // Loop restarts 10 s later: no 10 s "frame", no gap across the pause in the fps.
        val s = h.calistir(10_000 * ms, 31)
        assertEquals(Sonuc.Olcum(60, 17), s.single())
    }

    @Test
    fun ilkVsyncSonucVermez() {
        assertNull(KareHiziHesabi().vsync(123))
        assertTrue(true)
    }
}
