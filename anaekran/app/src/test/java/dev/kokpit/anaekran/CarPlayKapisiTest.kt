package dev.kokpit.anaekran

import android.Manifest
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.kokpit.anaekran.arayuz.AltCubuk.Simge
import dev.kokpit.anaekran.arayuz.CarPlayKapisi
import dev.kokpit.anaekran.arayuz.CarPlayKapisi.Durum
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.hareket.HareketTestYardim
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.uygulama.PaketAcici
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.Hiz
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * The CarPlay door (0.2.2, K-115, Y-22): ZLINK opened with a scale-up from the CarPlay button,
 * Kokpit 1 → 0.94 and 58 % darker in 320 ms, back in 360 ms after onResume, no intro replay,
 * one launch per door, consistent state when the door is undone half-way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class CarPlayKapisiTest {

    private lateinit var c: ActivityController<MainActivity>
    private val a get() = c.get()
    private val app get() = RuntimeEnvironment.getApplication()
    private val kok get() = a.katmanlar.kok
    private val kap get() = kok.parent as ViewGroup
    private val ortu get() = kap.getChildAt(1)
    private val kapi get() = a.kapi
    private val dugme get() = a.katmanlar.altCubuk.dugme(Simge.CARPLAY)

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
    }

    @After
    fun kapat() {
        if (::c.isInitialized) c.pause().stop().destroy()
        ShadowChoreographer.setPaused(false)
        Tema.zorla(null)
        AracVerisi.sifirla()
        Hiz.yumusatici.sifirla()
    }

    /** Opens on park with the intro cut and finished, then real 16 ms frames. */
    private fun ac() {
        c = TestYardim.acVeGec()
        TestYardim.gecisleriBitir()
        HareketTestYardim.gercekSure()
    }

    private fun bekle(ms: Long) = HareketTestYardim.bekle(ms)

    private fun dinlenmede() {
        assertEquals(Durum.KAPALI, kapi.durum)
        assertEquals(1f, kok.scaleX, 0f)
        assertEquals(1f, kok.scaleY, 0f)
        assertEquals(0f, ortu.alpha, 0f)
        assertEquals(View.GONE, ortu.visibility)
        assertNull(kap.background)
        assertEquals(View.LAYER_TYPE_NONE, kok.layerType)
    }

    private fun geride() {
        assertEquals(CarPlayKapisi.OLCEK, kok.scaleX, 1e-4f)
        assertEquals(CarPlayKapisi.OLCEK, kok.scaleY, 1e-4f)
        assertEquals(CarPlayKapisi.ORTU_ALFA, ortu.alpha, 1e-4f)
    }

    private fun baslatilan() = shadowOf(a).nextStartedActivityForResult

    @Test
    fun carplaySimgedenBuyuyerekAcilirKokpitGeriCekilir() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dinlenmede()
        dugme.performClick()

        // ZLINK started in the tap's turn, with a scale-up from the button's rectangle.
        val b = baslatilan()
        assertNotNull(b)
        assertEquals(PaketAcici.ZLINK, b.intent.component?.packageName)
        val o = b.options
        assertNotNull("ActivityOptions bundle", o)
        assertEquals(ANIM_SCALE_UP, o!!.getInt("android:activity.animType"))
        assertEquals(dugme.width, o.getInt("android:activity.animWidth"))
        assertEquals(dugme.height, o.getInt("android:activity.animHeight"))
        assertTrue(dugme.width > 0 && dugme.height > 0)
        // startX/Y are relative to the source view; the bundle holds them on screen.
        val xy = IntArray(2).also { dugme.getLocationOnScreen(it) }
        assertEquals(xy[0], o.getInt("android:activity.animStartX"))
        assertEquals(xy[1], o.getInt("android:activity.animStartY"))
        assertEquals(174, xy[0]) // Y-22 / tokens: the CarPlay button at x 174, y 932
        assertEquals(932, xy[1])

        // Pivot: the CarPlay button's centre (Y-22: 216, 978 design px; mdpi = 1:1).
        assertEquals(216f, kok.pivotX, 0.5f)
        assertEquals(978f, kok.pivotY, 0.5f)
        assertEquals(Durum.ACILIYOR, kapi.durum)
        assertEquals(View.VISIBLE, ortu.visibility)
        assertEquals(CarPlayKapisi.CEVRE, (kap.background as android.graphics.drawable.ColorDrawable).color)

        // Half-way: between, through a hardware layer.
        bekle(160)
        assertTrue(kok.scaleX in CarPlayKapisi.OLCEK + 0.001f..0.999f)
        assertTrue(ortu.alpha in 0.01f..CarPlayKapisi.ORTU_ALFA - 0.01f)
        assertEquals(View.LAYER_TYPE_HARDWARE, kok.layerType)
        // The scale and the cover move together (one value).
        val k = (1f - kok.scaleX) / (1f - CarPlayKapisi.OLCEK)
        assertEquals(k * CarPlayKapisi.ORTU_ALFA, ortu.alpha, 1e-3f)

        // 320 ms (+ up to two frames: the animator's first tick and its end): stepped back, layer gone.
        bekle(CarPlayKapisi.ACILIS_MS - 160 + 32)
        geride()
        assertEquals(View.LAYER_TYPE_NONE, kok.layerType)
        assertEquals(Durum.ACILIYOR, kapi.durum)
    }

    @Test
    fun haritaVeTelefonEskiDavranista() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        for (s in listOf(Simge.HARITA, Simge.TELEFON)) {
            a.katmanlar.altCubuk.dugme(s).performClick()
            val b = baslatilan()
            assertEquals(PaketAcici.ZLINK, b.intent.component?.packageName)
            assertNull(b.options)
            dinlenmede()
        }
    }

    @Test
    fun paketYoksaHicKipirdamazSakinNot() {
        ac()
        dugme.performClick()
        dinlenmede()
        assertNull(baslatilan())
        assertEquals(View.VISIBLE, a.kisaNot.visibility)
        assertTrue(a.kisaNot.text.toString(), a.kisaNot.text.contains("ZLink"))
        bekle(CarPlayKapisi.DONUS_MS + 50)
        dinlenmede()
    }

    @Test
    fun donusteAydinlanirAcilisKlibiOynamaz() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        val klipler = TestYardim.oynaticilar.size
        dugme.performClick()
        assertNotNull(baslatilan())
        bekle(CarPlayKapisi.ACILIS_MS + 32)
        c.pause()
        assertEquals(Durum.ARKADA, kapi.durum)
        assertNotNull(kapi.sonAyrilmaMs)
        geride()
        bekle(5000) // away in CarPlay: nothing comes back by itself
        assertEquals(Durum.ARKADA, kapi.durum)
        geride()

        c.resume()
        assertEquals(Durum.DONUYOR, kapi.durum)
        assertFalse(a.acilisSuruyor)
        // The return starts with the first frame drawn after the resume (at the latest the fallback).
        var t = 0L
        while (!kapi.oynuyor && t <= CarPlayKapisi.DONUS_YEDEK_MS) { bekle(16); t += 16 }
        assertTrue("return started after $t ms", kapi.oynuyor)
        geride()
        bekle(180)
        assertTrue(kok.scaleX in CarPlayKapisi.OLCEK + 0.001f..0.999f)
        assertEquals(View.LAYER_TYPE_HARDWARE, kok.layerType)
        bekle(CarPlayKapisi.DONUS_MS - 180 - 32)
        assertEquals("not before 360 ms", Durum.DONUYOR, kapi.durum)
        bekle(32 + 48) // 360 ms + up to three frames
        dinlenmede()

        // No intro clip, no intro screen: the park screen is simply back.
        assertEquals(klipler, TestYardim.oynaticilar.size)
        assertFalse(a.acilisSuruyor)
        assertEquals(Ekran.PARK, a.katmanlar.ekranlar.gecerli)

        // And the door opens again.
        dugme.performClick()
        assertNotNull(baslatilan())
        assertEquals(Durum.ACILIYOR, kapi.durum)
    }

    @Test
    fun ciftDokunmadaTekBaslatma() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        dugme.performClick()
        assertNotNull(baslatilan())
        assertNull(baslatilan())

        // A real second tap lands on the cover, whatever is under it (CarPlay, Harita, Telefon).
        bekle(100)
        for (s in listOf(Simge.CARPLAY, Simge.HARITA, Simge.TELEFON, Simge.MUZIK)) dokun(a.katmanlar.altCubuk.dugme(s))
        assertNull(baslatilan())
        assertEquals(Durum.ACILIYOR, kapi.durum)
    }

    @Test
    fun zlinkGelmezseGeriDoner() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        assertNotNull(baslatilan())
        bekle(CarPlayKapisi.ACILIS_MS + 32)
        geride()
        // No onPause: after the timeout the door goes back like a return.
        bekle(CarPlayKapisi.ZAMAN_ASIMI_MS - CarPlayKapisi.ACILIS_MS)
        assertEquals(Durum.DONUYOR, kapi.durum)
        bekle(CarPlayKapisi.DONUS_YEDEK_MS + CarPlayKapisi.DONUS_MS + 32)
        dinlenmede()
        assertEquals(1, kapi.geriAlmaSayisi)
        // Taps reach Kokpit again.
        a.katmanlar.altCubuk.klimaAlani.let { dokun(it) }
        assertEquals(Ekran.KLIMA, a.katmanlar.ekranlar.gecerli)
    }

    @Test
    fun yaridaDonusTutarli() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        bekle(100) // half-way down
        val yari = kok.scaleX
        assertTrue(yari < 1f && yari > CarPlayKapisi.OLCEK)
        c.pause()
        c.resume() // straight back (ZLINK closed at once)
        assertEquals(Durum.DONUYOR, kapi.durum)
        bekle(16)
        assertTrue(kok.scaleX <= yari + 0.02f) // goes back from where it was, no jump down
        bekle(CarPlayKapisi.DONUS_YEDEK_MS + CarPlayKapisi.DONUS_MS + 32)
        dinlenmede()
        // The timeout of the first opening does not fire later.
        bekle(CarPlayKapisi.ZAMAN_ASIMI_MS)
        dinlenmede()
        assertEquals(0, kapi.geriAlmaSayisi)
    }

    @Test
    fun surusSahnesiGecisBoyuncaDonar() {
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "dev.kokpit.anaekran/.veri.KapiServisi")
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        c = TestYardim.acVeGec()
        Hiz.yumusatici.ornek(50f / 3.6f, TestYardim.simdiNs(), null)
        AracVerisi.guncelle { hiz(50.0); hareketHali(HareketHali.SURUS) }
        TestYardim.gecisleriBitir()
        val s = a.surusSahnesi!!
        assertTrue(a.surusGorunur)
        assertTrue(s.calisiyor)
        HareketTestYardim.gercekSure()

        dugme.performClick()
        assertFalse("frozen while the door opens", s.calisiyor)
        bekle(CarPlayKapisi.ACILIS_MS + 32)
        assertFalse(s.calisiyor)
        c.pause()
        c.resume()
        assertFalse("still frozen while Kokpit steps forward", s.calisiyor)
        assertFalse(a.hizDongusu.etkin)
        bekle(CarPlayKapisi.DONUS_YEDEK_MS + CarPlayKapisi.DONUS_MS + 32)
        dinlenmede()
        assertTrue("running again", s.calisiyor)
        assertTrue(a.surusGorunur)
    }

    /** A real tap (DOWN + UP) at [v]'s centre through the window. */
    private fun dokun(v: View) {
        val xy = IntArray(2).also { v.getLocationInWindow(it) }
        val x = xy[0] + v.width / 2f
        val y = xy[1] + v.height / 2f
        val t = SystemClock.uptimeMillis()
        val d = a.window.decorView
        MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).also { d.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0).also { d.dispatchTouchEvent(it); it.recycle() }
        TestYardim.bosalt()
    }

    private companion object {
        /** ActivityOptions.ANIM_SCALE_UP (hidden constant, 2 on API 27). */
        const val ANIM_SCALE_UP = 2
    }
}
