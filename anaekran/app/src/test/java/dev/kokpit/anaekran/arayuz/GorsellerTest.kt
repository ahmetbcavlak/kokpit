package dev.kokpit.anaekran.arayuz

import android.graphics.Bitmap
import android.os.Looper
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.uygulama.UygulamaListesi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** One decode per picture however many ask; icons never queue in front of a scene. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class GorsellerTest {

    private val sahneKuyrugu = ArrayList<Runnable>()
    private val simgeKuyrugu = ArrayList<Runnable>()

    @After
    fun temizle() {
        Arkaplan.yurutucu = Executor { it.run() }
        UygulamaListesi.yurutucu = Executor { it.run() }
        Gorseller.bosalt()
    }

    private fun bosalt() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun ayniGorselTekIsteCozulur() {
        Gorseller.bosalt()
        Arkaplan.yurutucu = Executor { sahneKuyrugu += it }
        val res = RuntimeEnvironment.getApplication().resources
        val once = Gorseller.cozumSayisi
        val gelen = ArrayList<Bitmap>()
        // The scene layer and the motion layer ask for the same scene at once (MainActivity.sahneGecisi).
        Gorseller.al(res, R.drawable.sahne_park) { gelen += it }
        Gorseller.al(res, R.drawable.sahne_park) { gelen += it }
        assertEquals(1, sahneKuyrugu.size)
        assertEquals(once + 1, Gorseller.cozumSayisi)
        sahneKuyrugu.removeAt(0).run()
        bosalt()
        assertEquals(2, gelen.size)
        assertSame(gelen[0], gelen[1])
        // Cached now: answered at once, no new job.
        Gorseller.al(res, R.drawable.sahne_park) { gelen += it }
        assertEquals(3, gelen.size)
        assertTrue(sahneKuyrugu.isEmpty())
        // Another picture is its own job.
        Gorseller.al(res, R.drawable.sahne_surus) { gelen += it }
        assertEquals(1, sahneKuyrugu.size)
    }

    @Test
    fun simgelerAyriIsParcacigindaVeSirayla() {
        Arkaplan.yurutucu = Executor { sahneKuyrugu += it }
        UygulamaListesi.yurutucu = Executor { simgeKuyrugu += it }
        val adim = ArrayList<String>()
        UygulamaListesi.yukle(RuntimeEnvironment.getApplication(), 48, { adim += "adlar" }, { adim += "simgeler" })
        // Both steps on the list's own executor, in order; the scene worker stays free.
        assertEquals(2, simgeKuyrugu.size)
        assertTrue(sahneKuyrugu.isEmpty())
        simgeKuyrugu.toList().forEach { it.run() }
        bosalt()
        assertEquals(listOf("adlar", "simgeler"), adim)
    }
}
