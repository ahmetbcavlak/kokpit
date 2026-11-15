package dev.kokpit.anaekran

import android.app.Activity
import dev.kokpit.anaekran.ekran.UyariKatmani.Gorunum
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.KapiOzellikleri
import dev.kokpit.anaekran.veri.SahteVeri
import dev.kokpit.anaekran.veri.Teshis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The door warning across a stay in another app (0.2.2 review R1 ÖNEMLİ-1), with the properties
 * as the door source: while Kokpit is behind, the state is unknown, so a door closed meanwhile
 * gives no late "Kapı kapandı." on return and a door still open shows its warning again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class KapiDonusTest {

    private lateinit var c: ActivityController<MainActivity>
    private val a get() = c.get()

    /** Fake cardoor1..5 ("" = not set). */
    private val degerler = HashMap<String, String>()

    /** Passes wait here; [gecis] runs the next one (the 250 ms loop by hand). */
    private val bekleyen = ArrayList<Runnable>()

    private val okuyucu = object : KapiOzellikleri.OzellikOkuyucu {
        override val ad = "yansıma"
        override fun oku(ozellik: String): String = degerler[ozellik] ?: ""
    }

    private val zamanlayici = object : KapiOzellikleri.Zamanlayici {
        override fun sonra(gecikmeMs: Long, r: Runnable) { bekleyen += r }
        override fun temizle() = bekleyen.clear()
        override fun bitir() = bekleyen.clear()
    }

    /** The data hook's door part only (no GPS, no climate receiver). */
    private inner class KapiBaglayici : Baglayici {
        var o: KapiOzellikleri? = null
        override fun kur(activity: Activity, katmanlar: Katmanlar) {
            o = KapiOzellikleri({ okuyucu }, { "0" }, zamanlayici)
        }
        override fun basla() { o?.basla() }
        override fun dur() { o?.dur() }
        override fun birak() { o?.birak() }
    }

    private fun ayarla(vararg d: String) = d.forEachIndexed { i, v -> degerler[KapiOzellikleri.ADLAR[i]] = v }

    private fun gecis() {
        val r = bekleyen.removeAt(0)
        r.run()
        TestYardim.bosalt()
    }

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        KapiOzellikleri.testSifirla()
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
        Baglayicilar.uret = { listOf(KapiBaglayici(), TestYardim.sahteHareket()) }
        SahteVeri.uygula(SahteVeri.parkGece())
    }

    @After
    fun kapat() {
        if (::c.isInitialized) c.pause().stop().destroy()
        Tema.zorla(null)
        AracVerisi.sifirla()
        KapiOzellikleri.testSifirla()
        Teshis.sifirla()
    }

    private fun acikIleAc() {
        ayarla("2", "1", "1", "1", "1")
        c = TestYardim.acVeGec()
        TestYardim.gecisleriBitir()
        while (bekleyen.isNotEmpty() && AracVerisi.durum.kapiAcik != true) gecis()
        assertTrue(KapiOzellikleri.birincil)
        assertEquals(true, AracVerisi.durum.kapiAcik)
        assertEquals(Gorunum.PARK_BASLIGI, a.uyari.gorunum)
    }

    @Test
    fun arkadaykenKapandi_donusteOnayYok() {
        acikIleAc()
        c.pause() // CarPlay in front
        assertNull(AracVerisi.durum.kapiAcik)
        assertEquals(Gorunum.YOK, a.uyari.gorunum)

        ayarla("1", "1", "1", "1", "1") // closed while away
        TestYardim.bekle(60_000)
        c.resume()
        gecis()
        assertEquals(false, AracVerisi.durum.kapiAcik)
        assertEquals("no late \"Kapı kapandı.\"", Gorunum.YOK, a.uyari.gorunum)
        assertFalse(a.uyari.metinler.contains("Kapı kapandı."))
        TestYardim.bekle(500)
        assertEquals(Gorunum.YOK, a.uyari.gorunum)

        // A real close in front of the user still confirms.
        ayarla("2", "1", "1", "1", "1")
        gecis()
        assertEquals(Gorunum.PARK_BASLIGI, a.uyari.gorunum)
        ayarla("1", "1", "1", "1", "1")
        gecis()
        assertEquals(Gorunum.KAPANDI_PARK, a.uyari.gorunum)
    }

    @Test
    fun arkadaykenAcikKaldi_donusteUyariVar_onayYok() {
        acikIleAc()
        // Silenced by a long press before leaving (0.2's stuck-warning rule).
        a.uyari.sustur()
        assertTrue(a.uyari.susturuldu)
        assertEquals(Gorunum.YOK, a.uyari.gorunum)

        c.pause()
        assertNull(AracVerisi.durum.kapiAcik)
        TestYardim.bekle(60_000)
        c.resume()
        gecis()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        // Decision (KapiOzellikleri notes): the unknown in between is a change, so the warning is back.
        assertFalse(a.uyari.susturuldu)
        assertEquals(Gorunum.PARK_BASLIGI, a.uyari.gorunum)
        assertTrue(a.uyari.metinler.contains("Kapı açık"))
        assertFalse(a.uyari.metinler.contains("Kapı kapandı."))
    }
}
