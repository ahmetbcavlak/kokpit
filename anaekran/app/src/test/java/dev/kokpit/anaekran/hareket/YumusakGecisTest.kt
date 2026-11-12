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

/** ekran.Gecis contract for YumusakGecis, including rapid repeated calls. */
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
        bekle(100)
        assertTrue("ortasında yarı saydam", b.alpha > 0f && b.alpha < 1f)
        bekle(300)
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
        bekle(60)
        g.gec(b, c) { n2++ }
        // The first one is finished at once.
        assertEquals(1, n1)
        assertEquals(View.GONE, a.visibility)
        assertEquals(1f, a.alpha)
        bekle(400)
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
        bekle(400)
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
        bekle(400)
        assertEquals(0, acik)
    }

    @Test
    fun eskiYoksaYalnizGirer() {
        var n = 0
        g.gec(null, b) { n++ }
        bekle(400)
        assertEquals(View.VISIBLE, b.visibility)
        assertEquals(1f, b.alpha)
        assertEquals(1, n)
    }
}
