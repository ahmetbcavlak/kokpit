package dev.kokpit.anaekran.hareket

import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The packaged clips (design pack 0.2, K-93): present, ≤ 200 KB, faststart (moov before mdat),
 * no audio, H.264 Main level 3.1 (avcC), colour tagged bt709/tv (colr nclx), poster decodes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27])
class KlipKaynakTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun kliplerKucukVeFaststart() {
        for (k in Klip.entries) {
            val b = res.openRawResource(k.ham).use { it.readBytes() }
            assertTrue("$k ≤ 200 KB", b.size in 50_000..204_800)
            val s = String(b, Charsets.ISO_8859_1)
            assertEquals("$k ftyp", 4, s.indexOf("ftyp"))
            assertTrue("$k moov mdat'tan önce", s.indexOf("moov") in 0 until s.indexOf("mdat"))
            assertTrue("$k ses izi yok", !s.contains("soun"))
            val avc = s.indexOf("avcC")
            assertEquals("$k Main profile", 77, b[avc + 5].toInt() and 0xFF)
            assertEquals("$k level 3.1", 31, b[avc + 7].toInt() and 0xFF)
            val colr = s.indexOf("colrnclx")
            assertTrue("$k renk etiketi var", colr > 0)
            // primaries, transfer, matrix = 1 (bt709); then the full-range bit 0 (tv)
            val etiket = (0 until 7).map { b[colr + 8 + it].toInt() and 0xFF }
            assertEquals("$k bt709/tv", listOf(0, 1, 0, 1, 0, 1, 0), etiket)
        }
    }

    @Test
    fun girisPosteriCozulur() {
        val id = (Klip.GIRIS.poster as Goruntu.Ham).id
        val bmp = res.openRawResource(id).use { BitmapFactory.decodeStream(it) }
        assertNotNull(bmp)
        assertEquals(768, bmp.width)
        assertEquals(1024, bmp.height)
    }

    @Test
    fun surelerKlipKareSayisiyla() {
        assertEquals(2400L, Klip.GIRIS.sureMs) // 72 frames at 30 fps
        assertEquals(2133L, Klip.SURUS.sureMs) // 64 frames
        assertEquals(2133L, Klip.SURUS_GERI.sureMs)
    }
}
