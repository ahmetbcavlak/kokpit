package dev.kokpit.anaekran.hareket

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ekran.Gecis contract for YumusakGecis (K-93 timings), including rapid repeated calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class YumusakGecisTest {

    private lateinit var a: View
    private lateinit var b: View
    private lateinit var c: View
    private val g = YumusakGecis(kayma = { 6f })

    @Before
    fun hazirla() {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        val kap = FrameLayout(act)
        a = View(act); b = View(act).apply { visibility = View.GONE }; c = View(act).apply { visibility = View.GONE }
        listOf(a, b, c).forEach { kap.addView(it) }
        act.setContentView(kap)
        HareketTestYardim.gercekSure()
    }

    private fun bekle(ms: Long) = HareketTestYardim.bekle(ms)

    private fun sonDurum(eski: View, yeni: View) {
        assertEquals(View.GONE, eski.visibility)
        assertEquals(1f, eski.alpha)
        assertEquals(0f, eski.translationY)
        assertEquals(View.VISIBLE, yeni.visibility)
        assertEquals(1f, yeni.alpha)
        assertEquals(0f, yeni.translationY)
    }

    @Test
    fun sozlesme_eskiGoneYeniVisibleBittiBirKez() {
        var n = 0
        g.gec(a, b) { n++ }
        assertEquals(View.VISIBLE, b.visibility)
        assertEquals(0, n)
        bekle(150)
        assertEquals("0,2 sn gecikme: yeni henüz görünmez", 0f, b.alpha)
        assertEquals("kayma başta", 6f, b.translationY)
        assertTrue("eski hemen solmaya başlar", a.alpha < 1f)
        bekle(450) // 0.6 s: new one half-way
        assertTrue("ortasında yarı saydam", b.alpha > 0f && b.alpha < 1f)
        assertTrue("konum ortada", b.translationY > 0f && b.translationY < 6f)
        bekle(350) // 0.95 s: alpha (0.2 + 0.8 = 1.0 s) not done yet
        assertEquals(0, n)
        bekle(250) // 1.2 s: all done (1.1 s)
        sonDurum(a, b)
        assertEquals(1, n)
        bekle(500)
        assertEquals(1, n)
    }

    @Test
    fun yeniCagriSurenGecisiAnindaBitirir() {
        var n1 = 0
        var n2 = 0
        g.gec(a, b) { n1++ }
        bekle(300)
        g.gec(b, c) { n2++ }
        // The first one is finished at once.
        assertEquals(1, n1)
        assertEquals(View.GONE, a.visibility)
        assertEquals(1f, a.alpha)
        bekle(1200)
        sonDurum(b, c)
        assertEquals(1, n1)
        assertEquals(1, n2)
        assertEquals(View.GONE, a.visibility)
    }

    @Test
    fun ileriGeriHizliCagri() {
        var n = 0
        g.gec(a, b) { n++ }
        g.gec(b, a) { n++ }
        g.gec(a, b) { n++ }
        bekle(1200)
        sonDurum(a, b)
        assertEquals(3, n)
    }

    @Test
    fun hareketIziHizliCagrilardaDengeli() {
        var acik = 0
        g.izci = object : HareketIzci {
            override fun hareketBasladi() { acik++ }
            override fun hareketBitti() { acik-- }
        }
        g.gec(a, b) {}
        g.gec(b, c) {}
        assertEquals(1, acik)
        bekle(1200)
        assertEquals(0, acik)
    }

    @Test
    fun eskiYoksaYalnizGirer() {
        var n = 0
        g.gec(null, b) { n++ }
        bekle(1200)
        assertEquals(View.VISIBLE, b.visibility)
        assertEquals(1f, b.alpha)
        assertEquals(1, n)
    }

    @Test
    fun olculmemisGorunumKatmansizGecer() {
        // b is GONE, never measured (0×0): the fade runs without a hardware layer (the first screen
        // change of the app; withLayer would build a layer HWUI cannot use).
        assertEquals(0, b.width)
        g.gec(a, b) {}
        bekle(300)
        assertEquals(View.LAYER_TYPE_NONE, b.layerType)
        bekle(1200)
        sonDurum(a, b)

        // Measured views keep the layer for the fade, and drop it at the end.
        for (v in listOf(b, c)) {
            v.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 100, 100)
        }
        g.gec(b, c) {}
        bekle(300)
        assertEquals(View.LAYER_TYPE_HARDWARE, c.layerType)
        assertEquals(View.LAYER_TYPE_HARDWARE, b.layerType)
        bekle(1200)
        sonDurum(b, c)
        assertEquals(View.LAYER_TYPE_NONE, c.layerType)
    }

    @Test
    fun sureler() {
        assertEquals(800L, Zamanlama.ARAYUZ_SAYDAMLIK_MS)
        assertEquals(900L, Zamanlama.ARAYUZ_KONUM_MS)
        assertEquals(200L, Zamanlama.gecikme(0))
        assertEquals(250L, Zamanlama.gecikme(1))
        assertEquals(250L, Zamanlama.gecikme(5))
        assertEquals(1100L, YumusakGecis.SURE_MS)
        // demo cubic-bezier(.4,0,.2,1): slow start, fast middle, soft landing
        val e = Zamanlama.ARAYUZ_EGRISI
        assertEquals(0f, e.getInterpolation(0f), 1e-4f)
        assertEquals(1f, e.getInterpolation(1f), 1e-4f)
        assertTrue(e.getInterpolation(0.5f) > 0.7f)
        // smootherstep: symmetric, flat at both ends
        val s = Zamanlama.SAHNE_EGRISI
        assertEquals(0.5f, s.getInterpolation(0.5f), 1e-6f)
        assertTrue(s.getInterpolation(0.05f) < 0.002f)
        assertTrue(s.getInterpolation(0.95f) > 0.998f)
    }

    @Test
    fun girisYardimcisi_gecikmeSaydamlikKonum() {
        val anim = Zamanlama.giris(b, sira = 1, kayma = 10f)
        assertEquals(0f, b.alpha)
        assertEquals(10f, b.translationY)
        assertEquals(View.VISIBLE, b.visibility)
        anim.start()
        bekle(230)
        assertEquals("ikinci katman 0,25 sn gecikir", 0f, b.alpha)
        bekle(500)
        assertTrue(b.alpha > 0f && b.alpha < 1f)
        bekle(500) // 1.23 s: alpha done (1.05 s), rise (1.15 s) done
        assertEquals(1f, b.alpha)
        assertEquals(0f, b.translationY)
    }
}
