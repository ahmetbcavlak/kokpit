package dev.kokpit.anaekran

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.kokpit.anaekran.arayuz.AltCubuk.Simge
import dev.kokpit.anaekran.arayuz.CarPlayGecisi
import dev.kokpit.anaekran.arayuz.CarPlayGecisi.Durum
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * The CarPlay transition (0.2.2, K-115, Y-22): ZLINK opened with a scale-up from the CarPlay button,
 * Kokpit 1 → 0.94 and 58 % darker in 320 ms, back in 360 ms after onResume, no intro replay
 * (not even when the system re-creates the activity on a return), one launch per opening,
 * consistent state when it is undone half-way, the loops held only while an animation runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class CarPlayGecisiTest {

    private lateinit var c: ActivityController<MainActivity>
    private val a get() = c.get()
    private val app get() = RuntimeEnvironment.getApplication()
    private val kok get() = a.katmanlar.kok
    private val kap get() = kok.parent as ViewGroup
    private val ortu get() = kap.getChildAt(1)
    private val gecis get() = a.carplay
    private val dugme get() = a.katmanlar.altCubuk.dugme(Simge.CARPLAY)

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
    }

    /** A test that destroyed the activity itself sets this. */
    private var yokEdildi = false

    @After
    fun kapat() {
        PaketAcici.baslatici = null
        if (::c.isInitialized && !yokEdildi) c.pause().stop().destroy()
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
        assertEquals(Durum.KAPALI, gecis.durum)
        assertEquals(1f, kok.scaleX, 0f)
        assertEquals(1f, kok.scaleY, 0f)
        assertEquals(0f, ortu.alpha, 0f)
        assertEquals(View.GONE, ortu.visibility)
        assertNull(kap.background)
        assertEquals(View.LAYER_TYPE_NONE, kok.layerType)
    }

    private fun geride() {
        assertEquals(CarPlayGecisi.OLCEK, kok.scaleX, 1e-4f)
        assertEquals(CarPlayGecisi.OLCEK, kok.scaleY, 1e-4f)
        assertEquals(CarPlayGecisi.ORTU_ALFA, ortu.alpha, 1e-4f)
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
        assertEquals(Durum.ACILIYOR, gecis.durum)
        assertEquals(View.VISIBLE, ortu.visibility)
        assertEquals(CarPlayGecisi.CEVRE, (kap.background as android.graphics.drawable.ColorDrawable).color)
        // One flat fill: its alpha is applied to the draw, no off-screen layer (R2 K-3).
        assertFalse(ortu.hasOverlappingRendering)

        // Half-way: between, through a hardware layer.
        bekle(160)
        assertTrue(kok.scaleX in CarPlayGecisi.OLCEK + 0.001f..0.999f)
        assertTrue(ortu.alpha in 0.01f..CarPlayGecisi.ORTU_ALFA - 0.01f)
        assertEquals(View.LAYER_TYPE_HARDWARE, kok.layerType)
        // The scale and the cover move together (one value).
        val k = (1f - kok.scaleX) / (1f - CarPlayGecisi.OLCEK)
        assertEquals(k * CarPlayGecisi.ORTU_ALFA, ortu.alpha, 1e-3f)

        // 320 ms (+ up to two frames: the animator's first tick and its end): stepped back, layer gone.
        bekle(CarPlayGecisi.ACILIS_MS - 160 + 32)
        geride()
        assertEquals(View.LAYER_TYPE_NONE, kok.layerType)
        assertEquals(Durum.ACILIYOR, gecis.durum)
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
        bekle(CarPlayGecisi.DONUS_MS + 50)
        dinlenmede()
    }

    @Test
    fun donusteAydinlanirAcilisKlibiOynamaz() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        val klipler = TestYardim.oynaticilar.size
        dugme.performClick()
        assertNotNull(baslatilan())
        bekle(CarPlayGecisi.ACILIS_MS + 32)
        c.pause()
        assertEquals(Durum.ARKADA, gecis.durum)
        assertNotNull(gecis.sonAyrilmaMs)
        geride()
        bekle(5000) // away in CarPlay: nothing comes back by itself
        assertEquals(Durum.ARKADA, gecis.durum)
        geride()

        c.resume()
        assertEquals(Durum.DONUYOR, gecis.durum)
        assertFalse(a.acilisSuruyor)
        // The return starts with the first frame drawn after the resume (at the latest the fallback).
        var t = 0L
        while (!gecis.oynuyor && t <= CarPlayGecisi.DONUS_YEDEK_MS) { bekle(16); t += 16 }
        assertTrue("return started after $t ms", gecis.oynuyor)
        geride()
        bekle(180)
        assertTrue(kok.scaleX in CarPlayGecisi.OLCEK + 0.001f..0.999f)
        assertEquals(View.LAYER_TYPE_HARDWARE, kok.layerType)
        bekle(CarPlayGecisi.DONUS_MS - 180 - 32)
        assertEquals("not before 360 ms", Durum.DONUYOR, gecis.durum)
        bekle(32 + 48) // 360 ms + up to three frames
        dinlenmede()

        // No intro clip, no intro screen: the park screen is simply back.
        assertEquals(klipler, TestYardim.oynaticilar.size)
        assertFalse(a.acilisSuruyor)
        assertEquals(Ekran.PARK, a.katmanlar.ekranlar.gecerli)

        // And the door opens again.
        dugme.performClick()
        assertNotNull(baslatilan())
        assertEquals(Durum.ACILIYOR, gecis.durum)
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
        assertEquals(Durum.ACILIYOR, gecis.durum)
    }

    @Test
    fun zlinkGelmezseGeriDoner() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        assertNotNull(baslatilan())
        bekle(CarPlayGecisi.ACILIS_MS + 32)
        geride()
        // No onPause: after the timeout the door goes back like a return.
        bekle(CarPlayGecisi.ZAMAN_ASIMI_MS - CarPlayGecisi.ACILIS_MS)
        assertEquals(Durum.DONUYOR, gecis.durum)
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
        assertEquals(1, gecis.geriAlmaSayisi)
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
        assertTrue(yari < 1f && yari > CarPlayGecisi.OLCEK)
        c.pause()
        c.resume() // straight back (ZLINK closed at once)
        assertEquals(Durum.DONUYOR, gecis.durum)
        bekle(16)
        assertTrue(kok.scaleX <= yari + 0.02f) // goes back from where it was, no jump down
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
        // The timeout of the first opening does not fire later.
        bekle(CarPlayGecisi.ZAMAN_ASIMI_MS)
        dinlenmede()
        assertEquals(0, gecis.geriAlmaSayisi)
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
        assertFalse("frozen while the transition opens", s.calisiyor)
        assertFalse(a.hizDongusu.etkin)
        bekle(100)
        c.pause() // ZLINK in front while the step back still runs
        bekle(CarPlayGecisi.ACILIS_MS)
        assertFalse("paused: stays off after the step back", s.calisiyor)
        c.resume()
        assertFalse("still frozen while Kokpit steps forward", s.calisiyor)
        assertFalse(a.hizDongusu.etkin)
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
        assertTrue("running again", s.calisiyor)
        assertTrue(a.surusGorunur)
        assertTrue("HUD loop on again (R2 K-9)", a.hizDongusu.etkin)
    }

    @Test
    fun zlinkGelmezseBeklerkenSahneCalisir() {
        // R2 K-1: the loops are held only while an animation runs, not through the 2 s timeout.
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "dev.kokpit.anaekran/.veri.KapiServisi")
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        c = TestYardim.acVeGec()
        Hiz.yumusatici.ornek(50f / 3.6f, TestYardim.simdiNs(), null)
        AracVerisi.guncelle { hiz(50.0); hareketHali(HareketHali.SURUS) }
        TestYardim.gecisleriBitir()
        val s = a.surusSahnesi!!
        assertTrue(s.calisiyor)
        HareketTestYardim.gercekSure()

        dugme.performClick()
        assertFalse(s.calisiyor)
        bekle(CarPlayGecisi.ACILIS_MS + 32) // stepped back, ZLINK did not come (no onPause)
        geride()
        assertEquals(Durum.ACILIYOR, gecis.durum)
        assertFalse(gecis.donuk)
        assertTrue("scene runs while the timeout waits", s.calisiyor)
        assertTrue(a.hizDongusu.etkin)
        assertEquals(View.LAYER_TYPE_NONE, kok.layerType)

        // Timeout: held again for the return, released at its end.
        bekle(CarPlayGecisi.ZAMAN_ASIMI_MS - CarPlayGecisi.ACILIS_MS)
        assertEquals(Durum.DONUYOR, gecis.durum)
        assertFalse(s.calisiyor)
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
        assertTrue(s.calisiyor)
        assertTrue(a.hizDongusu.etkin)
    }

    @Test
    fun baslatmaReddedilirseHicKipirdamaz() {
        // ZLINK installed but the start is refused (SecurityException, a vendor surprise): R2 K-9.
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        PaketAcici.baslatici = { _, _, _ -> throw SecurityException("not exported") }
        ac()
        dugme.performClick()
        dinlenmede()
        assertNull(baslatilan())
        assertEquals(View.VISIBLE, a.kisaNot.visibility)
        assertTrue(a.kisaNot.text.toString(), a.kisaNot.text.contains("açılamadı"))
        bekle(CarPlayGecisi.ZAMAN_ASIMI_MS + CarPlayGecisi.DONUS_MS + 300)
        dinlenmede()
        assertEquals(0, gecis.geriAlmaSayisi)
        // A later start that works opens normally.
        PaketAcici.baslatici = null
        dugme.performClick()
        assertNotNull(baslatilan())
        assertEquals(Durum.ACILIYOR, gecis.durum)
    }

    @Test
    fun zamanAsimindanSonraGecOnPause() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        bekle(CarPlayGecisi.ZAMAN_ASIMI_MS + 32)
        assertEquals(Durum.DONUYOR, gecis.durum)
        // ZLINK comes late, in the middle of the return: the pause does not turn it into ARKADA.
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + 100)
        c.pause()
        assertEquals(Durum.DONUYOR, gecis.durum)
        bekle(CarPlayGecisi.DONUS_MS + 64) // the return ends while paused
        dinlenmede()
        c.resume() // back from the late ZLINK: nothing to step forward from, nothing stuck
        dinlenmede()
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
        assertFalse(gecis.oynuyor)
        assertEquals(1, gecis.geriAlmaSayisi)
        dokun(a.katmanlar.altCubuk.klimaAlani)
        assertEquals(Ekran.KLIMA, a.katmanlar.ekranlar.gecerli)
    }

    @Test
    fun donusSirasindaYenidenDokunus() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        assertNotNull(baslatilan())
        bekle(CarPlayGecisi.ACILIS_MS + 32)
        c.pause()
        c.resume()
        var t = 0L
        while (!gecis.oynuyor && t <= CarPlayGecisi.DONUS_YEDEK_MS) { bekle(16); t += 16 }
        bekle(160) // half-way forward
        val yari = kok.scaleX
        assertTrue(yari > CarPlayGecisi.OLCEK && yari < 1f)
        assertEquals(Durum.DONUYOR, gecis.durum)

        // CarPlay again while stepping forward: a new launch, back from where the picture is.
        dokun(dugme)
        assertNotNull(baslatilan())
        assertEquals(Durum.ACILIYOR, gecis.durum)
        bekle(16)
        assertTrue("no jump to rest", kok.scaleX <= yari + 0.005f)
        bekle(CarPlayGecisi.ACILIS_MS + 32)
        geride()
        c.pause()
        assertEquals(Durum.ARKADA, gecis.durum)
        c.resume()
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
    }

    @Test
    fun simgeyleDonus_onNewIntent() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        val klipler = TestYardim.oynaticilar.size
        a.katmanlar.altCubuk.klimaAlani.let { dokun(it) } // a panel open (R2 K-6)
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.KLIMA, a.katmanlar.ekranlar.gecerli)
        dugme.performClick()
        assertNotNull(baslatilan())
        bekle(CarPlayGecisi.ACILIS_MS + 32)
        c.pause().stop()
        assertEquals(Durum.ARKADA, gecis.durum)

        // The launcher icon (singleTask): onRestart, onStart, onNewIntent, onResume.
        c.restart()
        c.newIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER))
        c.resume()
        assertEquals(Durum.DONUYOR, gecis.durum)
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        dinlenmede()
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.PARK, a.katmanlar.ekranlar.gecerli) // the panel was closed by the icon
        assertEquals(klipler, TestYardim.oynaticilar.size)
        assertFalse(a.acilisSuruyor)
    }

    @Test
    fun animasyonOrtasindaYokEdilme() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        ac()
        dugme.performClick()
        bekle(100)
        assertTrue(gecis.oynuyor)
        c.pause().stop().destroy()
        yokEdildi = true
        assertFalse(gecis.oynuyor)
        // Nothing of it runs any more: no timeout, no return, no crash.
        bekle(CarPlayGecisi.ZAMAN_ASIMI_MS + CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 100)
        assertFalse(gecis.oynuyor)
        assertEquals(0, gecis.geriAlmaSayisi)
        assertEquals(Durum.ARKADA, gecis.durum) // as the pause left it: no return ran after onDestroy
    }

    @Test
    fun teshisCarPlayGecisiDokunustanOlcer() {
        TestYardim.uygulamaKur(PaketAcici.ZLINK, "ZLink")
        // The start call itself takes 120 ms (a slow binder call on the unit).
        PaketAcici.baslatici = { ctx, i, b ->
            ctx.startActivity(i, b)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(120))
        }
        ac()
        dugme.performClick()
        c.pause()
        assertTrue("from the tap: ${gecis.sonAyrilmaMs}", (gecis.sonAyrilmaMs ?: 0L) >= 120L)
        c.resume()
        bekle(CarPlayGecisi.DONUS_YEDEK_MS + CarPlayGecisi.DONUS_MS + 32)
        a.teshis.ac()
        val m = a.teshis.metin.toString()
        assertTrue(m, m.contains("CarPlay geçişi: kapalı · son açılış "))
        assertFalse(m, m.contains("CarPlay kapısı"))
        assertFalse("repeated line gone (R2 K-8)", m.contains("Kapı kayıtları"))
        assertEquals(1, Regex("liste dışı pencere").findAll(m).count())
        assertTrue(m, m.contains("Açılış: klip oynadı (soğuk açılış)"))
        a.teshis.kapat()
    }

    // --- Intro clip: cold start vs. re-creation (R2 Ö-1) ---

    @Test
    fun sistemYenidenKurunca_klipYok_ilkAcilistaVar() {
        // First start: the clip plays.
        c = Robolectric.buildActivity(MainActivity::class.java).setup()
        TestYardim.bosalt()
        assertTrue("cold start plays the clip", a.acilisSuruyor)
        assertTrue(TestYardim.oynaticilar.isNotEmpty())
        assertEquals(Ekran.ACILIS, a.katmanlar.ekranlar.gecerli)
        a.hareket?.kes()
        TestYardim.gecisleriBitir()

        // Behind CarPlay, the process dies; the system keeps the saved state and re-creates us.
        val kayit = Bundle()
        c.pause().saveInstanceState(kayit).stop().destroy()
        TestYardim.bekle(60_000) // a minute away, screen on (no deep sleep)
        val klipler = TestYardim.oynaticilar.size
        c = Robolectric.buildActivity(MainActivity::class.java).setup(kayit)
        TestYardim.bosalt()
        assertFalse("re-created: no clip", a.acilisSuruyor)
        assertEquals(klipler, TestYardim.oynaticilar.size)
        assertEquals(Ekran.PARK, a.katmanlar.ekranlar.gecerli)
        assertTrue(a.acilisKarari.sicak)
        a.teshis.ac()
        assertTrue(a.teshis.metin.toString().contains("Açılış: klip atlandı (sistem yeniden kurdu)"))
        a.teshis.kapat()
    }

    @Test
    fun uykudanSonraKlipOynar() {
        // The car was switched off: the unit slept (elapsedRealtime ran on, uptimeMillis did not).
        val kayit = Bundle()
        val g = SystemClock.elapsedRealtime()
        val u = SystemClock.uptimeMillis()
        AcilisKarari.kaydet(kayit, g - 10 * 60_000L, u - 30_000L) // 10 min ago, 9.5 min of it asleep
        c = Robolectric.buildActivity(MainActivity::class.java).setup(kayit)
        TestYardim.bosalt()
        assertTrue(a.acilisSuruyor)
        assertFalse(a.acilisKarari.sicak)
        // A state from another app or without our keys: cold too.
        c.pause().stop().destroy()
        c = Robolectric.buildActivity(MainActivity::class.java).setup(Bundle().apply { putString("x", "y") })
        TestYardim.bosalt()
        assertTrue(a.acilisSuruyor)
    }

    @Test
    fun acilisKarariSaf() {
        val b = Bundle()
        AcilisKarari.kaydet(b, 1_000_000L, 900_000L)
        assertTrue(AcilisKarari.karar(b, 1_000_000L + 3_600_000L, 900_000L + 3_600_000L).sicak) // an hour away, awake
        assertTrue(AcilisKarari.karar(b, 1_000_000L + 59_000L, 900_000L).sicak) // 59 s asleep
        assertFalse(AcilisKarari.karar(b, 1_000_000L + 61_000L, 900_000L).sicak) // slept > 1 min
        assertFalse(AcilisKarari.karar(b, 5_000L, 4_000L).sicak) // clocks went back: another boot
        assertFalse(AcilisKarari.karar(null, 1L, 1L).sicak)
        assertFalse(AcilisKarari.karar(Bundle(), 1L, 1L).sicak)
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
