package dev.kokpit.anaekran.veri

import org.junit.Assert.assertEquals
import org.junit.Test

/** Interval summary of GPS fixes, plain JUnit. */
class OrnekAraligiTest {

    private val MS = 1_000_000L

    @Test
    fun yarimSaniye_birSaniye_duzensiz() {
        val a = OrnekAraligi()
        assertEquals("ölçülmedi", a.ozet())
        var t = 5_000 * MS
        a.ornek(t)
        assertEquals(0, a.sayi) // the first fix only starts the run
        repeat(4) { t += 500 * MS; a.ornek(t) }
        assertEquals(4, a.sayi)
        assertEquals(500 * MS, a.ortalamaNs())
        assertEquals(500 * MS, a.enKisaNs)
        assertEquals(500 * MS, a.enUzunNs)

        val b = OrnekAraligi()
        t = 0; b.ornek(t)
        repeat(20) { t += 1000 * MS; b.ornek(t) }
        assertEquals(1000 * MS, b.ortalamaNs())
        assertEquals(10, b.son().size) // only the last 10 kept
        assertEquals("ort 1,00 sn · en kısa 1,00 · en uzun 1,00 · 20 aralık · son: " + List(10) { "1,00" }.joinToString(" "), b.ozet())

        val c = OrnekAraligi()
        t = 0; c.ornek(t)
        val araliklar = longArrayOf(950, 1050, 200, 3000, 1000, 10, 1000, 1000, 1000, 1000, 1000, 1500)
        for (ms in araliklar) { t += ms * MS; c.ornek(t) }
        assertEquals(12, c.sayi)
        assertEquals(10 * MS, c.enKisaNs)
        assertEquals(3000 * MS, c.enUzunNs)
        assertEquals(araliklar.sum() * MS / 12, c.ortalamaNs())
        assertEquals(araliklar.takeLast(10).map { it * MS }, c.son())
    }

    @Test
    fun kesVeGeriGidenZaman_aralikSayilmaz() {
        val a = OrnekAraligi()
        a.ornek(0); a.ornek(1000 * MS)
        a.kes()
        a.ornek(60_000 * MS)
        assertEquals(1, a.sayi)
        a.ornek(59_000 * MS) // backwards: ignored as an interval, restarts the run
        assertEquals(1, a.sayi)
        a.ornek(60_000 * MS)
        assertEquals(2, a.sayi)
        a.sifirla()
        assertEquals(0, a.sayi)
        assertEquals(emptyList<Long>(), a.son())
    }
}
