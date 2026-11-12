package dev.kokpit.anaekran.tema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Sunrise/sunset (NOAA simplified formulas) against an official almanac.
 *
 * Reference: U.S. Naval Observatory, Astronomical Applications Department, "Sun and Moon Data
 * for One Day" (aa.usno.navy.mil/api/rstt/oneday, coords 41.01,28.98, tz +3), read 13 Nov 2026:
 *  - 13 Nov 2026 Istanbul: rise 07:49, set 17:47 (UTC+3)
 *  - 21 Jun 2026 Istanbul: rise 05:32, set 20:40 (UTC+3)
 * (Turkey is UTC+3 all year since 2016, so the winter sunset is near 17:45, not 16:45.)
 * Tolerance ±10 min; the simplified series is expected within ~2 min.
 */
class GunesTest {

    private val trt = ZoneOffset.ofHours(3)
    private fun an(y: Int, ay: Int, g: Int, s: Int, dk: Int) =
        ZonedDateTime.of(y, ay, g, s, dk, 0, 0, trt).toInstant().toEpochMilli()

    private fun yakin(beklenen: Long, gercek: Long?, ad: String) {
        assertNotNull(ad, gercek)
        val fark = abs(gercek!! - beklenen) / 60_000.0
        assertTrue("$ad: ${fark} dk fark", fark <= 10.0)
    }

    @Test
    fun istanbul13Kasim() {
        val g = Gunes.gun(an(2026, 11, 13, 12, 0), 41.01, 28.98)
        assertEquals(Gunes.Tur.NORMAL, g.tur)
        yakin(an(2026, 11, 13, 7, 49), g.dogum, "doğum")
        yakin(an(2026, 11, 13, 17, 47), g.batim, "batım")
        // The same day from early morning to late evening. (Just after clock midnight the local
        // SOLAR time is still the day before — Istanbul runs ~1 h ahead of the sun — and that
        // day's sunset is past, so the answer, night, is the same.)
        assertEquals(g, Gunes.gun(an(2026, 11, 13, 5, 0), 41.01, 28.98))
        assertEquals(g, Gunes.gun(an(2026, 11, 13, 23, 55), 41.01, 28.98))
        assertFalse(Gunes.gunduzMu(an(2026, 11, 13, 0, 5), 41.01, 28.98))
    }

    @Test
    fun istanbul21Haziran() {
        val g = Gunes.gun(an(2026, 6, 21, 12, 0), 41.01, 28.98)
        yakin(an(2026, 6, 21, 5, 32), g.dogum, "doğum")
        yakin(an(2026, 6, 21, 20, 40), g.batim, "batım")
    }

    @Test
    fun gunduzKurali_tamDogumBatim() {
        val g = Gunes.gun(an(2026, 11, 13, 12, 0), 41.01, 28.98)
        val dogum = g.dogum!!
        val batim = g.batim!!
        assertFalse(Gunes.gunduzMu(dogum - 1, 41.01, 28.98))
        assertTrue(Gunes.gunduzMu(dogum, 41.01, 28.98)) // sunrise ≤ now
        assertTrue(Gunes.gunduzMu(batim - 1, 41.01, 28.98))
        assertFalse(Gunes.gunduzMu(batim, 41.01, 28.98)) // now < sunset
        // What the old 07:00–19:00 rule got wrong in November.
        assertFalse(Gunes.gunduzMu(an(2026, 11, 13, 7, 20), 41.01, 28.98))
        assertFalse(Gunes.gunduzMu(an(2026, 11, 13, 18, 30), 41.01, 28.98))
        assertTrue(Gunes.gunduzMu(an(2026, 11, 13, 16, 45), 41.01, 28.98))
    }

    @Test
    fun kutupVeUcDurumlarCokmez() {
        // Tromsø: polar night in December, midnight sun in June.
        val aralik = Gunes.gun(an(2026, 12, 15, 12, 0), 69.65, 18.96)
        assertEquals(Gunes.Tur.KUTUP_GECESI, aralik.tur)
        assertNull(aralik.dogum)
        assertFalse(Gunes.gunduzMu(an(2026, 12, 15, 12, 0), 69.65, 18.96))
        val haziran = Gunes.gun(an(2026, 6, 21, 0, 30), 69.65, 18.96)
        assertEquals(Gunes.Tur.GECE_YARISI_GUNESI, haziran.tur)
        assertTrue(Gunes.gunduzMu(an(2026, 6, 21, 0, 30), 69.65, 18.96))

        // Poles, the date line, the southern hemisphere, odd inputs: an answer, never an exception.
        for (enlem in listOf(90.0, -90.0, 89.99, -89.99, 0.0, -33.87, 64.15)) {
            for (boylam in listOf(-180.0, -179.9, 0.0, 151.21, 179.9, 180.0, 540.0)) {
                for (ay in 1..12) Gunes.gunduzMu(an(2026, ay, 1, 12, 0), enlem, boylam)
            }
        }
        Gunes.gunduzMu(an(2028, 2, 29, 12, 0), 41.0, 29.0) // leap day
        assertFalse(Gunes.gunduzMu(an(2026, 11, 13, 12, 0), Double.NaN, 28.98))
        Gunes.gunduzMu(an(2026, 11, 13, 12, 0), 41.0, Double.NaN)
        Gunes.gunduzMu(0L, 41.0, 29.0)

        // Equator: about 12 h of day all year.
        val e = Gunes.gun(an(2026, 3, 20, 12, 0), 0.0, 0.0)
        val saat = (e.batim!! - e.dogum!!) / 3_600_000.0
        assertTrue("$saat", saat in 11.9..12.3)
    }
}
