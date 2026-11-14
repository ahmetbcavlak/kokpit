package dev.kokpit.anaekran.hareket.surus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** Integration over frames: dt × speed, frame-rate independence, camera spring, rest. */
class SurusModeliTest {

    private fun ms(kmh: Float) = kmh / 3.6f

    /** Runs [sn] seconds at [fps] with speed [v] (function of time, s) and returns the model. */
    private fun calistir(fps: Int, sn: Double, v: (Double) -> Float): SurusModeli {
        val m = SurusModeli()
        val dt = 1.0 / fps
        var t = 0.0
        m.ilerle(0f, v(0.0))
        val n = Math.round(sn * fps).toInt()
        repeat(n) {
            t += dt
            m.ilerle(dt.toFloat(), v(t))
        }
        return m
    }

    private fun aciFarki(a: Double, b: Double): Double {
        val d = abs(a - b).mod(360.0)
        return minOf(d, 360.0 - d)
    }

    @Test
    fun tekerAcisi_dtCarpiVBolu0316() {
        val m = SurusModeli()
        val v = ms(10f)
        m.ilerle(0f, v)
        m.ilerle(0.1f, v)
        val beklenen = Math.toDegrees(0.1 * v / 0.316)
        assertEquals(beklenen, m.tekerAcisi, 1e-3)
    }

    @Test
    fun otuzVeAltmisFpsAyniSuredeAyniAci() {
        // constant speeds, both regimes (real speed and the blurred cap)
        for (k in listOf(8f, 25f, 50f, 130f)) {
            val a = calistir(60, 3.0) { ms(k) }
            val b = calistir(30, 3.0) { ms(k) }
            assertTrue("$k km/sa: ${a.tekerAcisi} / ${b.tekerAcisi}", aciFarki(a.tekerAcisi, b.tekerAcisi) <= 1.0)
            assertEquals(a.mesafe, b.mesafe, 1e-6)
        }
        // accelerating 0 → 60 km/h in 4 s, then braking (the regimes cross)
        val hiz = { t: Double -> ms((if (t < 4.0) 15.0 * t else maxOf(0.0, 60.0 - 20.0 * (t - 4.0))).toFloat()) }
        val a = calistir(60, 7.0, hiz)
        val b = calistir(30, 7.0, hiz)
        assertTrue("ivmelenmede ±1°: ${a.tekerAcisi} / ${b.tekerAcisi}", aciFarki(a.tekerAcisi, b.tekerAcisi) <= 1.0)
        assertEquals(a.mesafe, b.mesafe, 0.01)
        assertEquals(a.yakinOfset, b.yakinOfset, 1.5f)
    }

    @Test
    fun seritKaydirma_1m142px() {
        val m = SurusModeli()
        m.ilerle(0f, 2f)
        m.ilerle(1f, 2f) // 2 m
        assertEquals(2.0, m.mesafe, 1e-9)
        assertEquals(284f, m.yakinOfset, 1e-3f)
        assertEquals(284f * 0.8f, m.uzakOfset, 1e-3f)
        assertEquals(284f * 0.04f, m.isikOfset, 1e-3f)
        // wraps at the 9 m period (1278 px)
        m.ilerle(4f, 2f) // 10 m
        assertEquals((1420.0 - 1278.0).toFloat(), m.yakinOfset, 1e-2f)
    }

    @Test
    fun kamera_16SnYumusak_asmaz() {
        val m = SurusModeli()
        m.ilerle(0f, ms(130f))
        var t = 0.0
        var enKucuk = 1f
        while (t < 1.6) {
            m.ilerle(1f / 60f, ms(130f)); t += 1.0 / 60
            enKucuk = minOf(enKucuk, m.kamera)
        }
        val ilerleme = (1f - m.kamera) / 0.035f
        assertTrue("1,6 sn'de ≈ %95 ($ilerleme)", ilerleme in 0.93f..0.97f)
        repeat(600) { m.ilerle(1f / 60f, ms(130f)); enKucuk = minOf(enKucuk, m.kamera) }
        assertEquals(0.965f, m.kamera, 1e-4f)
        assertTrue("hedefi geçmez", enKucuk >= 0.965f - 1e-5f)
    }

    @Test
    fun durunca_herSeyYumusakcaDurur_sonraDurgun() {
        val m = SurusModeli()
        m.ilerle(0f, ms(50f))
        repeat(300) { m.ilerle(1f / 60f, ms(50f)) }
        assertFalse(m.durgun)
        // speed glides to 0 over 2 s
        for (i in 1..120) m.ilerle(1f / 60f, ms(50f) * (1f - i / 120f))
        val aci = m.tekerAcisi
        val mesafe = m.mesafe
        assertEquals(0f, m.suspansiyon, 0f)
        assertEquals(0f, m.izOpakligi)
        assertEquals(0f, m.lambaGorunurlugu)
        repeat(300) { m.ilerle(1f / 60f, 0f) }
        assertEquals("0'da hareketsiz", aci, m.tekerAcisi, 0.0)
        assertEquals(mesafe, m.mesafe, 0.0)
        assertEquals(1f, m.kamera)
        assertTrue(m.durgun)
    }

    @Test
    fun yansima_her35mdeBir() {
        val m = SurusModeli()
        m.ilerle(0f, 10f)
        var supurme = 0
        var oncekiVar = false
        repeat(60 * 34) { // 34 s at 10 m/s = 340 m: sweeps start at 0, 35 … 315 m
            m.ilerle(1f / 60f, 10f)
            val var_ = !m.lambaKonumu.isNaN()
            if (var_ && !oncekiVar) supurme++
            oncekiVar = var_
        }
        assertEquals(10, supurme)
    }

    @Test
    fun suspansiyon_11SnYukari11SnAsagi() {
        val m = SurusModeli()
        m.ilerle(0f, ms(60f))
        var enAz = 0f
        repeat(66) { m.ilerle(1f / 60f, ms(60f)); enAz = minOf(enAz, m.suspansiyon) } // 1.1 s
        assertEquals(-0.9f, enAz, 0.01f)
        assertEquals(PI, m.sustFaz, 1e-6)
        repeat(66) { m.ilerle(1f / 60f, ms(60f)) }
        assertEquals(0f, m.suspansiyon, 1e-4f)
    }

    @Test
    fun dinlenmeyeAl_klibinSonKaresi() {
        val m = SurusModeli()
        m.ilerle(0f, 20f)
        repeat(120) { m.ilerle(1f / 60f, 20f) }
        assertTrue(m.tekerAcisi != 0.0 && m.mesafe > 0.0 && m.kamera < 1f)
        m.dinlenmeyeAl()
        assertEquals(1f, m.kamera)
        assertEquals(0f, m.suspansiyon)
        assertEquals("sprite'ın kendi duruşu", 0.0, m.tekerAcisi, 0.0)
        assertEquals(0.0, m.mesafe, 0.0)
        assertEquals(0f, m.yakinOfset)
        assertEquals(-0.40f, m.lambaKonumu, 1e-6f) // band outside the body
        assertFalse(m.tavanda)
        // next frame continues from 0 (trapezoid), no jump
        m.ilerle(1f / 60f, 20f)
        assertEquals(10.0 / 60.0, m.mesafe, 1e-6)
    }

    @Test
    fun tavan_30da_hemen_yumusakca_30fpsKaredeAzOtuzAltiDerece() {
        for (k in listOf(30f, 38f, 45f)) {
            val m = SurusModeli()
            m.ilerle(0f, ms(k))
            assertTrue("$k: tavan açık", m.tavanda)
            var onceki = m.tekerAcisi
            repeat(90) {
                m.ilerle(1f / 30f, ms(k))
                val adim = aciFarki(m.tekerAcisi, onceki)
                assertTrue("$k km/sa: 30 fps karede ${adim}°", adim < 36.0)
                onceki = m.tekerAcisi
            }
        }
    }

    @Test
    fun tavanGecisi_sicramaYok_histerezis() {
        val m = SurusModeli()
        m.ilerle(0f, ms(25f))
        var w = m.gosterilenAcisalHiz
        // 25 → 35 km/h in 2 s at 60 fps: the shown speed eases (no step per frame > ~1.4 rad/s)
        for (i in 1..120) {
            m.ilerle(1f / 60f, ms(25f + 10f * i / 120f))
            val y = m.gosterilenAcisalHiz
            assertTrue("kare $i: ${w} → ${y}", abs(y - w) < 2f)
            w = y
        }
        assertTrue(m.tavanda)
        // noise around 29 km/h does not switch back (exit below 28)
        repeat(60) { m.ilerle(1f / 60f, ms(if (it % 2 == 0) 29f else 30.5f)) }
        assertTrue(m.tavanda)
        repeat(60) { m.ilerle(1f / 60f, ms(27f)) }
        assertFalse(m.tavanda)
    }
}
