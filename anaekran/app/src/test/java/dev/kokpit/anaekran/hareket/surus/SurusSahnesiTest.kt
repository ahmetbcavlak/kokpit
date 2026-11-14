package dev.kokpit.anaekran.hareket.surus

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.kokpit.anaekran.hareket.HareketIzci
import dev.kokpit.anaekran.hareket.KareKaynagi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The drive scene view: loop life cycle, dt clamp, rest stop, views follow the model. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class SurusSahnesiTest {

    private class SahteKaynak : KareKaynagi {
        val bekleyen = ArrayList<Choreographer.FrameCallback>()
        override fun iste(cb: Choreographer.FrameCallback) { bekleyen += cb }
        override fun birak(cb: Choreographer.FrameCallback) { bekleyen.remove(cb) }
        fun vsync(t: Long) {
            val l = bekleyen.toList()
            bekleyen.clear()
            l.forEach { it.doFrame(t) }
        }
    }

    private lateinit var a: Activity
    private lateinit var kap: FrameLayout
    private lateinit var kaynak: SahteKaynak
    private lateinit var s: SurusSahnesi
    private var hiz = 0f
    private var durgun = true
    private var okuma = 0
    private var simdi = 0L
    private val vs = 16_666_667L

    @Before
    fun hazirla() {
        a = Robolectric.buildActivity(Activity::class.java).setup().get()
        kaynak = SahteKaynak()
        s = SurusSahnesi(a, kaynak) { simdi }
        s.hizSaglayici = { okuma++; hiz }
        s.durgunSaglayici = { durgun }
        s.varliklariKur(SurusVarliklari.coz(a.resources))
        kap = FrameLayout(a)
        kap.addView(s, FrameLayout.LayoutParams(768, 1024))
        a.setContentView(kap)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun kareler(n: Int, adim: Long = vs) {
        repeat(n) {
            simdi += adim
            kaynak.vsync(simdi)
        }
    }

    @Test
    fun varliklar_boyutVeBellek() {
        val v = SurusVarliklari.coz(a.resources)
        assertEquals(798, v.govde.width)
        assertEquals(272, v.govde.height)
        for (b in v.tekerOn + v.tekerArka) {
            assertEquals(101, b.width)
            assertEquals(101, b.height)
            assertEquals(Bitmap.Config.ARGB_8888, b.config)
        }
        assertEquals(Bitmap.Config.ARGB_8888, v.govde.config)
        assertEquals(600, v.isik.width)
        assertEquals(60, v.isik.height)
        assertTrue("≈1,25 MB (${v.bayt})", v.bayt in 1_200_000L..1_400_000L)
        assertTrue("arka plan dahil ≤ 12 MB", v.bayt + SurusVarliklari.SAHNE_BAYT <= 12L * 1048576L)
        // the lights tile has the four dots and transparent space between them
        assertTrue(Color32.alfa(v.isik.getPixel(20, 30)) > 60)
        assertEquals(0, Color32.alfa(v.isik.getPixel(100, 5)))
    }

    private object Color32 {
        fun alfa(c: Int) = (c ushr 24) and 255
    }

    @Test
    fun baslaDur_gorunurlukDonguyuYonetir() {
        assertFalse(s.donguBekliyor)
        s.basla()
        assertTrue(s.calisiyor)
        assertTrue(s.donguBekliyor)
        s.dur()
        assertFalse(s.donguBekliyor)
        assertTrue(kaynak.bekleyen.isEmpty())
        s.basla()
        assertTrue(s.donguBekliyor)
        kap.visibility = View.GONE
        assertFalse("görünmeyen sahne kare istemez", s.donguBekliyor)
        assertTrue(kaynak.bekleyen.isEmpty())
        kap.visibility = View.VISIBLE
        assertTrue(s.donguBekliyor)
        s.visibility = View.INVISIBLE
        assertFalse(s.donguBekliyor)
    }

    @Test
    fun durgundaDonguDurur_uyandirVeYoklamaBaslatir() {
        var acik = 0
        val izler = ArrayList<Int>()
        s.izci = object : HareketIzci {
            override fun hareketBasladi() { acik++; izler += acik }
            override fun hareketBitti() { acik--; izler += acik }
        }
        s.basla()
        kareler(1)
        assertFalse("hız 0, durgun: ilk karede durur", s.donguBekliyor)
        assertEquals(0, acik)
        hiz = 10f
        durgun = false
        s.uyandir()
        assertTrue(s.donguBekliyor)
        kareler(30)
        assertEquals(1, acik)
        assertTrue(s.donguBekliyor)
        // speed back to 0: the loop runs until the camera has settled, then stops
        hiz = 0f
        durgun = true
        kareler(30)
        assertTrue("kamera yerine oturuyor", s.donguBekliyor)
        kareler(400)
        assertFalse(s.donguBekliyor)
        assertEquals(0, acik)
        assertEquals(1f, s.dunyaGorunumu.scaleX)
        assertEquals(0f, s.dunyaGorunumu.translationY)
        assertEquals(listOf(1, 0), izler)
        // poll: the source moves again without anyone calling uyandir()
        durgun = false
        hiz = 3f
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SurusSahnesi.YOKLAMA_MS + 10))
        assertTrue("yoklama yeniden başlattı", s.donguBekliyor)
        s.dur()
        assertEquals(0, acik)
    }

    @Test
    fun ilkKareDtSifir_dtKirpilir_kareBasinaTekOkuma() {
        hiz = 10f
        durgun = false
        var dinleyici = 0
        var sonHiz = -1f
        s.kareDinleyici = { _, h -> dinleyici++; sonHiz = h }
        s.basla()
        kareler(1)
        assertEquals("ilk kare dt = 0", 0.0, s.model.mesafe, 0.0)
        assertEquals(1, okuma)
        assertEquals(1, dinleyici)
        assertEquals(10f, sonHiz)
        assertEquals(10f, s.sonHizMs)
        kareler(1, 1_000_000_000L) // a 1 s stall
        assertEquals("dt 0,05 sn'ye kırpılır", 0.5, s.model.mesafe, 1e-6)
        kareler(1)
        assertEquals(0.5 + 10.0 / 60.0, s.model.mesafe, 1e-4)
        assertEquals(3, okuma)
        assertEquals(3, dinleyici)
        // after a pause the first frame is again dt = 0
        s.dur()
        simdi += 5_000_000_000L
        s.basla()
        val m = s.model.mesafe
        kareler(1)
        assertEquals(m, s.model.mesafe, 0.0)
    }

    @Test
    fun tekerlerVeKatmanlarModeliIzler() {
        hiz = 21f / 3.6f // blur 0.5
        durgun = false
        s.basla()
        kareler(120)
        val on = s.tekerOnGorunumleri
        assertEquals(1f, on[0].alpha)
        assertEquals(0.5f, on[1].alpha, 1e-4f)
        assertEquals(0f, on[2].alpha)
        // counter-clockwise: View rotation = −angle
        assertEquals((-s.model.tekerAcisi).toFloat(), on[0].rotation, 1e-3f)
        assertEquals(on[0].rotation, s.tekerArkaGorunumleri[0].rotation, 0f)
        assertTrue("kamera küçüldü", s.dunyaGorunumu.scaleX < 1f)
        assertEquals(SurusYerlesim.KAMERA_PX, s.dunyaGorunumu.pivotX)
        assertEquals(SurusYerlesim.KAMERA_PY, s.dunyaGorunumu.pivotY)
        assertEquals(21f / 80f, s.izGorunumu.alpha, 1e-4f)
        // wheel centre on the sprite centre
        val v = on[0]
        assertEquals(SurusYerlesim.ON_X, v.left + v.translationX + v.pivotX, 1e-3f)
        assertEquals(SurusYerlesim.TEKER_Y, v.top + v.translationY + v.pivotY, 1e-3f)
        // 130 km/h: only the full-blur sprite
        hiz = 130f / 3.6f
        kareler(10)
        assertEquals(0f, on[0].alpha)
        assertEquals(0f, on[1].alpha)
        assertEquals(1f, on[2].alpha)
    }

    @Test
    fun yansimaYalnizSupururkenCizer() {
        hiz = 20f
        durgun = false
        s.basla()
        kareler(1)
        val y = s.yansimaGorunumu
        var cizdi = 0
        var bos = 0
        repeat(240) { // 4 s at 20 m/s = 80 m: 35 m period
            kareler(1)
            if (y.ciziyor) cizdi++ else bos++
        }
        // sweeps at 0, 35 and 70 m, 6.3 m each = 18.9 m of 80 m (1/3 m per frame) ≈ 57 frames
        assertTrue("$cizdi", cizdi in 54..59)
        assertTrue(bos > cizdi)
        // a band on the body is drawn inside the body box
        while ((y.konum ?: -1f) < 0.3f) kareler(1)
        y.measure(View.MeasureSpec.makeMeasureSpec(SurusYerlesim.GOVDE_W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(SurusYerlesim.GOVDE_H, View.MeasureSpec.EXACTLY))
        y.layout(0, 0, SurusYerlesim.GOVDE_W, SurusYerlesim.GOVDE_H)
        val bmp = Bitmap.createBitmap(SurusYerlesim.GOVDE_W, SurusYerlesim.GOVDE_H, Bitmap.Config.ARGB_8888)
        y.draw(android.graphics.Canvas(bmp))
        val r = y.sonDikdortgen
        assertFalse(r.isEmpty)
        assertTrue(r.left >= 0f && r.right <= SurusYerlesim.GOVDE_W)
        assertTrue("bant dar (${r.width()})", r.width() < 0.34f * SurusYerlesim.KUTU_W + 100f)
        // the band starts outside the body (−40 %): nothing is drawn there
        y.konum = -0.4f
        y.draw(android.graphics.Canvas(bmp))
        assertTrue(y.sonDikdortgen.isEmpty)
    }

    @Test
    fun durgundanBasla_ilkKareDinlenmeGorunumu() {
        hiz = 90f / 3.6f
        durgun = false
        s.basla()
        kareler(200)
        assertTrue(s.dunyaGorunumu.scaleX < 0.99f)
        s.durgundanBasla(1000)
        assertEquals(1f, s.dunyaGorunumu.scaleX)
        val aci = s.model.tekerAcisi
        kareler(1)
        assertEquals("rampa sıfırdan başlar", aci, s.model.tekerAcisi, 1e-6)
        assertEquals(1f, s.dunyaGorunumu.scaleX, 1e-6f)
        kareler(30)
        assertTrue(s.model.hiz in 0.1f..hiz * 0.7f)
        kareler(60)
        assertEquals(hiz, s.model.hiz, 1e-4f)
        assertEquals("HUD gerçek hızı alır", hiz, s.sonHizMs)
    }

    @Test
    fun dokunmaAlmaz_erisilebilirlikteGizli() {
        val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 300f, 500f, 0)
        assertFalse(s.dispatchTouchEvent(e))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, s.importantForAccessibility)
        assertFalse(s.isClickable)
    }

    @Test
    fun birakSonrasiKareYok() {
        hiz = 5f
        durgun = false
        s.basla()
        kareler(2)
        s.birak()
        assertTrue(kaynak.bekleyen.isEmpty())
        s.basla()
        assertTrue(kaynak.bekleyen.isEmpty())
        assertNotEquals(0, okuma)
    }
}
