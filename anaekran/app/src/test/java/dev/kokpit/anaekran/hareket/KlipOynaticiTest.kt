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
        override fun birak(): Long { birakildi = true; dinleyici = null; return 7L }

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

    /** Pictures held back until [teslim] (an asynchronous worker). */
    private val bekletilen = HashSet<String>()
    private val bekleyenTeslim = LinkedHashMap<String, (Drawable?) -> Unit>()

    /** Order in which pictures were requested (the worker decodes in this order). */
    private val istekSirasi = ArrayList<String>()

    private fun teslim(a: String) {
        val cb = bekleyenTeslim.remove(a) ?: error("$a beklenmiyor")
        cb(cizimler.getOrPut(a) { ColorDrawable(Color.rgb(a.hashCode() and 255, 10, 20)) })
    }

    private fun ad(g: Goruntu) = when (g) {
        is Goruntu.Hazir -> "hazir"
        is Goruntu.Kaynak -> "k${g.id}"
        is Goruntu.Ham -> "h${g.id}"
    }

    private val yukleyici = GorselYukleyici { _, g, sonra ->
        val a = ad(g)
        istekSirasi += a
        when (a) {
            in gelmeyen -> Unit
            in bekletilen -> bekleyenTeslim[a] = sonra
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
    fun posterGelmezse_videoRenkOrtusununAltinda_zamanAsimindaAtlar() {
        gelmeyen += "h${(Klip.GIRIS.poster as Goruntu.Ham).id}"
        val t0 = SystemClock.uptimeMillis()
        acilis()
        val o = oynaticilar.single()
        assertSame("renk örtüsü görünürken video hemen eklenir", video, o.gorunum.parent)
        assertEquals(View.VISIBLE, ortu.visibility)
        assertTrue(ortu.drawable is ColorDrawable)
        bekleKadar(t0, 1490)
        assertTrue(sonuclar.isEmpty())
        bekleKadar(t0, 2000)
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

    @Test
    fun soguk_acilis_posterHedeftenOnceIstenir_videoBeklemez() {
        val poster = "h${(Klip.GIRIS.poster as Goruntu.Ham).id}"
        bekletilen += poster
        bekletilen += "k1001"
        val t0 = SystemClock.uptimeMillis()
        acilis()
        assertTrue("poster hedeften önce kuyrukta", istekSirasi.indexOf(poster) < istekSirasi.indexOf("k1001"))
        val o = oynaticilar.single()
        assertSame("video poster beklemeden, renk örtüsünün altında", video, o.gorunum.parent)
        // The worker is slow: the poster comes at 1.2 s, the first frame at 1.3 s — no skip.
        bekleKadar(t0, 1200)
        teslim(poster)
        assertSame(cizimler[poster], ortu.drawable)
        bekleKadar(t0, 1300)
        o.ilkKare()
        assertEquals(View.GONE, ortu.visibility)
        bekleKadar(t0, 1600)
        o.dinleyici!!.bitti()
        teslim("k1001")
        bekleKadar(t0, 2200)
        assertEquals(listOf(Sonuc.TAMAM), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun ilkKareSiniri_videoEklendigiAndanSayilir() {
        // Transition with no cover yet: the video waits for the poster (decoded at 1 s).
        val poster = "k${(Klip.SURUS.poster as Goruntu.Kaynak).id}"
        bekletilen += poster
        val t0 = SystemClock.uptimeMillis()
        k.oynat(Klip.SURUS, null, HEDEF, 0L, 200L, bitti = kaydet)
        val o = oynaticilar.single()
        assertNull(o.gorunum.parent)
        bekleKadar(t0, 1000)
        teslim(poster)
        assertSame(video, o.gorunum.parent)
        bekleKadar(t0, 2400) // 1.4 s after the video was added: still waiting
        assertTrue(sonuclar.isEmpty())
        bekleKadar(t0, 2800)
        assertEquals(listOf(Sonuc.ATLANDI), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun ortuHicGelmezse_ayriUzunSinirdaAtlar() {
        gelmeyen += "k${(Klip.SURUS.poster as Goruntu.Kaynak).id}"
        val t0 = SystemClock.uptimeMillis()
        k.oynat(Klip.SURUS, null, HEDEF, 0L, 200L, bitti = kaydet)
        bekleKadar(t0, 2900)
        assertTrue(sonuclar.isEmpty())
        bekleKadar(t0, 3400)
        assertEquals(listOf(Sonuc.ATLANDI), sonuclar)
        hepsiTemiz()
    }

    @Test
    fun eskiPostertenSonraGelirsePosteriEzmez() {
        bekletilen += "k1003"
        k.oynat(Klip.SURUS, ESKI, HEDEF, 0L, 200L, bitti = kaydet)
        val posterAd = "k${(Klip.SURUS.poster as Goruntu.Kaynak).id}"
        assertSame(cizimler[posterAd], ortu.drawable)
        teslim("k1003")
        assertSame(cizimler[posterAd], ortu.drawable)
    }

    @Test
    fun pencereGizlenince_gecissizHemenBiter() {
        val goruntuler = ArrayList<Boolean>()
        k.videoGorunurlugu = { goruntuler += it }
        acilis()
        val o = oynaticilar.single()
        bekle(100)
        o.ilkKare()
        val d = o.dinleyici!!
        o.birak() // DokuOynatici releases before telling
        d.pencereGizlendi()
        assertEquals(listOf(Sonuc.ATLANDI), sonuclar)
        hepsiTemiz()
        assertEquals(listOf(true, false), goruntuler)
    }

    @Test
    fun videoGorunurluguCiftHalinde_bittidenOnceKapanir() {
        val olaylar = ArrayList<String>()
        k.videoGorunurlugu = { olaylar += "video:$it" }
        k.oynat(Klip.SURUS, ESKI, HEDEF, 0L, 200L) { olaylar += "bitti:$it" }
        val o = oynaticilar.single()
        o.dinleyici!!.basladi()
        assertTrue("çerçeve çizilmeden sahne gizlenmez", olaylar.isEmpty())
        o.dinleyici!!.kareGeldi()
        assertEquals(listOf("video:true"), olaylar)
        o.dinleyici!!.bitti()
        bekle(400)
        assertEquals(listOf("video:true", "video:false", "bitti:TAMAM"), olaylar)
        assertEquals(7L, k.sonOlcum?.birakmaMs)
        assertNotNull(k.sonOlcum?.ilkKareMs)
    }

    @Test
    fun hareketIzi_klipVeSolmaSirasindaAcik_tutmadaKapali() {
        var acik = 0
        val izler = ArrayList<Int>()
        k.izci = object : HareketIzci {
            override fun hareketBasladi() { acik++; izler += acik }
            override fun hareketBitti() { acik--; izler += acik }
        }
        val t0 = SystemClock.uptimeMillis()
        acilis()
        val o = oynaticilar.single()
        assertEquals("poster duruyor: hareket yok", 0, acik)
        o.ilkKare()
        assertEquals(1, acik)
        bekleKadar(t0, 1333)
        o.dinleyici!!.bitti()
        assertEquals("tutma süresi durgun", 0, acik)
        bekleKadar(t0, 1700)
        assertEquals("solma", 1, acik)
        bekleKadar(t0, 2200)
        assertEquals(0, acik)
        assertEquals(listOf(1, 0, 1, 0), izler)
    }

    @Test
    fun kesildiGeriCagrisindanYeniOynat_sizmaz() {
        var araSonuc: Sonuc? = null
        k.oynat(Klip.SURUS, ESKI, HEDEF, 0L, 200L) { s ->
            sonuclar += s
            if (s == Sonuc.KESILDI) k.oynat(Klip.SURUS_GERI, null, HEDEF2, 0L, 200L) { araSonuc = it }
        }
        var sonSonuc: Sonuc? = null
        k.oynat(Klip.SURUS, null, HEDEF, 0L, 200L) { sonSonuc = it }
        assertEquals(listOf(Sonuc.KESILDI), sonuclar)
        assertEquals("aradaki çağrı da temizce kesildi", Sonuc.KESILDI, araSonuc)
        assertEquals(3, oynaticilar.size)
        assertTrue(oynaticilar[0].birakildi)
        assertTrue(oynaticilar[1].birakildi)
        assertNull(oynaticilar[1].gorunum.parent)
        val son = oynaticilar[2]
        assertFalse(son.birakildi)
        son.ilkKare()
        son.dinleyici!!.bitti()
        bekle(400)
        assertEquals(Sonuc.TAMAM, sonSonuc)
        hepsiTemiz()
    }

    @Test
    fun hedefBeklenirkenKes_hedefGelinceHizliSolar() {
        bekletilen += "k1001"
        acilis()
        val o = oynaticilar.single()
        o.ilkKare()
        bekle(200)
        k.kes()
        bekle(500)
        assertTrue("hedef yok: bekliyor", sonuclar.isEmpty())
        assertEquals(View.GONE, ust.visibility)
        teslim("k1001")
        assertEquals(View.VISIBLE, ust.visibility)
        bekle(100)
        assertTrue(sonuclar.isEmpty())
        bekle(150)
        assertEquals(listOf(Sonuc.DOKUNULDU), sonuclar)
        hepsiTemiz()
    }
}
