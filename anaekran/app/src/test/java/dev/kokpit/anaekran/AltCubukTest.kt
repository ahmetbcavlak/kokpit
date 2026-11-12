package dev.kokpit.anaekran

import android.content.Intent
import android.view.View
import dev.kokpit.anaekran.arayuz.AltCubuk.Simge
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.uygulama.PaketAcici
import dev.kokpit.anaekran.uygulama.UygulamaListesi
import dev.kokpit.anaekran.veri.AracVerisi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** Bottom bar behaviour: opening other apps, the calm note, panels opening and closing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class AltCubukTest {

    private lateinit var c: ActivityController<MainActivity>
    private val a get() = c.get()
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        AracVerisi.sifirla()
    }

    @After
    fun kapat() {
        if (::c.isInitialized) c.pause().stop().destroy()
    }

    @Test
    fun paketYoksaCokmezSakinNotGosterir() {
        assertEquals(PaketAcici.Sonuc.YOK, PaketAcici.ac(app, PaketAcici.ZLINK))
        assertEquals(PaketAcici.Sonuc.YOK, PaketAcici.ac(app, PaketAcici.MEDYA))

        c = TestYardim.ac()
        for (s in listOf(Simge.CARPLAY, Simge.HARITA, Simge.TELEFON)) {
            a.katmanlar.altCubuk.dugme(s).performClick()
            assertEquals(View.VISIBLE, a.kisaNot.visibility)
            assertTrue(a.kisaNot.text.toString(), a.kisaNot.text.contains("ZLink"))
        }
        a.katmanlar.altCubuk.dugme(Simge.MUZIK).performClick()
        assertTrue(a.kisaNot.text.toString(), a.kisaNot.text.startsWith("Müzik açılamadı"))
        assertNull(shadowOf(app).nextStartedActivity)
        assertFalse(a.isFinishing)

        // The note goes away by itself.
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertEquals(View.GONE, a.kisaNot.visibility)
    }

    @Test
    fun paketVarsaYeniGorevdeAcar() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        TestYardim.uygulamaKur(PaketAcici.MEDYA, "Medya")
        c = TestYardim.ac()

        a.katmanlar.altCubuk.dugme(Simge.CARPLAY).performClick()
        val zlink = shadowOf(app).nextStartedActivity
        assertNotNull(zlink)
        assertEquals(PaketAcici.ZLINK, zlink.component?.packageName)
        assertTrue(zlink.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        a.katmanlar.altCubuk.dugme(Simge.MUZIK).performClick()
        assertEquals(PaketAcici.MEDYA, shadowOf(app).nextStartedActivity.component?.packageName)
        assertEquals(View.GONE, a.kisaNot.visibility)
    }

    @Test
    fun klimaPaneliAcilirKapanir() {
        c = TestYardim.ac()
        val e = a.katmanlar.ekranlar
        e.goster(Ekran.PARK)
        val klima = a.katmanlar.altCubuk.klimaAlani

        // Open, close with the bar again (toggle).
        klima.performClick()
        assertEquals(Ekran.KLIMA, e.gecerli)
        klima.performClick()
        assertEquals(Ekran.PARK, e.gecerli)

        // Open, a tap on the background (outside the card) closes.
        klima.performClick()
        e.gorunum(Ekran.KLIMA).gorunum.performClick()
        assertEquals(Ekran.PARK, e.gecerli)

        // Open, the X closes.
        klima.performClick()
        val kapat = e.gorunum(Ekran.KLIMA).gorunum.findViewsWithText("Kapat")
        assertEquals(1, kapat.size)
        kapat[0].performClick()
        assertEquals(Ekran.PARK, e.gecerli)

        // Back key closes too; on a base screen it does not.
        klima.performClick()
        assertTrue(e.geri())
        assertFalse(e.geri())
    }

    @Test
    fun uygulamalarListesiSiraliKendisiHaric() {
        TestYardim.uygulamaKur("org.b", "Şimşek")
        TestYardim.uygulamaKur("org.a", "Ayarlar")
        TestYardim.uygulamaKur("org.c", "Çay")
        TestYardim.uygulamaKur("org.d", "Zeka")
        c = TestYardim.ac()

        val liste = UygulamaListesi.oku(a, 64).map { it.ad }
        assertFalse(liste.any { it == "Kokpit" })
        // Turkish order: Ç after C, Ş after S.
        assertEquals(listOf("Ayarlar", "Çay", "Şimşek", "Zeka"), liste)

        a.katmanlar.altCubuk.dugme(Simge.UYGULAMALAR).performClick()
        TestYardim.bosalt()
        assertEquals(Ekran.UYGULAMALAR, a.katmanlar.ekranlar.gecerli)
        val hucre = a.katmanlar.ekran.findViewsWithText("Çay").first { it.isClickable }
        hucre.performClick()
        assertEquals("org.c", shadowOf(app).nextStartedActivity.component?.packageName)

        // The licence line at the end of the list shows the OFL texts; back closes them first.
        a.katmanlar.ekran.findViewsWithText("Yazı tipi lisansları").first { it.isClickable }.performClick()
        // (findViewsWithText lower-cases with the default locale; avoid capital I.)
        val metin = a.katmanlar.ekran.findViewsWithText("Barlow Project Authors")
        assertEquals(1, metin.size)
        assertTrue((metin.first() as android.widget.TextView).text.contains("Geist Project Authors"))
        @Suppress("DEPRECATION") a.onBackPressed()
        assertEquals(Ekran.UYGULAMALAR, a.katmanlar.ekranlar.gecerli)

        // Tapping the apps icon again goes back.
        a.katmanlar.altCubuk.dugme(Simge.UYGULAMALAR).performClick()
        assertEquals(a.katmanlar.ekranlar.temel, a.katmanlar.ekranlar.gecerli)
    }
}

/** View.findViewsWithText as a list. */
private fun View.findViewsWithText(metin: String): List<View> {
    val out = ArrayList<View>()
    findViewsWithText(out, metin, View.FIND_VIEWS_WITH_TEXT or View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
    return out
}
