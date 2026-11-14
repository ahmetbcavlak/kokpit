package dev.kokpit.anaekran.hareket

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Choreographer
import android.view.View
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.TestYardim
import dev.kokpit.anaekran.ekran.Ekran
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Baglayici on the real layer tree of MainActivity (as task 4 will wire it). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real webp decoding for the drive scene's bitmaps
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class HareketBaglayiciTest {

    private class Kaynak : KareKaynagi {
        val bekleyen = ArrayList<Choreographer.FrameCallback>()
        override fun iste(cb: Choreographer.FrameCallback) { bekleyen += cb }
        override fun birak(cb: Choreographer.FrameCallback) { bekleyen.remove(cb) }
    }

    private class Oy(c: Context) : Oynatici {
        override val gorunum = View(c)
        var d: Oynatici.Dinleyici? = null
        var birakildi = false
        override fun hazirla(klipHam: Int, d: Oynatici.Dinleyici) { this.d = d }
        override fun birak(): Long { birakildi = true; return 0L }
    }

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        // This test wires its own HareketBaglayici by hand: the activity runs without one.
        dev.kokpit.anaekran.Baglayicilar.uret = { emptyList() }
    }

    @Test
    fun kurBaslaDurBirakVeAcilis() {
        val c = TestYardim.ac()
        val a = c.get()
        val k = a.katmanlar
        HareketTestYardim.gercekSure()
        val kaynak = Kaynak()
        val oynaticilar = ArrayList<Oy>()
        val h = HareketBaglayici(
            oynaticiUret = { Oy(it).also { o -> oynaticilar += o } },
            yukleyici = { _, _, sonra -> sonra(ColorDrawable(0xFF102030.toInt())) },
            kareKaynagi = kaynak,
        )
        h.kur(a, k)
        assertTrue(k.kareHizi.getChildAt(0) is KareHiziSayaci)
        assertEquals(a.getString(R.string.kare_hizi_yok), (k.kareHizi.getChildAt(0) as KareHiziSayaci).text.toString())
        assertTrue(h.gecis is YumusakGecis)
        k.ekranlar.gecis = h.gecis

        h.basla()
        assertEquals(1, kaynak.bekleyen.size)
        h.dur()
        assertTrue(kaynak.bekleyen.isEmpty())

        var sonuc: KlipOynatici.Sonuc? = null
        h.acilisiOynat(R.drawable.sahne_park) { sonuc = it; k.ekranlar.goster(Ekran.PARK) }
        val o = oynaticilar.single()
        assertSame(k.video, o.gorunum.parent)
        o.d!!.basladi(); o.d!!.kareGeldi()
        h.kes()
        HareketTestYardim.bekle(500)
        assertEquals(KlipOynatici.Sonuc.DOKUNULDU, sonuc)
        assertEquals(Ekran.PARK, k.ekranlar.gecerli)
        assertTrue(o.birakildi)

        h.gecisOynat(Klip.SURUS, R.drawable.sahne_park, R.drawable.sahne_surus) { }
        h.birak()
        assertTrue(oynaticilar.last().birakildi)
        assertEquals(0, k.video.childCount)
        c.pause().stop().destroy()
    }

    @Test
    fun surusSahnesi_olusturBaslaDurHiz() {
        val c = TestYardim.ac()
        val a = c.get()
        val k = a.katmanlar
        val kaynak = Kaynak()
        val h = HareketBaglayici(oynaticiUret = { Oy(it) }, kareKaynagi = kaynak)
        h.kur(a, k)
        var hiz = 0f
        h.surusHizi({ hiz }, { hiz == 0f })
        var hudHizi = -1f
        h.surusKaresi { _, v -> hudHizi = v }
        val s = h.surusSahnesi(a)
        assertSame(s, h.surusSahnesi(a))
        TestYardim.bosalt() // bitmaps decoded (worker inline) and handed over
        assertTrue(s.hazir)
        assertSame(h.sayac, s.izci)
        k.ekran.addView(s, android.widget.FrameLayout.LayoutParams(768, 1024))
        TestYardim.bosalt()
        h.basla()
        assertTrue(s.calisiyor)
        hiz = 12f
        h.surusUyandir()
        val t0 = 1_000_000_000L
        repeat(3) { i ->
            val l = kaynak.bekleyen.toList()
            kaynak.bekleyen.clear()
            l.forEach { it.doFrame(t0 + i * 16_666_667L) }
        }
        assertEquals(12f, hudHizi)
        assertTrue(s.model.mesafe > 0.0)
        h.dur()
        assertTrue("dur: sahne ve sayaç kare istemez", kaynak.bekleyen.isEmpty())
        h.birak()
        assertEquals(null, h.surus)
        c.pause().stop().destroy()
    }
}
