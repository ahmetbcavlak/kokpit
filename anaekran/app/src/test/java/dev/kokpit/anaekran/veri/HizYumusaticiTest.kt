package dev.kokpit.anaekran.veri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Smoothed speed (design 0.2 §3; research decisions 9, 10, 12). Plain JUnit: the class has no Android calls. */
class HizYumusaticiTest {

    private val S = 1_000_000_000L
    private val KMH100 = (100 / 3.6).toFloat()

    private fun sn(s: Double) = (s * S).toLong()

    /** Frame time of frame [i] at [fps] (exact integers, so 30 and 60 fps share every other frame). */
    private fun kare(i: Long, fps: Int) = i * S / fps

    /** Runs frames at [fps] from [bas] (s) up to and including [son] (s); calls [her] on each frame value. */
    private fun HizYumusatici.kareler(bas: Double, son: Double, fps: Int = 60, her: (Long, Float) -> Unit = { _, _ -> }): Float {
        var i = Math.ceil(bas * fps - 1e-9).toLong()
        var x = 0f
        while (kare(i, fps) <= sn(son) + 1) {
            val t = kare(i, fps)
            x = deger(t)
            her(t, x)
            i++
        }
        return x
    }

    // --- Spring ---

    @Test
    fun sifirdan100e_birSaniyedeYuzde95_hicbirKaredeHedefiGecmez() {
        val y = HizYumusatici()
        y.ornek(0f, 0, null)
        y.kareler(0.0, 0.3)
        // 0.3 s after the first sample: too close for an acceleration, so this is a pure step.
        y.ornek(KMH100, sn(0.3), null)
        var enCok = 0f
        val x = y.kareler(0.3 + 1.0 / 60, 1.3) { t, v ->
            assertTrue("overshoot at $t: $v > ${y.hedef(t)}", v <= y.hedef(t) + 1e-4)
            enCok = maxOf(enCok, v)
        }
        assertTrue("1 s after the step: $x", x >= 0.95f * KMH100)
        assertTrue(enCok <= KMH100 + 1e-4)
        // Settles on the target and stays there.
        val son = y.kareler(1.3 + 1.0 / 60, 2.2) { _, v -> assertTrue(v <= KMH100 + 1e-4) }
        assertEquals(KMH100, son, 0.05f)
    }

    @Test
    fun ivmeliAdim_tahminleBile_hedefiGecmez() {
        val y = HizYumusatici()
        y.ornek(0f, 0, null)
        y.kareler(0.0, 1.0)
        y.ornek(KMH100, sn(1.0), null) // 27.8 m/s² measured, clamped to +4: target up to 27.8 + 2 m/s
        var enCok = 0f
        val x = y.kareler(1.0 + 1.0 / 60, 2.0) { t, v ->
            assertTrue("overshoot at $t", v <= y.hedef(t) + 1e-4)
            enCok = maxOf(enCok, v)
        }
        assertTrue(x >= 0.95f * KMH100)
        assertTrue("never above the predicted target", enCok <= KMH100 + 0.5f * 4f + 1e-3f)
    }

    @Test
    fun yuzden0a_negatifYok_sifiraOturur_durgun() {
        val y = HizYumusatici()
        for (s in 0..3) { y.ornek(KMH100, sn(s.toDouble()), null); y.kareler(s.toDouble(), s + 1.0 - 1.0 / 60) }
        assertEquals(KMH100, y.deger(sn(4.0) - 1), 0.1f)
        y.ornek(0f, sn(4.0), null) // hard stop (one sample; zero mode not yet on, the target is 0 anyway)
        assertFalse(y.sifirda)
        var sifirOldu = -1.0
        y.kareler(4.0, 7.0) { t, v ->
            assertTrue("negative at $t", v >= 0f)
            if (v == 0f && sifirOldu < 0) sifirOldu = t / 1e9
            if (sifirOldu >= 0) assertEquals("stays at 0", 0f, v, 0f)
        }
        assertTrue("settled to 0 at $sifirOldu", sifirOldu in 4.5..6.0)
        assertTrue(y.durgun(sn(7.0)))
        // A real speed again: the loop must run.
        y.ornek(3f, sn(7.5), null)
        assertFalse(y.durgun(sn(7.5)))
    }

    @Test
    fun dtKirpma_ikiSaniyeTakilmaSonrasiSicramaYok() {
        fun hazir() = HizYumusatici().also {
            it.ornek(0f, 0, null)
            it.deger(sn(0.3))
            it.ornek(KMH100, sn(0.3), null)
        }
        val takilan = hazir()
        val sonra = takilan.deger(sn(2.3)) // the frame loop was stuck for 2 s
        val tekAdim = hazir().deger(sn(0.35)) // one 50 ms step
        assertEquals(tekAdim, sonra, 1e-4f)
        assertTrue("no jump after a stall: $sonra", sonra < 2f)
        // First call ever: dt = 0, value unchanged.
        val y = HizYumusatici()
        y.ornek(10f, sn(5.0), null)
        assertEquals(10f, y.deger(sn(100.0)), 0f)
        // Time going backwards: dt = 0.
        assertEquals(10f, y.deger(sn(99.0)), 0f)
    }

    @Test
    fun otuzVeAltmisFps_ayniAndaAyniDeger() {
        // A drive: pulling away, cruising, braking to a stop; one sample a second, prediction active.
        val profil = floatArrayOf(0f, 0f, 2f, 5f, 8f, 11f, 13.9f, 13.9f, 13.9f, 10f, 6f, 2f, 0f, 0f)
        val y30 = HizYumusatici()
        val y60 = HizYumusatici()
        val d30 = HashMap<Long, Float>()
        for ((s, v) in profil.withIndex()) {
            y30.ornek(v, sn(s.toDouble()), null)
            y60.ornek(v, sn(s.toDouble()), null)
            y30.kareler(s.toDouble(), s + 1.0 - 1e-6, 30) { t, x -> d30[t] = x }
            y60.kareler(s.toDouble(), s + 1.0 - 1e-6, 60) { t, x ->
                val o = d30[t] ?: return@kareler
                assertTrue("t=$t: 60 fps $x vs 30 fps $o", abs(x - o) <= maxOf(0.02f * maxOf(x, o), 0.05f))
            }
        }
        assertTrue(d30.size > 300)
    }

    // --- Forward prediction ---

    @Test
    fun ileriTahmin_yarimSaniyeyiAsmaz_ivmeKirpilir() {
        val y = HizYumusatici()
        y.ornek(10f, 0, null)
        y.ornek(12f, sn(1.0), null) // +2 m/s²
        assertEquals(2f, y.sonIvme, 1e-4f)
        assertEquals(12.0, y.hedef(sn(1.0)), 1e-6)
        assertEquals(12.5, y.hedef(sn(1.25)), 1e-6)
        assertEquals(13.0, y.hedef(sn(1.5)), 1e-6)
        assertEquals("held after 0.5 s", 13.0, y.hedef(sn(2.9)), 1e-6)

        // Clamped to +4 and −9 m/s².
        val h = HizYumusatici()
        h.ornek(10f, 0, null); h.ornek(20f, sn(1.0), null)
        assertEquals(4f, h.sonIvme, 1e-4f)
        assertEquals(22.0, h.hedef(sn(1.7)), 1e-6)
        h.ornek(5f, sn(2.0), null)
        assertEquals(-9f, h.sonIvme, 1e-4f)
        assertEquals(0.5, h.hedef(sn(2.6)), 1e-6)
        h.ornek(1f, sn(3.0), null) // 1 m/s is not zero mode; −4 m/s²: 1 − 2 is clamped to 0
        assertFalse(h.sifirda)
        assertEquals(0.0, h.hedef(sn(3.5)), 1e-6)

        // Samples closer than 0.5 s give no acceleration of their own …
        val k = HizYumusatici()
        k.ornek(10f, 0, null); k.ornek(14f, sn(0.3), null)
        assertEquals(0f, k.sonIvme, 0f)
        assertEquals(14.0, k.hedef(sn(0.6)), 1e-6)
        // … at 5 Hz the newest sample at least 0.5 s older is used.
        k.ornek(15f, sn(0.5), null) // 0.5 s after the first: (15 − 10) / 0.5 = 10 → 4
        assertEquals(4f, k.sonIvme, 1e-4f)
        // After a gap of more than 2 s: no acceleration (the old sample is from before the gap).
        k.ornek(25f, sn(3.0), null)
        assertEquals(0f, k.sonIvme, 0f)
    }

    // --- 2 s and 5 s rules ---

    @Test
    fun ikiSaniyedeTahminBiter_besSaniyedeEski() {
        val y = HizYumusatici()
        assertTrue("nothing yet", y.eski)
        assertEquals(0f, y.deger(0), 0f)
        assertTrue(y.durgun(0))
        y.ornek(10f, sn(1.0), null)
        y.ornek(12f, sn(2.0), null)
        assertFalse(y.eski)
        assertEquals(13.0, y.hedef(sn(3.99)), 1e-6)
        assertEquals("2 s without a sample: last value, no prediction", 12.0, y.hedef(sn(4.0)), 1e-6)
        assertEquals(12.0, y.hedef(sn(30.0)), 1e-6)
        val x = y.kareler(2.0, 6.9)
        assertEquals(12f, x, 0.05f)
        assertFalse(y.eski)
        assertTrue("5 s without a sample", y.eskiMi(sn(7.01)))
        assertEquals("the value stays", 12f, y.deger(sn(7.02)), 0.05f)
        assertTrue(y.eski)
        y.ornek(12f, sn(8.0), null)
        assertFalse(y.eski)
    }

    // --- Zero mode ---

    @Test
    fun parkGurultusu_sifirdaKalir() {
        val y = HizYumusatici()
        val gurultu = floatArrayOf(0.2f, 0.4f, 0.6f, 0.3f, 0.55f, 0.25f, 0.6f, 0.45f, 0.5f, 0.35f, 0.6f, 0.2f, 0.58f, 0.3f)
        for ((s, v) in gurultu.withIndex()) {
            y.ornek(v, sn(s.toDouble()), null)
            y.kareler(s.toDouble(), s + 1.0 - 1e-6) { t, x ->
                assertEquals("park noise at $t", 0f, x, 0f)
                assertTrue(y.durgun(t))
            }
        }
        assertTrue(y.sifirda)
    }

    @Test
    fun sifirHisterezisi_ikiAltOrnekGirer_birUstOrnekCikar() {
        val y = HizYumusatici()
        y.ornek(5f, 0, null)
        assertFalse(y.sifirda)
        y.ornek(0.4f, sn(1.0), null) // one below 0.5
        assertFalse(y.sifirda)
        y.ornek(0.6f, sn(2.0), null) // between 0.5 and 0.8: breaks the run, does not enter
        assertFalse(y.sifirda)
        y.ornek(0.3f, sn(3.0), null)
        assertFalse(y.sifirda)
        y.ornek(0.2f, sn(4.0), null) // second in a row: zero
        assertTrue(y.sifirda)
        assertEquals(0.0, y.hedef(sn(4.2)), 0.0)
        y.ornek(0.7f, sn(5.0), null) // below 0.8: stays zero
        assertTrue(y.sifirda)
        y.ornek(0.8f, sn(6.0), null) // one sample ≥ 0.8: out
        assertFalse(y.sifirda)
        assertEquals(0.8, y.hedef(sn(6.0)), 1e-6)

        // With a speed accuracy the entry threshold is max(0.5, accuracy), the exit 0.3 above it.
        val d = HizYumusatici()
        d.ornek(5f, 0, 1.0f)
        d.ornek(0.9f, sn(1.0), 1.0f)
        d.ornek(0.95f, sn(2.0), 1.0f)
        assertTrue(d.sifirda)
        d.ornek(1.2f, sn(3.0), 1.0f) // below 1.3: stays
        assertTrue(d.sifirda)
        d.ornek(1.31f, sn(4.0), 1.0f)
        assertFalse(d.sifirda)
        // A small accuracy never lowers the thresholds below 0.5 / 0.8.
        val k = HizYumusatici()
        k.ornek(5f, 0, 0.1f); k.ornek(0.45f, sn(1.0), 0.1f); k.ornek(0.45f, sn(2.0), 0.1f)
        assertTrue(k.sifirda)
        k.ornek(0.75f, sn(3.0), 0.1f)
        assertTrue(k.sifirda)
    }

    // --- Reset, bad input ---

    @Test
    fun sifirla_ilkOrnekHemenGosterilir_bozukDegerYokSayilir() {
        val y = HizYumusatici()
        y.ornek(5f, 0, null)
        y.kareler(0.0, 0.5)
        y.sifirla()
        assertTrue(y.eski)
        assertNull(y.sonHam)
        assertTrue(y.sifirda)
        assertEquals(0f, y.deger(sn(10.0)), 0f)
        y.ornek(15f, sn(10.0), null) // opening the app while driving: "15", not a fake 0→15
        assertEquals(15f, y.deger(sn(10.02)), 1e-4f)
        // Also when the first sample falls between two frames.
        val z = HizYumusatici()
        z.deger(sn(1.0))
        z.ornek(15f, sn(1.01), null)
        assertEquals(15f, z.deger(sn(1.0167)), 1e-4f)

        y.ornek(Float.NaN, sn(11.0), null)
        y.ornek(Float.POSITIVE_INFINITY, sn(11.0), null)
        assertEquals(15f, y.sonHam!!, 0f)
        y.ornek(-3f, sn(12.0), null) // negative counts as 0
        assertEquals(0f, y.sonHam!!, 0f)
    }
}
