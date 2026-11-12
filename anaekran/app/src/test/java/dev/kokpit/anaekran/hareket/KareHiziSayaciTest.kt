package dev.kokpit.anaekran.hareket

import android.app.Activity
import android.view.Choreographer
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.tema.Yazi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The counter view: callback loop life cycle, what it shows, no self-sustained redraws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KareHiziSayaciTest {

    /** Collects posted callbacks instead of waiting for VSYNC. */
    private class SahteKaynak : KareKaynagi {
        val bekleyen = ArrayList<Choreographer.FrameCallback>()
        var istekSayisi = 0
        override fun iste(cb: Choreographer.FrameCallback) { bekleyen += cb; istekSayisi++ }
        override fun birak(cb: Choreographer.FrameCallback) { bekleyen.remove(cb) }

        /** Delivers one VSYNC at [t]. */
        fun vsync(t: Long) {
            val l = bekleyen.toList()
            bekleyen.clear()
            l.forEach { it.doFrame(t) }
        }
    }

    private lateinit var a: Activity
    private lateinit var kaynak: SahteKaynak
    private lateinit var sayac: KareHiziSayaci
    private var simdi = 0L
    private val vs = 16_666_667L

    @Before
    fun hazirla() {
        a = Robolectric.buildActivity(Activity::class.java).setup().get()
        Yazi.yukle(a.resources)
        kaynak = SahteKaynak()
        sayac = KareHiziSayaci(a, kaynak) { simdi }
        a.setContentView(FrameLayout(a).apply { addView(sayac, FrameLayout.LayoutParams(96, 20)) })
    }

    /** What ViewRootImpl does after every drawn frame. */
    private fun cizimBildir() {
        val vto = sayac.viewTreeObserver
        ViewTreeObserver::class.java.getDeclaredMethod("dispatchOnDraw").apply { isAccessible = true }.invoke(vto)
    }

    private fun kareler(n: Int, ciz: Boolean) {
        repeat(n) {
            simdi += vs
            if (ciz) cizimBildir()
            kaynak.vsync(simdi)
        }
    }

    @Test
    fun baslaTekGeriCagriKurarDurHepsiniKaldirir() {
        sayac.basla()
        assertEquals(1, kaynak.bekleyen.size)
        kareler(5, ciz = true)
        assertEquals(1, kaynak.bekleyen.size)
        sayac.dur()
        assertTrue(kaynak.bekleyen.isEmpty())
        assertFalse(sayac.donguBekliyor)
        // A drawn frame after dur() must not restart the loop.
        cizimBildir()
        assertTrue(kaynak.bekleyen.isEmpty())
    }

    @Test
    fun hareketteFpsGosterir() {
        sayac.basla()
        kareler(40, ciz = true)
        assertEquals(60, sayac.gosterilen?.fps)
        assertEquals(a.getString(R.string.kare_hizi, 60, 17), sayac.text.toString())
    }

    @Test
    fun durgunEkrandaDonguDurur_veDegerSolukKalir() {
        sayac.basla()
        kareler(40, ciz = true)
        val renk = sayac.currentTextColor
        val metin = sayac.text.toString()
        // The window still holding the last draws reports them; the next all-still one dims.
        kareler(70, ciz = false)
        // The loop stopped by itself: no VSYNC wake-ups on a still screen.
        assertTrue(kaynak.bekleyen.isEmpty())
        val istek = kaynak.istekSayisi
        kareler(100, ciz = false)
        assertEquals(istek, kaynak.istekSayisi)
        assertEquals(metin, sayac.text.toString())
        assertTrue(android.graphics.Color.alpha(sayac.currentTextColor) < android.graphics.Color.alpha(renk))
        // The next drawn frame restarts it.
        cizimBildir()
        assertEquals(1, kaynak.bekleyen.size)
    }

    @Test
    fun bilinenHareketCizimsizDeDonguyuCalistirir_veTakilmayiGosterir() {
        sayac.basla()
        kareler(40, ciz = false)
        assertTrue("durgun: döngü durdu", kaynak.bekleyen.isEmpty())
        sayac.hareketBasladi()
        assertEquals("hareket döngüyü başlatır", 1, kaynak.bekleyen.size)
        // 8 fps stutter for 1 s.
        repeat(60) { k ->
            simdi += vs
            if (k % 8 == 0) cizimBildir()
            kaynak.vsync(simdi)
        }
        assertEquals(8, sayac.gosterilen?.fps)
        sayac.hareketBitti()
        kareler(70, ciz = false)
        assertTrue("hareket bitti, durgun: döngü durdu", kaynak.bekleyen.isEmpty())
    }

    @Test
    fun hicHareketYoksaCizgiGosterir() {
        sayac.basla()
        kareler(40, ciz = false)
        assertNull(sayac.gosterilen)
        assertEquals(a.getString(R.string.kare_hizi_yok), sayac.text.toString())
        assertTrue(kaynak.bekleyen.isEmpty())
    }

    @Test
    fun gercekChoreographerIleDurSonrasiCagriKalmaz() {
        val gercek = KareHiziSayaci(a)
        (sayac.parent as FrameLayout).addView(gercek)
        gercek.basla()
        assertTrue(gercek.donguBekliyor)
        gercek.dur()
        assertFalse(gercek.donguBekliyor)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertFalse(gercek.donguBekliyor)
    }
}
