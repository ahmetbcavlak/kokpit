package dev.kokpit.anaekran.hareket.surus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 60 ↔ 30 fps decision (research decision 8). */
class KareTemposuTest {
    private val vs = 16_666_667L

    @Test
    fun akiciysa60daKalir() {
        val k = KareTemposu(vs)
        var t = 0L
        repeat(600) { assertTrue(k.ciz(t)); t += vs }
        assertFalse(k.yariHiz)
    }

    @Test
    fun birSaniyeYavassa30aIner_herIkinciVsyncCizer() {
        val k = KareTemposu(vs)
        var t = 0L
        // every frame takes two VSYNCs (33 ms): uneven / slow
        repeat(30) { k.ciz(t); t += 2 * vs }
        assertFalse("1 sn dolmadan inmez", k.yariHiz)
        repeat(60) { k.ciz(t); t += 2 * vs }
        assertTrue(k.yariHiz)
        // now drawn frames are cheap again: callbacks every VSYNC, every second one is drawn
        var cizilen = 0
        repeat(60) { if (k.ciz(t)) cizilen++; t += vs }
        assertTrue("≈30 ($cizilen)", cizilen in 29..31)
    }

    @Test
    fun yaridaHizlaninca3SnSonraDoner_tekrarDuserseBekleme2Katina() {
        val k = KareTemposu(vs)
        var t = 0L
        repeat(120) { k.ciz(t); t += 2 * vs }
        assertTrue(k.yariHiz)
        repeat(150) { k.ciz(t); t += vs } // 2.5 s
        assertTrue("3 sn dolmadı", k.yariHiz)
        repeat(90) { k.ciz(t); t += vs }
        assertFalse("3 sn sonra 60'a döndü", k.yariHiz)
        // slow again right away: back to 30, next return needs 6 s
        repeat(120) { k.ciz(t); t += 2 * vs }
        assertTrue(k.yariHiz)
        repeat(60 * 5) { k.ciz(t); t += vs }
        assertTrue("6 sn dolmadı", k.yariHiz)
        repeat(60 * 3) { k.ciz(t); t += vs }
        assertFalse(k.yariHiz)
        assertEquals(2, k.yariyaInisSayisi)
    }

    @Test
    fun tekTakilmaIndirmez() {
        val k = KareTemposu(vs)
        var t = 0L
        repeat(60) { k.ciz(t); t += vs }
        t += 400_000_000L // a 400 ms GC pause
        repeat(120) { k.ciz(t); t += vs }
        assertFalse(k.yariHiz)
    }
}
