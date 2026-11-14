package dev.kokpit.anaekran

import android.Manifest
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.arayuz.Arkaplan
import dev.kokpit.anaekran.arayuz.Gorseller
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.ekran.SurusEkrani
import dev.kokpit.anaekran.hareket.HareketTestYardim
import dev.kokpit.anaekran.hareket.YumusakGecis
import dev.kokpit.anaekran.hareket.surus.HizSaglayici
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.Hiz
import dev.kokpit.anaekran.veri.SahteVeri
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
import java.util.concurrent.Executor

/**
 * Ana Ekran 0.2 wiring (task E2): the drive scene's layer rules, the single speed reader, the
 * HUD's "—" / stale label, the K-93 entrances, the E1 touch regression and the diagnostics lines.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class SurusBaglamaTest {

    private lateinit var c: ActivityController<MainActivity>
    private val a get() = c.get()
    private val e get() = a.katmanlar.ekranlar
    private val app get() = RuntimeEnvironment.getApplication()
    private val s get() = a.surusSahnesi!!
    private val sahneResmi get() = a.sahneResmi

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "dev.kokpit.anaekran/.veri.KapiServisi")
    }

    private var yokEdildi = false

    @After
    fun kapat() {
        if (::c.isInitialized && !yokEdildi) c.pause().stop().destroy()
        ShadowChoreographer.setPaused(false)
        Tema.zorla(null)
        AracVerisi.sifirla()
        Hiz.yumusatici.sifirla()
        Arkaplan.yurutucu = Executor { it.run() }
    }

    private fun hal(h: HareketHali?) = AracVerisi.guncelle { hiz(if (h == HareketHali.SURUS) 50.0 else 0.0); hareketHali(h) }

    /** A GPS speed sample now (TestYardim.simdiNs), km/h. */
    private fun ornek(kmh: Float) = Hiz.yumusatici.ornek(kmh / 3.6f, TestYardim.simdiNs(), null)

    private val surusEkrani get() = e.gorunum(Ekran.SURUS) as SurusEkrani

    // --- 1. Drive scene layer ---

    @Test
    fun sahneGeceSurusteHazirsaGorunur() {
        c = TestYardim.acVeGec()
        assertEquals(Ekran.PARK, e.gecerli)
        assertTrue(s.hazir)
        assertEquals(View.INVISIBLE, s.visibility) // park

        hal(HareketHali.SURUS)
        assertEquals(Ekran.SURUS, e.gecerli)
        assertEquals(R.raw.klip_surus, TestYardim.oynaticilar.last().klip)
        assertEquals(View.INVISIBLE, s.visibility) // the clip has its own car
        TestYardim.bekle(1000)
        assertEquals(View.INVISIBLE, s.visibility) // still in the clip's hold / fade
        TestYardim.gecisleriBitir()
        assertEquals(View.VISIBLE, s.visibility)
        assertTrue(a.surusGorunur)
        assertTrue(s.calisiyor)
        assertEquals(R.drawable.boya_gece, sahneResmi) // the scene draws the car

        // A panel: hidden at once; the panel's picture is the bare texture.
        a.katmanlar.altCubuk.klimaAlani.performClick()
        assertEquals(Ekran.KLIMA, e.gecerli)
        assertEquals(View.INVISIBLE, s.visibility)
        TestYardim.gecisleriBitir()
        assertEquals(View.INVISIBLE, s.visibility)
        // Back: the still car fades in, then the scene takes over from rest.
        a.katmanlar.altCubuk.klimaAlani.performClick()
        assertEquals(Ekran.SURUS, e.gecerli)
        assertEquals(View.INVISIBLE, s.visibility)
        TestYardim.gecisleriBitir()
        assertEquals(View.VISIBLE, s.visibility)
        assertEquals(R.drawable.boya_gece, sahneResmi)

        // Diagnostics: the scene stops behind the panel, the still car is back under it.
        a.katmanlar.kareHizi.performLongClick()
        assertTrue(a.teshis.acik)
        assertEquals(View.INVISIBLE, s.visibility)
        assertFalse(s.calisiyor)
        assertEquals(R.drawable.sahne_surus, sahneResmi)
        a.teshis.kapat()
        assertEquals(View.VISIBLE, s.visibility)
        assertEquals(R.drawable.boya_gece, sahneResmi)

        // Drive → park: hidden before the clip, which starts on the still car (its poster).
        val once = TestYardim.oynaticilar.size
        hal(HareketHali.PARK)
        assertEquals(View.INVISIBLE, s.visibility)
        assertEquals(once + 1, TestYardim.oynaticilar.size)
        assertEquals(R.raw.klip_surus_geri, TestYardim.oynaticilar.last().klip)
        TestYardim.gecisleriBitir()
        assertEquals(View.INVISIBLE, s.visibility)
        assertEquals(R.drawable.sahne_park, sahneResmi)
    }

    @Test
    fun gunduzSurusteHazirsaGunduzSahnesi() { // K-108
        Tema.zorla(Palet.GUNDUZ)
        c = TestYardim.acVeGec()
        assertEquals(true, s.kuruluGunduz) // the theme's set from the start
        assertEquals(View.INVISIBLE, s.visibility) // park

        // Park → drive by day: no clip, a plain fade over the still day car, then the scene.
        val once = TestYardim.oynaticilar.size
        hal(HareketHali.SURUS)
        assertEquals(Ekran.SURUS, e.gecerli)
        assertEquals(once, TestYardim.oynaticilar.size) // no player: there is no day clip
        assertTrue(a.hareket!!.oynatici!!.calisiyor)
        assertEquals(View.INVISIBLE, s.visibility)
        assertEquals(R.drawable.sahne_surus_gunduz, sahneResmi)
        TestYardim.gecisleriBitir()
        assertEquals(View.VISIBLE, s.visibility)
        assertTrue(a.surusGorunur)
        assertTrue(s.calisiyor)
        assertTrue(s.hazir)
        assertEquals(true, s.kuruluGunduz)
        assertEquals(R.drawable.boya_gunduz, sahneResmi) // the scene draws the day car
        // Day look: no city lights, lamp reflection off.
        assertEquals(null, (s.arkaGorunumu as dev.kokpit.anaekran.hareket.surus.ArkaKatman).isik)
        assertEquals(View.GONE, s.yansimaGorunumu.visibility)

        // Panel: hidden at once; back: the still day car, then the scene from rest.
        a.katmanlar.altCubuk.klimaAlani.performClick()
        assertEquals(View.INVISIBLE, s.visibility)
        TestYardim.gecisleriBitir()
        assertEquals(R.drawable.boya_gunduz, sahneResmi) // the panel's bare texture (day)
        a.katmanlar.altCubuk.klimaAlani.performClick()
        assertEquals(View.INVISIBLE, s.visibility)
        TestYardim.gecisleriBitir()
        assertEquals(View.VISIBLE, s.visibility)

        // Diagnostics: the scene stops, the still day car is back under the panel.
        a.katmanlar.kareHizi.performLongClick()
        assertEquals(View.INVISIBLE, s.visibility)
        assertFalse(s.calisiyor)
        assertEquals(R.drawable.sahne_surus_gunduz, sahneResmi)
        a.teshis.kapat()
        assertEquals(View.VISIBLE, s.visibility)
        assertEquals(R.drawable.boya_gunduz, sahneResmi)

        // Drive → park: hidden before the plain fade, whose cover is the still day car.
        hal(HareketHali.PARK)
        assertEquals(View.INVISIBLE, s.visibility)
        assertEquals(once, TestYardim.oynaticilar.size)
        assertTrue(aracVar())
        TestYardim.gecisleriBitir()
        assertEquals(View.INVISIBLE, s.visibility)
        assertEquals(R.drawable.sahne_park_gunduz, sahneResmi)
    }

    @Test
    fun geceGunduzGecisindeSetDegisirAracHerKarede() { // K-108: dawn and dusk while driving
        geceSurusteSahne()
        assertEquals(false, s.kuruluGunduz)
        HareketTestYardim.gercekSure()

        // Dawn: hidden over the still night car, fade to the still day car, the day scene from rest.
        Tema.zorla(Palet.GUNDUZ)
        assertTrue("geçiş başlamadı", a.hareket!!.oynatici!!.calisiyor)
        assertEquals(View.INVISIBLE, s.visibility)
        var adim = 0
        while ((a.hareket!!.oynatici!!.calisiyor || !a.surusGorunur) && adim < 200) {
            assertTrue("araçsız kare (şafak, adım $adim)", aracVar())
            HareketTestYardim.bekle(16)
            adim++
        }
        assertTrue(a.surusGorunur)
        assertEquals(true, s.kuruluGunduz) // the night set is gone from the views
        assertEquals(R.drawable.boya_gunduz, a.sahneResmi)
        repeat(30) {
            assertTrue(aracVar())
            HareketTestYardim.bekle(16)
        }

        // Dusk: the other way round.
        Tema.zorla(Palet.GECE)
        assertTrue(a.hareket!!.oynatici!!.calisiyor)
        assertEquals(View.INVISIBLE, s.visibility)
        adim = 0
        while ((a.hareket!!.oynatici!!.calisiyor || !a.surusGorunur) && adim < 200) {
            assertTrue("araçsız kare (akşam, adım $adim)", aracVar())
            HareketTestYardim.bekle(16)
            adim++
        }
        assertTrue(a.surusGorunur)
        assertEquals(false, s.kuruluGunduz)
        assertEquals(R.drawable.boya_gece, a.sahneResmi)
        assertTrue(aracVar())
    }

    @Test
    fun gunduzSetiGecCozulurseSabitAracBekler() {
        // Day set decode held back on the worker: after the dawn fade the still day car stays
        // (the car rule) until the set is in, then the scene shows.
        geceSurusteSahne()
        val kuyruk = ArrayList<Runnable>()
        Arkaplan.yurutucu = Executor { kuyruk += it }
        Tema.zorla(Palet.GUNDUZ)
        // The worker is serial: the fade's pictures were asked for first, the drive scene's set
        // last (HareketBaglayici hears the theme after MainActivity). Hold back only that one.
        val setIsi = kuyruk.removeAt(kuyruk.lastIndex)
        Arkaplan.yurutucu = Executor { it.run() }
        try {
            kuyruk.toList().forEach { it.run() }
            kuyruk.clear()
            TestYardim.gecisleriBitir()
            assertFalse(a.hareket!!.oynatici!!.calisiyor)
            assertFalse(a.surusGorunur)
            assertFalse(s.hazir)
            assertEquals(View.INVISIBLE, s.visibility)
            assertEquals(R.drawable.sahne_surus_gunduz, a.sahneResmi)
            assertTrue(aracVar())
        } finally {
            setIsi.run()
            TestYardim.bosalt()
        }
        assertTrue(a.surusGorunur)
        assertEquals(true, s.kuruluGunduz)
        assertEquals(R.drawable.boya_gunduz, a.sahneResmi)
    }

    @Test
    fun parkteTemaDegisinceSetDegisir() {
        c = TestYardim.acVeGec() // night, park
        assertEquals(false, s.kuruluGunduz)
        Tema.zorla(Palet.GUNDUZ)
        TestYardim.bosalt()
        assertEquals("gizli sahne yeni seti hemen alır", true, s.kuruluGunduz)
        assertTrue(s.hazir)
        Tema.zorla(Palet.GECE)
        TestYardim.bosalt()
        assertEquals(false, s.kuruluGunduz)
    }

    @Test
    fun hazirOlmadanSabitAracSonraSahne() {
        // Worker jobs wait; the drive scene's decode is the first one (queued in onCreate before the intro).
        val kuyruk = ArrayList<Runnable>()
        Arkaplan.yurutucu = Executor { kuyruk += it }
        SahteVeri.uygula(SahteVeri.surus62())
        c = TestYardim.ac()
        val surusIsi = kuyruk.removeAt(0)
        while (kuyruk.isNotEmpty()) {
            val l = kuyruk.toList()
            kuyruk.clear()
            l.forEach { it.run() }
            TestYardim.bosalt()
        }
        Arkaplan.yurutucu = Executor { it.run() }
        a.hareket!!.kes()
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.SURUS, e.gecerli)
        assertFalse(s.hazir)
        assertEquals(View.INVISIBLE, s.visibility)
        assertEquals(R.drawable.sahne_surus, sahneResmi) // the still car meanwhile

        surusIsi.run()
        TestYardim.bosalt()
        assertTrue(s.hazir)
        assertEquals(View.VISIBLE, s.visibility)
        assertEquals(R.drawable.boya_gece, sahneResmi)
    }

    @Test
    fun acilisKlibiDogrudanParkSahnesineSoluyor() { // J1 finding A (0.2.1)
        // The clip's last frame has sahne_park's rim angle; sahne_karsilama's is half a spoke
        // pitch off. The intro must fade from the held last frame straight to sahne_park and
        // never show sahne_karsilama (it would make the spokes jump).
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.ac()
        HareketTestYardim.gercekSure()
        assertEquals(Ekran.ACILIS, e.gecerli)
        assertEquals("video altındaki sahne = hedef", R.drawable.sahne_park, sahneResmi)
        val o = TestYardim.oynaticilar.single()
        assertEquals(R.raw.klip_giris, o.klip)
        o.dinleyici!!.basladi()
        o.dinleyici!!.kareGeldi()
        o.dinleyici!!.bitti()
        var park: android.graphics.Bitmap? = null
        Gorseller.al(a.resources, R.drawable.sahne_park) { park = it }
        assertNotNull(park)
        var hedefGoruldu = false
        var adim = 0
        while (a.acilisSuruyor && adim++ < 300) {
            val v = a.katmanlar.video
            for (i in 0 until v.childCount) {
                val iv = v.getChildAt(i) as? android.widget.ImageView ?: continue
                if (iv.visibility != View.VISIBLE) continue
                val b = (iv.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: continue
                if (b.width == 768 && b.height == 1024) {
                    assertTrue("solma hedefi sahne_park değil (adım $adim)", b === park)
                    hedefGoruldu = true
                }
            }
            HareketTestYardim.bekle(16)
        }
        assertFalse(a.acilisSuruyor)
        assertTrue("solma görülmedi", hedefGoruldu)
        assertEquals(Ekran.PARK, e.gecerli)
        assertEquals(R.drawable.sahne_park, sahneResmi)
        assertFalse("sahne_karsilama hiç çözülmemeli", Gorseller.hazirMi(R.drawable.sahne_karsilama))
    }

    // --- İ-F2 ---

    private val R_ARAC = setOf(
        R.drawable.sahne_surus, R.drawable.sahne_surus_gunduz,
        R.drawable.sahne_park, R.drawable.sahne_park_gunduz,
    )

    /** Some full, opaque picture of the car is on screen: the moving scene, a cover, or the scene picture. */
    private fun aracVar(): Boolean {
        // A visible scene has a set on its views, and it is the set of the ground under it.
        if (s.visibility == View.VISIBLE) {
            val zemin = if (s.kuruluGunduz == true) R.drawable.boya_gunduz else R.drawable.boya_gece
            return s.kuruluGunduz != null && (a.sahneResmi == zemin || a.sahneResmi in R_ARAC)
        }
        val v = a.katmanlar.video
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is android.widget.ImageView && c.visibility == View.VISIBLE && c.alpha >= 0.999f && c.drawable != null) return true
        }
        val r = a.sahneResmi
        return r in R_ARAC && a.katmanlar.sahne.visibility == View.VISIBLE && Gorseller.hazirMi(r)
    }

    private fun geceSurusteSahne() {
        c = TestYardim.acVeGec()
        hal(HareketHali.SURUS)
        TestYardim.gecisleriBitir()
        assertTrue(a.surusGorunur)
    }

    @Test
    fun safaktaGecisleGunduzAracina() { // Ö-1
        geceSurusteSahne()
        HareketTestYardim.gercekSure()
        Tema.zorla(Palet.GUNDUZ)
        assertTrue("geçiş başlamadı", a.hareket!!.oynatici!!.calisiyor)
        assertEquals(View.INVISIBLE, s.visibility)
        repeat(120) {
            assertTrue("araçsız kare (adım $it)", aracVar())
            HareketTestYardim.bekle(16)
        }
        assertFalse(a.hareket!!.oynatici!!.calisiyor)
        // 0.2.1: the fade ended on the still day car, then the day scene took over from rest.
        assertTrue(a.surusGorunur)
        assertEquals(R.drawable.boya_gunduz, a.sahneResmi)
        assertTrue(aracVar())
    }

    @Test
    fun bellekBosalincaOrtuBeklenir() { // Ö-2
        geceSurusteSahne()
        Gorseller.bosalt() // onTrimMemory
        hal(HareketHali.PARK)
        assertEquals(View.VISIBLE, s.visibility) // the moving car stays until the cover is decoded
        assertTrue(aracVar())
        TestYardim.bosalt()
        assertEquals(View.INVISIBLE, s.visibility)
        assertTrue("örtü yok", aracVar())
        assertEquals(R.raw.klip_surus_geri, TestYardim.oynaticilar.last().klip)
        repeat(240) {
            assertTrue("araçsız kare (adım $it)", aracVar())
            TestYardim.bekle(16)
        }
        assertEquals(R.drawable.sahne_park, a.sahneResmi)
    }

    @Test
    fun yokEdilinceBekleyenOrtuGecisBaslatmaz() { // İ-F3
        geceSurusteSahne()
        Gorseller.bosalt()
        hal(HareketHali.PARK) // waits for the still car's decode
        assertEquals(View.VISIBLE, s.visibility)
        val once = TestYardim.oynaticilar.size
        c.pause().stop().destroy()
        yokEdildi = true
        TestYardim.bosalt() // the decode calls back on the destroyed activity
        TestYardim.bekle(1000) // and the retry timer would fire
        assertEquals(once, TestYardim.oynaticilar.size) // no clip / fade started
    }

    @Test
    fun duraklayincaHizDonukKalmaz() { // Ö-3
        // A data hook that, like HizKaynagi, resets the smoother when GPS stops in onPause.
        Baglayicilar.uret = { listOf(object : Baglayici { override fun dur() = Hiz.yumusatici.sifirla() }, TestYardim.sahteHareket()) }
        geceSurusteSahne()
        ornek(62f)
        TestYardim.kareler(3)
        assertEquals("62", surusEkrani.hizMetni.toString())
        c.pause() // e.g. a dialog: our window stays visible
        assertEquals("—", surusEkrani.hizMetni.toString())
        c.resume()
        ornek(62f)
        TestYardim.kareler(3)
        assertEquals("62", surusEkrani.hizMetni.toString())
    }

    @Test
    fun yokEdilirkenTeshisSahneyiGostermez() { // K-1
        geceSurusteSahne()
        val sahne = s
        a.katmanlar.kareHizi.performLongClick()
        assertTrue(a.teshis.acik)
        assertEquals(View.INVISIBLE, sahne.visibility)
        c.pause().stop().destroy()
        yokEdildi = true
        assertEquals(View.INVISIBLE, sahne.visibility)
    }

    // --- 2. Single reader ---

    @Test
    fun tekOkuyucuHerKareBirKez() {
        val okumalar = HashMap<Long, Int>()
        val y = Hiz.yumusatici
        Baglayicilar.hiz = HizSaglayici { t -> okumalar[t] = (okumalar[t] ?: 0) + 1; y.deger(t) }
        c = TestYardim.acVeGec()
        hal(HareketHali.SURUS)
        ornek(50f)
        // During the clip the scene is hidden: the screen's own loop reads.
        val ekranOnce = a.hizDongusu.okumaSayisi
        TestYardim.kareler(10)
        assertTrue(a.hizDongusu.okumaSayisi > ekranOnce)
        assertEquals("50", surusEkrani.hizMetni.toString())

        TestYardim.gecisleriBitir()
        assertTrue(s.calisiyor)
        // The scene runs: the screen loop asks for no frame and reads nothing, the digits still follow.
        val ekranSahnede = a.hizDongusu.okumaSayisi
        ornek(80f)
        TestYardim.kareler(60)
        assertEquals(ekranSahnede, a.hizDongusu.okumaSayisi)
        assertFalse(a.hizDongusu.bekliyor)
        val gosterilen = surusEkrani.hizMetni.toString().toInt()
        assertTrue("$gosterilen", gosterilen in 70..80)
        assertTrue(surusEkrani.yayKmh in 69f..80f)

        // Panel, back, day: readers hand over; never two reads on one frame time.
        a.katmanlar.altCubuk.klimaAlani.performClick()
        TestYardim.kareler(20)
        a.katmanlar.altCubuk.klimaAlani.performClick()
        TestYardim.kareler(20)
        TestYardim.gecisleriBitir()
        TestYardim.kareler(20)
        // Day (0.2.1): during the dawn fade the scene is hidden and the screen's loop reads...
        HareketTestYardim.gercekSure()
        Tema.zorla(Palet.GUNDUZ)
        assertFalse(s.calisiyor)
        val gunduzOnce = a.hizDongusu.okumaSayisi
        TestYardim.kareler(10)
        assertTrue(a.hizDongusu.okumaSayisi > gunduzOnce)
        // ...then the day scene runs and is the only reader, like at night.
        HareketTestYardim.bekle(1500)
        assertTrue(a.surusGorunur)
        assertTrue(s.calisiyor)
        assertEquals(true, s.kuruluGunduz)
        val gunduzSahnede = a.hizDongusu.okumaSayisi
        ornek(40f)
        TestYardim.kareler(60)
        assertEquals("gündüz ekran döngüsü okumamalı", gunduzSahnede, a.hizDongusu.okumaSayisi)
        assertFalse(a.hizDongusu.bekliyor)
        assertTrue(surusEkrani.hizMetni.toString().toInt() in 40..80)
        assertTrue(okumalar.isNotEmpty())
        assertTrue("çift okuma: ${okumalar.filterValues { it > 1 }}", okumalar.values.all { it == 1 })
    }

    @Test
    fun durgunDongulerDurur() {
        // The screen's loop alone: no motion hook, so no drive scene (by day it runs too since 0.2.1).
        Baglayicilar.uret = { emptyList() }
        c = TestYardim.acVeGec()
        assertNull(a.surusSahnesi)
        hal(HareketHali.SURUS)
        TestYardim.gecisleriBitir()
        ornek(0f)
        ornek(0f)
        TestYardim.kareler(5)
        assertEquals("0", surusEkrani.hizMetni.toString())
        assertFalse(a.hizDongusu.bekliyor) // at rest: no frame asked
        ornek(20f) // leaves rest → uyandir
        assertTrue(a.hizDongusu.bekliyor)
        TestYardim.kareler(90)
        assertEquals("20", surusEkrani.hizMetni.toString())
    }

    // --- 3. "—", stale ---

    @Test
    fun bilinmiyorTireEskiGpsSoluk() {
        c = TestYardim.acVeGec()
        hal(HareketHali.SURUS)
        TestYardim.gecisleriBitir()
        val se = surusEkrani
        assertEquals("—", se.hizMetni.toString())
        assertTrue(se.birimMetni.toString(), se.birimMetni.contains("bekleniyor"))
        assertTrue(se.yayKmh < 0f)

        ornek(62f) // first sample: shown at once
        TestYardim.kareler(3)
        assertEquals("62", se.hizMetni.toString())
        assertEquals("km/sa · gps", se.birimMetni.toString())
        assertFalse(se.gpsSoluk)

        // 5 s without a sample: the value stays, "gps" at 40 % (also while no frame comes).
        TestYardim.bekle(6000)
        assertEquals("62", se.hizMetni.toString())
        assertTrue(se.gpsSoluk)
        val sp = se.birimMetni as android.text.Spanned
        val renk = sp.getSpans(0, sp.length, android.text.style.ForegroundColorSpan::class.java).single()
        assertEquals(Math.round(0xFF * SurusEkrani.ESKI_OPAKLIK), android.graphics.Color.alpha(renk.foregroundColor))
        assertEquals("gps", sp.subSequence(sp.getSpanStart(renk), sp.getSpanEnd(renk)).toString())

        ornek(62f) // fresh again
        TestYardim.kareler(2)
        assertFalse(se.gpsSoluk)

        Hiz.yumusatici.unut() // 60 s silent: unknown
        TestYardim.kareler(2)
        assertEquals("—", se.hizMetni.toString())
    }

    // --- 4. K-93 entrances ---

    @Test
    fun zamanlamaGirisleri() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = acDuraklatilmis()
        // Intro → park: the bottom bar comes in with the K-93 entrance (0.2 s delay, 0.8 s alpha).
        a.hareket!!.kes()
        var adim = 0
        while (e.gecerli != Ekran.PARK && adim++ < 200) HareketTestYardim.bekle(16)
        assertEquals(Ekran.PARK, e.gecerli)
        val cubuk = a.katmanlar.altCubuk.parent as View
        assertEquals(0f, cubuk.alpha, 0.01f)
        HareketTestYardim.bekle(150)
        assertEquals(0f, cubuk.alpha, 0.01f) // still in its delay
        HareketTestYardim.bekle(1200)
        assertEquals(1f, cubuk.alpha, 0.001f)

        // The screen change is the K-93 one (YumusakGecis: 0.2 s, 0.8 s alpha, 6 px rise).
        assertTrue(e.gecis is YumusakGecis)
        hal(HareketHali.SURUS)
        val v = e.gorunum(Ekran.SURUS).gorunum
        assertEquals(0f, v.alpha, 0.001f)
        assertEquals(6f, v.translationY, 0.001f)
        HareketTestYardim.bekle(3500)
        assertEquals(1f, v.alpha, 0.001f)

        // Door card on the drive screen: its own entrance, a leaf, the layer itself untouched.
        AracVerisi.guncelle { kapiAcik(true) }
        val kart = a.uyari.kartGorunumu
        assertEquals(View.VISIBLE, kart.visibility)
        assertEquals(0f, kart.alpha, 0.001f)
        assertEquals(6f, kart.translationY, 0.001f)
        HareketTestYardim.bekle(150)
        assertEquals(0f, kart.alpha, 0.01f)
        HareketTestYardim.bekle(1100)
        assertEquals(1f, kart.alpha, 0.001f)
        assertEquals(0f, kart.translationY, 0.001f)
        assertEquals(1f, a.uyari.alpha, 0f)
        // Same look again (a data update): no restart.
        AracVerisi.guncelle { disSicaklik(12.0) }
        assertEquals(1f, kart.alpha, 0.001f)
    }

    /** Opens the activity, then pauses Robolectric's Choreographer: one frame per 16 ms from here on. */
    private fun acDuraklatilmis(): ActivityController<MainActivity> =
        TestYardim.ac().also { HareketTestYardim.gercekSure() }

    // --- 5. E1: touches only to the screen in front ---

    private fun duzenle() {
        val d = a.window.decorView
        d.measure(View.MeasureSpec.makeMeasureSpec(768, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY))
        d.layout(0, 0, 768, 1024)
    }

    /** Window coordinates of the centre of the app cell [ad] in the apps panel. */
    private fun hucreMerkezi(ad: String): Pair<Float, Float> {
        fun bul(v: View): View? {
            if (v.contentDescription?.toString() == ad && v.isClickable) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) bul(v.getChildAt(i))?.let { return it }
            return null
        }
        val h = bul(e.gorunum(Ekran.UYGULAMALAR).gorunum) ?: error("cell $ad not found")
        val xy = IntArray(2)
        h.getLocationInWindow(xy)
        return (xy[0] + h.width / 2f) to (xy[1] + h.height / 2f)
    }

    private fun dokun(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val d = a.window.decorView
        MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).also { d.dispatchTouchEvent(it); it.recycle() }
        MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0).also { d.dispatchTouchEvent(it); it.recycle() }
        TestYardim.bosalt()
    }

    private fun baslatilan() = shadowOf(app).nextStartedActivity ?: shadowOf(a).nextStartedActivity

    @Test
    fun e1KapananPanelDokunmaAlmaz() {
        TestYardim.uygulamaKur("com.spotify.music", "Spotify")
        c = acDuraklatilmis()
        a.hareket!!.kes()
        HareketTestYardim.bekle(3000)
        assertEquals(Ekran.PARK, e.gecerli)
        val uyg = a.katmanlar.altCubuk.dugme(AltCubuk.Simge.UYGULAMALAR)

        // Positive control: with the panel settled, a tap on the cell opens the app.
        uyg.performClick()
        HareketTestYardim.bekle(1300)
        duzenle()
        val (x, y) = hucreMerkezi("Spotify")
        while (baslatilan() != null) Unit
        dokun(x, y)
        assertNotNull("kontrol: hücre dokunması uygulamayı açmalı", baslatilan())

        // E1: close the panel, 0.9 s later (old panel still VISIBLE, fading) tap where the cell was.
        uyg.performClick()
        assertEquals(Ekran.PARK, e.gecerli)
        HareketTestYardim.bekle(900)
        assertEquals(View.VISIBLE, e.gorunum(Ekran.UYGULAMALAR).gorunum.visibility)
        duzenle()
        dokun(x, y)
        assertNull("kapanan panel dokunma aldı", baslatilan())
        HareketTestYardim.bekle(1000)

        // Other way: the panel coming in at alpha 0 takes no tap (nothing visible to tap yet).
        uyg.performClick()
        HareketTestYardim.bekle(48)
        duzenle()
        assertTrue(e.gorunum(Ekran.UYGULAMALAR).gorunum.alpha < 0.5f)
        dokun(x, y)
        assertNull("alfa 0 panel dokunma aldı", baslatilan())
        assertEquals(Ekran.UYGULAMALAR, e.gecerli)
    }

    // --- 6. Diagnostics ---

    @Test
    fun teshisSahneVeGpsSatirlari() {
        c = TestYardim.acVeGec()
        a.katmanlar.kareHizi.performLongClick()
        val m = a.teshis.metin.toString()
        for (s in listOf("Sürüş sahnesi:", "set gece", "tempo ", "Hz", "Hız göstergesi:", "GPS aralığı", "GPS hız: ham", "yumuşatma", "Klip: ")) {
            assertTrue("$s yok:\n$m", m.contains(s))
        }
        a.teshis.kapat()
        // Driving still closes it (0.1 rule).
        a.katmanlar.kareHizi.performLongClick()
        hal(HareketHali.SURUS)
        assertFalse(a.teshis.acik)
    }
}
