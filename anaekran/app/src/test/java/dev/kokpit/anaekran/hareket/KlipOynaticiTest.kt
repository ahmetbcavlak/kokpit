package dev.kokpit.anaekran.hareket

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import dev.kokpit.anaekran.hareket.KlipOynatici.Sonuc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Clip player timeline with a fake player (Robolectric's MediaPlayer does not decode) and a
 * synchronous picture loader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KlipOynaticiTest {

    private class SahteOynatici(context: Context) : Oynatici {
        override val gorunum: View = View(context)
        var dinleyici: Oynatici.Dinleyici? = null
        var klip = 0
        var birakildi = false
        override fun hazirla(klipHam: Int, d: Oynatici.Dinleyici) { klip = klipHam; dinleyici = d }
        override fun birak() { birakildi = true; dinleyici = null }

        /** Decoder output: RENDERING_START, then the view latches the frame. */
        fun ilkKare() { dinleyici?.basladi(); dinleyici?.kareGeldi() }
    }

    private lateinit var act: Activity
    private lateinit var kok: FrameLayout
    private lateinit var video: FrameLayout
    private lateinit var k: KlipOynatici
    private val oynaticilar = ArrayList<SahteOynatici>()
    private val cizimler = HashMap<String, Drawable>()

    /** Pictures that never arrive (decode failed silently, like Gorseller) or arrive as null. */
    private val gelmeyen = HashSet<String>()
    private val bos = HashSet<String>()

    private fun ad(g: Goruntu) = when (g) {
        is Goruntu.Hazir -> "hazir"
        is Goruntu.Kaynak -> "k${g.id}"
        is Goruntu.Ham -> "h${g.id}"
    }

    private val yukleyici = GorselYukleyici { _, g, sonra ->
        val a = ad(g)
        when (a) {
            in gelmeyen -> Unit
            in bos -> sonra(null)
            else -> sonra(cizimler.getOrPut(a) { ColorDrawable(Color.rgb(a.hashCode() and 255, 10, 20)) })
        }
    }

    private val sonuclar = ArrayList<Sonuc>()
    private val kaydet: (Sonuc) -> Unit = { sonuclar += it }

    private val HEDEF = Goruntu.Kaynak(1001)
    private val HEDEF2 = Goruntu.Kaynak(1002)
    private val ESKI = Goruntu.Kaynak(1003)

    @Before
    fun hazirla() {
        act = Robolectric.buildActivity(Activity::class.java).setup().get()
        kok = FrameLayout(act)
        video = FrameLayout(act)
        kok.addView(video)
        act.setContentView(kok)
        HareketTestYardim.gercekSure()
        k = KlipOynatici(video, kok, { SahteOynatici(it).also { o -> oynaticilar += o } }, yukleyici)
    }

    private fun bekle(ms: Long) = HareketTestYardim.bekle(ms)

    /** Advances to [ms] after [t0]. */
    private fun bekleKadar(t0: Long, ms: Long) = bekle(t0 + ms - SystemClock.uptimeMillis())

    private fun gorunenResimler(): List<ImageView> =
        (0 until video.childCount).map { video.getChildAt(it) }.filterIsInstance<ImageView>().filter { it.visibility == View.VISIBLE }

    private val ust get() = (0 until video.childCount).map { video.getChildAt(it) }.filterIsInstance<ImageView>().last()
    private val ortu get() = (0 until video.childCount).map { video.getChildAt(it) }.filterIsInstance<ImageView>().first()

    private fun acilis() = k.oynat(Klip.GIRIS, null, HEDEF, 1600L, 350L, dokunmaKalkani = true, bitti = kaydet)

    private fun hepsiTemiz() {
        assertTrue("oynatıcı bırakıldı", oynaticilar.all { it.birakildi })
        assertTrue("video görünümü kalktı", oynaticilar.none { it.gorunum.parent != null })
        assertTrue("tam ekran katman kalmadı", gorunenResimler().isEmpty())
        assertEquals("dokunma kalkanı kalktı", 1, kok.childCount)
        assertFalse(k.calisiyor)
    }

    @Test
    fun acilis_birBucukAltinciSaniyedeSolarBirKezBiter() {
        val t0 = SystemClock.uptimeMillis()
        acilis()
        val o = oynaticilar.single()
        assertEquals(Klip.GIRIS.ham, o.klip)
        // Poster is decoded (sync here): the video view sits under the cover.
        assertSame(video, o.gorunum.parent)
        assertEquals(0, video.indexOfChild(o.gorunum))
        assertSame(cizimler["h${(Klip.GIRIS.poster as Goruntu.Ham).id}"], ortu.drawable)
        bekle(120)
        o.ilkKare()
        assertEquals("ilk kare gelince poster kalkar", View.GONE, ortu.visibility)
        bekleKadar(t0, 1333)
        o.dinleyici!!.bitti()
        bekleKadar(t0, 1590)
        assertEquals("tutma süresince son kare (video) kalır", View.GONE, ust.visibility)
        assertTrue(sonuclar.isEmpty())
        bekleKadar(t0, 1700)
        assertEquals("1,6 sn'de solma başladı", View.VISIBLE, ust.visibility)
        assertTrue(ust.alpha > 0f && ust.alpha < 1f)
        assertFalse("video solma bitene kadar bırakılmaz", o.birakildi)
        bekleKadar(t0, 2200)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
        hepsiTemiz()
        bekle(2000)
        assertEquals(1, sonuclar.size)
    }

    @Test
    fun posterYalnizHemBaslayipHemKareGelinceKalkar() {
        acilis()
        val o = oynaticilar.single()
        o.dinleyici!!.basladi()
        assertEquals(View.VISIBLE, ortu.visibility)
        o.dinleyici!!.kareGeldi()
        assertEquals(View.GONE, ortu.visibility)
    }

    @Test
    fun yavasKodlayicida_klipBitmedenSolmaz() {
        val t0 = SystemClock.uptimeMillis()
        acilis()
        val o = oynaticilar.single()
        bekleKadar(t0, 500)
        o.ilkKare()
        bekleKadar(t0, 1700)
        assertEquals(View.GONE, ust.visibility)
        o.dinleyici!!.bitti()
        bekle(20)
        assertEquals(View.VISIBLE, ust.visibility)
        bekle(400)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
    }

    @Test
    fun kesince_hizliSolupBiter() {
        acilis()
        val o = oynaticilar.single()
        bekle(100)
        o.ilkKare()
        bekle(300)
        k.kes()
        k.kes()
        bekle(100)
        assertEquals(View.VISIBLE, ust.visibility)
        assertTrue("350 ms değil, 150 ms", sonuclar.isEmpty())
        bekle(120)
        assertEquals(listOf(Sonuc.DOKUNULDU), sonuclar)
        hepsiTemiz()
        o.dinleyici?.bitti() // late completion is ignored
        bekle(2000)
        assertEquals(1, sonuclar.size)
    }

    @Test
    fun kalkanaDokununcaKeser_kaydirincaKesmez() {
        acilis()
        oynaticilar.single().ilkKare()
        val kalkan = kok.getChildAt(kok.childCount - 1)
        fun ev(a: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, a, x, y, 0)
        // Swipe from the top edge: not a tap.
        kalkan.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 300f, 0f))
        kalkan.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 300f, 200f))
        kalkan.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 300f, 200f))
        bekle(300)
        assertTrue(sonuclar.isEmpty())
        // Tap.
        kalkan.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 300f, 500f))
        kalkan.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 301f, 501f))
        bekle(300)
        assertEquals(listOf(Sonuc.DOKUNULDU), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun hatadaDogrudanSahneyeSolar() {
        val t0 = SystemClock.uptimeMillis()
        acilis()
        val o = oynaticilar.single()
        bekle(100)
        o.dinleyici!!.hata("test")
        assertTrue("çözücü hemen bırakılır (poster üstte)", o.birakildi)
        assertEquals(View.VISIBLE, ortu.visibility)
        bekle(20)
        assertEquals(View.VISIBLE, ust.visibility)
        bekleKadar(t0, 600)
        assertEquals(listOf(Sonuc.ATLANDI), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun ilkKareGelmezseZamanAsiminda_atlar() {
        val t0 = SystemClock.uptimeMillis()
        acilis()
        bekleKadar(t0, 1490)
        assertTrue(sonuclar.isEmpty())
        assertEquals("siyah yok: poster hâlâ üstte", View.VISIBLE, ortu.visibility)
        bekleKadar(t0, 1520)
        assertEquals(View.VISIBLE, ust.visibility)
        bekleKadar(t0, 1900)
        assertEquals(listOf(Sonuc.ATLANDI), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun posterGelmezse_videoEklenmez_zamanAsimindaAtlar() {
        gelmeyen += "h${(Klip.GIRIS.poster as Goruntu.Ham).id}"
        acilis()
        val o = oynaticilar.single()
        assertNull("örtüsüz video eklenmez", o.gorunum.parent)
        assertEquals("açılışta pencere rengiyle örtülü", View.VISIBLE, ortu.visibility)
        bekle(1600)
        bekle(400)
        assertEquals(listOf(Sonuc.ATLANDI), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun hedefHicGelmezseYineBiter() {
        gelmeyen += "k1001"
        k.oynat(null, ESKI, HEDEF, 0L, 350L, bitti = kaydet)
        bekle(1100)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun surusGecisi_klipBitinceKisaSolma() {
        k.oynat(Klip.SURUS, ESKI, HEDEF, 0L, 200L, bitti = kaydet)
        val o = oynaticilar.single()
        assertSame("poster: park sahnesi", cizimler["k${(Klip.SURUS.poster as Goruntu.Kaynak).id}"], ortu.drawable)
        bekle(50)
        o.ilkKare()
        bekle(800)
        assertEquals(View.GONE, ust.visibility)
        o.dinleyici!!.bitti()
        bekle(100)
        assertEquals(View.VISIBLE, ust.visibility)
        bekle(200)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun duzSaydamlikGecisi_eskiOrtudeYeniUstte() {
        k.oynat(null, ESKI, HEDEF, 0L, 350L, bitti = kaydet)
        assertTrue(oynaticilar.isEmpty())
        assertSame(cizimler["k1003"], ortu.drawable)
        assertEquals(View.VISIBLE, ortu.visibility)
        assertSame(cizimler["k1001"], ust.drawable)
        bekle(150)
        assertTrue(ust.alpha > 0f && ust.alpha < 1f)
        bekle(300)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun artArdaIkiGecis_ilkiTemizceKesilir() {
        k.oynat(Klip.SURUS, ESKI, HEDEF, 0L, 200L, bitti = kaydet)
        val o1 = oynaticilar.single()
        bekle(50)
        o1.ilkKare()
        bekle(300)
        var ikinci: Sonuc? = null
        k.oynat(Klip.SURUS_GERI, null, HEDEF2, 0L, 200L) { ikinci = it }
        assertEquals(listOf(Sonuc.KESILDI), sonuclar)
        assertTrue(o1.birakildi)
        assertNull(o1.gorunum.parent)
        // The new run starts from the first one's target, not from black.
        assertEquals(View.VISIBLE, ortu.visibility)
        assertNotNull(ortu.drawable)
        val o2 = oynaticilar[1]
        o2.ilkKare()
        bekle(800)
        o2.dinleyici!!.bitti()
        bekle(400)
        assertEquals(Sonuc.TAMAM, ikinci)
        assertEquals(1, sonuclar.size)
        hepsiTemiz()
    }

    @Test
    fun bittiIcindenYeniCagri_ustuKaldirmaz() {
        var ikinciBitti: Sonuc? = null
        k.oynat(null, ESKI, HEDEF, 0L, 100L) {
            sonuclar += it
            k.oynat(null, null, HEDEF2, 0L, 100L) { s -> ikinciBitti = s }
        }
        bekle(140)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
        // The first target is now the second run's cover.
        assertSame(cizimler["k1001"], ortu.drawable)
        assertEquals(View.VISIBLE, ortu.visibility)
        bekle(300)
        assertEquals(Sonuc.TAMAM, ikinciBitti)
        hepsiTemiz()
    }

    @Test
    fun bitirHedefteKeser() {
        acilis()
        k.bitir()
        assertEquals(listOf(Sonuc.KESILDI), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun birak_geriCagriYok_hepsiKalkar() {
        acilis()
        k.birak()
        bekle(3000)
        assertTrue(sonuclar.isEmpty())
        assertTrue(oynaticilar.single().birakildi)
        assertEquals(0, video.childCount)
        assertEquals(1, kok.childCount)
    }
}
