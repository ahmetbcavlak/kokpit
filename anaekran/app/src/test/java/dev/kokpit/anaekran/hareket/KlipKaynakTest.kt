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

/** The packaged clips: present, small, faststart (moov before mdat), poster decodes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KlipKaynakTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun kliplerKucukVeFaststart() {
        for (k in Klip.entries) {
            val b = res.openRawResource(k.ham).use { it.readBytes() }
            assertTrue("$k ≤ 160 KB", b.size in 50_000..160_000)
            val s = String(b, Charsets.ISO_8859_1)
            assertEquals("$k ftyp", 4, s.indexOf("ftyp"))
            assertTrue("$k moov mdat'tan önce", s.indexOf("moov") in 0 until s.indexOf("mdat"))
            assertTrue("$k ses izi yok", !s.contains("soun"))
        }
    }

    @Test
    fun girisPosteriCozulur() {
        val id = (Klip.GIRIS.poster as Goruntu.Ham).id
        val bmp = res.openRawResource(id).use { BitmapFactory.decodeStream(it) }
        assertNotNull(bmp)
    }
}
