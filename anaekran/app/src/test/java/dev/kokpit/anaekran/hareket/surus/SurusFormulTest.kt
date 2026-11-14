package dev.kokpit.anaekran.hareket.surus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Design pack 0.2 §2 formulas (pure, JVM). */
class SurusFormulTest {

    private fun ms(kmh: Float) = kmh / 3.6f

    @Test
    fun tekerAcisalHizi_vBolu0316() {
        assertEquals(10f / 0.316f, SurusFormul.gercekAcisalHiz(10f), 1e-4f)
        // up to 30 km/h the shown speed is the real one
        for (k in listOf(1f, 5f, 12f, 20f, 29.9f)) { // below 30 km/h: the real speed
            assertEquals("$k km/sa", ms(k) / 0.316f, SurusFormul.gosterilenAcisalHiz(ms(k)), 1e-4f)
        }
    }

    @Test
    fun bulaniklikEsikleri() {
        assertEquals(0f, SurusFormul.bulaniklik(0f))
        assertEquals(0f, SurusFormul.bulaniklik(12f))
        assertEquals(0.5f, SurusFormul.bulaniklik(21f), 1e-5f)
        assertEquals(1f, SurusFormul.bulaniklik(30f), 1e-5f)
        assertEquals(1.5f, SurusFormul.bulaniklik(37.5f), 1e-5f)
        assertEquals(2f, SurusFormul.bulaniklik(45f))
        assertEquals(2f, SurusFormul.bulaniklik(130f))
        // continuous at the thresholds
        assertEquals(SurusFormul.bulaniklik(29.999f), SurusFormul.bulaniklik(30f), 1e-3f)
        assertEquals(SurusFormul.bulaniklik(44.999f), SurusFormul.bulaniklik(45f), 1e-3f)
    }

    @Test
    fun bulanikSprite_ucteBirVeEnAz09SnTur() {
        val enCok = (2 * PI / 0.9).toFloat()
        // from 30 km/h (sprite 1 fully shown, b ≥ 1) the design's cap
        for (k in listOf(30f, 38f, 45f, 50f, 90f, 130f, 180f)) {
            val v = ms(k)
            val beklenen = minOf(v / 0.316f / 3f, enCok)
            assertEquals("$k km/sa", beklenen, SurusFormul.gosterilenAcisalHiz(v), 1e-4f)
            assertTrue("$k: en çok gerçek hızın 1/3'ü", SurusFormul.gosterilenAcisalHiz(v) <= v / 0.316f / 3f + 1e-4f)
            val turSn = 2 * PI / SurusFormul.gosterilenAcisalHiz(v)
            assertTrue("$k: en az 0,9 sn/tur ($turSn)", turSn >= 0.9 - 1e-5)
            val kareDerece = Math.toDegrees(SurusFormul.gosterilenAcisalHiz(v) / 30.0)
            assertTrue("$k: 30 fps'te karede < 36° ($kareDerece)", kareDerece < 36.0)
        }
    }

    @Test
    fun seritler_1m142px() {
        assertEquals(142f, SurusFormul.yakinSeritHizi(1f), 1e-4f)
        assertEquals(142f * 0.8f, SurusFormul.uzakSeritHizi(1f), 1e-4f)
        assertEquals(142f * 0.04f, SurusFormul.isikHizi(1f), 1e-5f)
        assertEquals(1278f, SurusFormul.seritPeriyodu(1f), 1e-3f)
        assertEquals(426f, SurusFormul.seritDolu(1f), 1e-3f)
        assertEquals(1022.4f, SurusFormul.seritPeriyodu(0.8f), 1e-3f)
        assertEquals(340.8f, SurusFormul.seritDolu(0.8f), 1e-3f)
    }

    @Test
    fun kameraOlcegi() {
        assertEquals(1f, SurusFormul.kameraHedefi(0f))
        assertEquals(1f - 0.035f * 50f / 130f, SurusFormul.kameraHedefi(50f), 1e-6f)
        assertEquals(0.965f, SurusFormul.kameraHedefi(130f), 1e-6f)
        assertEquals(0.965f, SurusFormul.kameraHedefi(200f), 1e-6f)
    }

    @Test
    fun suspansiyonGenligi() {
        assertEquals(0f, SurusFormul.suspansiyonGenligi(0f))
        assertEquals(0.45f, SurusFormul.suspansiyonGenligi(30f), 1e-6f)
        assertEquals(0.9f, SurusFormul.suspansiyonGenligi(60f), 1e-6f)
        assertEquals(0.9f, SurusFormul.suspansiyonGenligi(130f), 1e-6f)
        // 0 → −A → 0 over 2 × 1.1 s
        assertEquals(0f, SurusFormul.suspansiyon(0.9f, 0.0), 1e-6f)
        assertEquals(-0.9f, SurusFormul.suspansiyon(0.9f, PI), 1e-6f)
    }

    @Test
    fun izOpakligi() {
        assertEquals(0f, SurusFormul.izOpakligi(0f))
        assertEquals(0.5f, SurusFormul.izOpakligi(40f), 1e-6f)
        assertEquals(1f, SurusFormul.izOpakligi(80f))
        assertEquals(1f, SurusFormul.izOpakligi(130f))
    }

    @Test
    fun lamba_her35mdeBir_ilk18Yuzde() {
        assertEquals(-0.40f, SurusFormul.lambaKonumu(0.0), 1e-6f)
        assertEquals(0.35f, SurusFormul.lambaKonumu(35.0 * 0.09), 1e-5f)
        assertTrue("süpürme bitti", SurusFormul.lambaKonumu(35.0 * 0.18).isNaN())
        assertTrue(SurusFormul.lambaKonumu(20.0).isNaN())
        assertFalse("35 m sonra yeniden", SurusFormul.lambaKonumu(35.0).isNaN())
        assertEquals(-0.40f, SurusFormul.lambaKonumu(70.0), 1e-5f)
        // the band covers 6.3 m of every 35 m
        var gorunen = 0
        for (i in 0 until 3500) if (!SurusFormul.lambaKonumu(i / 100.0).isNaN()) gorunen++
        assertEquals(630, gorunen)
    }

    @Test
    fun isikOpakligi() {
        assertEquals(0.6f, SurusFormul.isikOpakligi(0f), 1e-6f)
        assertEquals(1f, SurusFormul.isikOpakligi(5f), 1e-6f)
        assertEquals(1f, SurusFormul.isikOpakligi(50f), 1e-6f)
    }
}
