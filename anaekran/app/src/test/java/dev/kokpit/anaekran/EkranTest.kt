package dev.kokpit.anaekran

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import dev.kokpit.anaekran.arayuz.AltCubuk
import dev.kokpit.anaekran.arayuz.AracHaritasi
import dev.kokpit.anaekran.arayuz.IsikCizgisi
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.Olcek
import dev.kokpit.anaekran.arayuz.TamEkran
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.ekran.Ekran
import dev.kokpit.anaekran.ekran.KlimaPaneli
import dev.kokpit.anaekran.ekran.SurusEkrani
import dev.kokpit.anaekran.ekran.UyariKatmani
import dev.kokpit.anaekran.hareket.HareketBaglayici
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.veri.AracVerisi
import dev.kokpit.anaekran.veri.HareketHali
import dev.kokpit.anaekran.veri.KlimaCozucu
import dev.kokpit.anaekran.veri.KlimaKaynagi
import dev.kokpit.anaekran.veri.SahteVeri
import dev.kokpit.anaekran.veri.VeriBaglayici
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

/** Screen flow, warnings, passive climate panel, hints, diagnostics and the İ-1 gaps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class EkranTest {

    private lateinit var c: ActivityController<MainActivity>
    private val a get() = c.get()
    private val e get() = a.katmanlar.ekranlar
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun hazirla() {
        TestYardim.esZamanli()
        Tema.zorla(Palet.GECE)
        AracVerisi.sifirla()
    }

    @After
    fun kapat() {
        if (::c.isInitialized) c.pause().stop().destroy()
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        Tema.zorla(null)
        AracVerisi.sifirla()
        Olcek.kur(768)
    }

    private fun hal(h: HareketHali?) = AracVerisi.guncelle { hareketHali(h) }

    private fun metinler(ekran: Ekran) = TestYardim.gorunurMetinler(e.gorunum(ekran).gorunum)

    @Test
    fun baglayicilarVeriSonraHareket() {
        val l = Baglayicilar.varsayilan()
        assertEquals(2, l.size)
        assertTrue(l[0] is VeriBaglayici)
        assertTrue(l[1] is HareketBaglayici)
    }

    @Test
    fun acilisKlibiParkaGecer() {
        c = TestYardim.ac()
        assertEquals(Ekran.ACILIS, e.gecerli)
        assertTrue(a.acilisSuruyor)
        assertEquals(View.INVISIBLE, (a.katmanlar.altCubuk.parent as View).visibility)
        assertEquals(dev.kokpit.anaekran.R.raw.klip_giris, TestYardim.oynaticilar.single().klip)
        // The fake player never starts: skipped after 1.5 s, held to 1.6 s, 350 ms fade.
        TestYardim.gecisleriBitir()
        assertFalse(a.acilisSuruyor)
        assertEquals(Ekran.PARK, e.gecerli)
        assertEquals(View.VISIBLE, (a.katmanlar.altCubuk.parent as View).visibility)
    }

    @Test
    fun acilisVeriSurusDiyorsaSurusaGecer() {
        SahteVeri.uygula(SahteVeri.surus62())
        c = TestYardim.ac()
        TestYardim.gecisleriBitir()
        assertEquals(Ekran.SURUS, e.gecerli)
    }

    @Test
    fun hareketHaliEkraniVeKlibiSecer() {
        c = TestYardim.acVeGec()
        assertEquals(Ekran.PARK, e.gecerli)
        val once = TestYardim.oynaticilar.size

        hal(HareketHali.SURUS)
        assertEquals(Ekran.SURUS, e.gecerli)
        // gecisOynat(Klip.SURUS, …) started a player on klip_surus before the switch.
        assertEquals(once + 1, TestYardim.oynaticilar.size)
        assertEquals(dev.kokpit.anaekran.R.raw.klip_surus, TestYardim.oynaticilar.last().klip)
        TestYardim.gecisleriBitir()

        hal(null) // unknown: nothing moves
        assertEquals(Ekran.SURUS, e.gecerli)
        assertEquals(once + 1, TestYardim.oynaticilar.size)

        hal(HareketHali.PARK)
        assertEquals(Ekran.PARK, e.gecerli)
        assertEquals(dev.kokpit.anaekran.R.raw.klip_surus_geri, TestYardim.oynaticilar.last().klip)
        TestYardim.gecisleriBitir()

        // A speed update with the same state does not act.
        AracVerisi.guncelle { hiz(3.0) }
        assertEquals(once + 2, TestYardim.oynaticilar.size)
    }

    @Test
    fun gunduzKlipYokDuzGecis() {
        Tema.zorla(Palet.GUNDUZ)
        c = TestYardim.acVeGec()
        val once = TestYardim.oynaticilar.size
        hal(HareketHali.SURUS)
        assertEquals(Ekran.SURUS, e.gecerli)
        assertEquals(once, TestYardim.oynaticilar.size) // plain fade: no player
        assertTrue(a.hareket!!.oynatici!!.calisiyor)
        TestYardim.gecisleriBitir()
        assertFalse(a.hareket!!.oynatici!!.calisiyor)
    }

    @Test
    fun panelAcikkenHareketHali() {
        c = TestYardim.acVeGec()
        a.katmanlar.altCubuk.klimaAlani.performClick()
        assertEquals(Ekran.KLIMA, e.gecerli)
        hal(HareketHali.SURUS) // driving off: the panel closes, speed comes
        assertEquals(Ekran.SURUS, e.gecerli)
        TestYardim.gecisleriBitir()

        a.katmanlar.altCubuk.dugme(AltCubuk.Simge.UYGULAMALAR).performClick()
        assertEquals(Ekran.UYGULAMALAR, e.gecerli)
        hal(HareketHali.PARK) // stopping: the panel stays, it returns to park
        assertEquals(Ekran.UYGULAMALAR, e.gecerli)
        assertEquals(Ekran.PARK, e.temel)
        assertTrue(e.geri())
        assertEquals(Ekran.PARK, e.gecerli)
    }

    @Test
    fun kapiUyarisiKapandiVeBilinmiyor() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        val u = a.uyari
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)

        AracVerisi.guncelle { kapiAcik(true) }
        assertEquals(UyariKatmani.Gorunum.PARK_BASLIGI, u.gorunum)
        assertTrue(u.metinler.toString(), u.metinler.contains("Kapı açık"))
        assertTrue(metinler(Ekran.PARK).contains("Açık")) // not "Biri açık": which door is not known
        assertFalse(metinler(Ekran.PARK).contains("Biri açık"))
        assertFalse(metinler(Ekran.PARK).contains("Gece mavisi")) // the warning takes the title's place

        AracVerisi.guncelle { kapiAcik(false) }
        assertEquals(UyariKatmani.Gorunum.KAPANDI_PARK, u.gorunum)
        assertTrue(u.metinler.contains("Kapı kapandı."))
        TestYardim.bekle(UyariKatmani.KAPANDI_MS + 100)
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)
        assertTrue(metinler(Ekran.PARK).contains("Gece mavisi"))

        // Unknown (service off) after open: no warning, and never "closed".
        AracVerisi.guncelle { kapiAcik(true) }
        AracVerisi.guncelle { kapiAcik(null) }
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)
        assertTrue(metinler(Ekran.PARK).contains("—"))
    }

    @Test
    fun zamanAsimiylaDusenKayittaKapandiYok() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        AracVerisi.guncelle { kapiAcik(true) }
        assertEquals(UyariKatmani.Gorunum.PARK_BASLIGI, a.uyari.gorunum)
        // The door service drops a never-listed record after 10 s: counter rises with the false.
        dev.kokpit.anaekran.veri.Teshis.kapiZamanAsimi++
        AracVerisi.guncelle { kapiAcik(false) }
        assertEquals(UyariKatmani.Gorunum.YOK, a.uyari.gorunum)
        assertTrue(metinler(Ekran.PARK).contains("Gece mavisi"))
        // A real close afterwards confirms again.
        AracVerisi.guncelle { kapiAcik(true) }
        AracVerisi.guncelle { kapiAcik(false) }
        assertEquals(UyariKatmani.Gorunum.KAPANDI_PARK, a.uyari.gorunum)
    }

    @Test
    fun klipSirasindaSahneGizli() {
        c = TestYardim.acVeGec()
        // Timing test: with Robolectric's free-running Choreographer an idle() during the screen
        // transition's animation moves the clock past the clip's 1.5 s first-frame limit.
        dev.kokpit.anaekran.hareket.HareketTestYardim.gercekSure()
        assertEquals(View.VISIBLE, a.katmanlar.sahne.visibility)
        hal(HareketHali.SURUS)
        val o = TestYardim.oynaticilar.last()
        assertEquals(dev.kokpit.anaekran.R.raw.klip_surus, o.klip)
        TestYardim.bosalt() // poster decoded, video view added
        o.dinleyici!!.basladi()
        o.dinleyici!!.kareGeldi()
        assertEquals(View.INVISIBLE, a.katmanlar.sahne.visibility)
        o.dinleyici!!.bitti()
        dev.kokpit.anaekran.hareket.HareketTestYardim.bekle(3500) // clip 2.13 s + 0.3 s hold + 0.6 s fade
        assertEquals(View.VISIBLE, a.katmanlar.sahne.visibility)
        assertFalse(a.hareket!!.oynatici!!.calisiyor)
    }

    @Test
    fun kapiVeBagajBirlikteVeSurusteKart() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        AracVerisi.guncelle { kapiAcik(true); bagajAcik(true) }
        assertTrue(a.uyari.metinler.contains("Kapı ve bagaj açık"))
        val park = metinler(Ekran.PARK)
        assertEquals(2, park.count { it == "Açık" }) // doors and boot

        AracVerisi.guncelle { bagajAcik(false) } // door still open: no confirmation, title follows
        assertEquals(UyariKatmani.Gorunum.PARK_BASLIGI, a.uyari.gorunum)
        assertTrue(a.uyari.metinler.contains("Kapı açık"))

        hal(HareketHali.SURUS)
        assertEquals(UyariKatmani.Gorunum.KART, a.uyari.gorunum)
        assertTrue(a.uyari.metinler.contains("Güvenli bir yerde dur ve kapat"))
        val isik = (a.katmanlar.altCubuk.parent as android.view.ViewGroup).getChildAt(1) as IsikCizgisi
        assertEquals(IsikCizgisi.Mod.ALARM, isik.mod)
        AracVerisi.guncelle { kapiAcik(false) }
        assertEquals(UyariKatmani.Gorunum.KAPANDI_KART, a.uyari.gorunum)
        TestYardim.bekle(UyariKatmani.KAPANDI_MS + 100)
        assertEquals(UyariKatmani.Gorunum.YOK, a.uyari.gorunum)
        assertEquals(IsikCizgisi.Mod.NORMAL, isik.mod)
    }

    @Test
    fun kapiAcikkenBagajAlani() {
        c = TestYardim.acVeGec()
        AracVerisi.guncelle { kapiAcik(true); bagajAcik(false) }
        var m = metinler(Ekran.PARK)
        assertTrue(m.contains("Açık"))
        assertTrue(m.contains("Kapalı")) // boot reported closed
        AracVerisi.guncelle { bagajAcik(null) }
        m = metinler(Ekran.PARK)
        assertFalse(m.contains("Kapalı")) // boot unknown is not "closed"
        assertTrue(m.contains("—"))
    }

    @Test
    fun klimaPaneliHicbirSeyGondermez() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        shadowOf(app).clearStartedServices()
        a.katmanlar.altCubuk.klimaAlani.performClick()
        val panel = e.gorunum(Ekran.KLIMA) as KlimaPaneli
        assertEquals("21,5°", panel.buyukMetin.text.toString())
        assertEquals("3", panel.fanMetin.text.toString())
        val m = metinler(Ekran.KLIMA)
        for (yasak in listOf("Hava yönü", "Ön camı çöz", "/ 8", "Tek bölge", "İç hava", "Arka cam")) {
            assertFalse(yasak, m.any { it.contains(yasak) })
        }
        for (v in panel.pasifDugmeler + panel.acOtoKarti) {
            assertTrue(v.performClick())
            assertEquals(Ekran.KLIMA, e.gecerli) // passive taps do not close the panel
        }
        assertTrue(a.kisaNot.text.contains("yalnız gösterim"))
        for (v in panel.dokunulabilirler) v.performClick()
        a.katmanlar.altCubuk.klimaAlani.performClick()
        TestYardim.gecisleriBitir()
        assertTrue(shadowOf(app).broadcastIntents.isEmpty())
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun klimaMetinSicaklikGorunur() {
        c = TestYardim.acVeGec()
        fun gonder(deger: String) {
            KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_KLIMA).putExtra(KlimaCozucu.SOL_SICAKLIK, deger))
            TestYardim.bosalt()
        }
        val alt = a.katmanlar.altCubuk
        gonder("23.0℃")
        assertTrue(TestYardim.gorunurMetinler(alt).contains("23°"))
        assertTrue(metinler(Ekran.PARK).contains("23°"))
        alt.klimaAlani.performClick()
        val panel = e.gorunum(Ekran.KLIMA) as KlimaPaneli
        assertEquals("23°", panel.buyukMetin.text.toString())

        gonder("21.5℃")
        assertEquals("21,5°", panel.buyukMetin.text.toString())
        assertTrue(TestYardim.gorunurMetinler(alt).contains("21,5°"))

        // Engine off: "0.0℃" is unknown, shown as a dash.
        gonder("0.0℃")
        assertEquals("—", panel.buyukMetin.text.toString())
        assertFalse(TestYardim.gorunurMetinler(alt).contains("0°"))
        assertTrue(alt.klimaAlani.contentDescription.endsWith(" —"))
    }

    @Test
    fun surusHizYokVeHiz() {
        c = TestYardim.acVeGec()
        hal(HareketHali.SURUS)
        val s = e.gorunum(Ekran.SURUS) as SurusEkrani
        assertEquals("—", s.hizMetni.toString())
        assertTrue(s.birimMetni.toString(), s.birimMetni.contains("bekleniyor") || s.birimMetni.contains("konum izni"))
        // 0.2 (K-96): the number comes from the smoothed GPS speed, not from AracDurumu.hiz.
        AracVerisi.guncelle { hiz(61.6) }
        assertEquals("—", s.hizMetni.toString())
        dev.kokpit.anaekran.veri.Hiz.yumusatici.ornek(61.6f / 3.6f, TestYardim.simdiNs(), null)
        TestYardim.kareler(2)
        assertEquals("62", s.hizMetni.toString())
        assertEquals("km/sa · gps", s.birimMetni.toString())
        assertFalse(metinler(Ekran.SURUS).any { it == "kapılar" || it == "bagaj" })
    }

    @Test
    fun sifirlaSonrasiTireler() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        assertTrue(metinler(Ekran.PARK).contains("21,5° · Oto"))
        AracVerisi.sifirla()
        val m = metinler(Ekran.PARK)
        assertEquals(3, m.count { it == "—" }) // climate, doors, boot
        assertTrue(m.contains("dış —"))
        assertTrue(TestYardim.gorunurMetinler(a.katmanlar.altCubuk).count { it == "—" } == 2)
    }

    @Test
    fun tamEkranVeEkranAcik() {
        c = TestYardim.acVeGec()
        val decor = a.window.decorView
        assertEquals(TamEkran.BAYRAKLAR, decor.systemUiVisibility)
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = 0 // e.g. a dialog took focus and the system cleared them
        a.onWindowFocusChanged(true)
        assertEquals(TamEkran.BAYRAKLAR, decor.systemUiVisibility)
    }

    @Test
    fun olcekFarkliGenislikler() {
        Olcek.kur(768)
        assertEquals(40, u(40))
        Olcek.kur(1024)
        assertEquals(53, u(40))
        assertEquals(1024, u(768))
        Olcek.kur(600)
        assertEquals(31, u(40))
        Olcek.kur(0) // ignored
        assertEquals(31, u(40))
        Olcek.kur(1536)
        val d = MutlakDuzen(app)
        val v = View(app)
        d.ekle(v, 40, 300, 170, 20)
        val lp = v.layoutParams as MutlakDuzen.Yer
        assertEquals(80, lp.x)
        assertEquals(600, lp.y)
        assertEquals(340, lp.width)
    }

    @Test
    fun geriTusuTabandaKapatmaz() {
        c = TestYardim.acVeGec()
        a.katmanlar.altCubuk.klimaAlani.performClick()
        @Suppress("DEPRECATION") a.onBackPressed()
        assertEquals(Ekran.PARK, e.gecerli)
        @Suppress("DEPRECATION") a.onBackPressed()
        assertFalse(a.isFinishing)
        assertEquals(Ekran.PARK, e.gecerli)
    }

    @Test
    fun teshisUzunBasinca() {
        c = TestYardim.acVeGec()
        assertFalse(a.teshis.acik)
        assertTrue(a.katmanlar.kareHizi.performLongClick())
        assertTrue(a.teshis.acik)
        val m = a.teshis.metin.toString()
        assertTrue(m, m.contains("Klima ham"))
        assertTrue(m, m.contains("u() ölçeği 1.000"))
        assertTrue(m, m.contains("768×1024"))
        assertTrue(m, m.contains("Klip: "))
        assertTrue(m, m.contains("zaman aşımıyla düşen"))
        a.teshis.performClick()
        assertFalse(a.teshis.acik)
    }

    @Test
    fun ipuclariBirKezVeAyarlar() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        c = TestYardim.acVeGec()
        val park = e.gorunum(Ekran.PARK) as dev.kokpit.anaekran.ekran.ParkEkrani
        val kart = park.ipucuKarti
        assertEquals(View.VISIBLE, kart.visibility)
        assertEquals(dev.kokpit.anaekran.ekran.Ipucu.ERISILEBILIRLIK, kart.ipucu)

        kart.performClick() // opens Settings > Accessibility, closes the hint
        val niyet = shadowOf(app).nextStartedActivity
        assertNotNull(niyet)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, niyet.action)
        assertEquals(dev.kokpit.anaekran.ekran.Ipucu.KONUM, kart.ipucu) // next one

        kart.kapatDugmesi.performClick() // X: closes without opening anything
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(View.GONE, kart.visibility)

        // Same session: a new activity does not show them again.
        c.pause().stop().destroy()
        c = TestYardim.acVeGec()
        assertEquals(View.GONE, (e.gorunum(Ekran.PARK) as dev.kokpit.anaekran.ekran.ParkEkrani).ipucuKarti.visibility)
    }

    @Test
    fun ipucuYokServisVeIzinVarsa() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "dev.kokpit.anaekran/.veri.KapiServisi")
        c = TestYardim.acVeGec()
        assertEquals(View.GONE, (e.gorunum(Ekran.PARK) as dev.kokpit.anaekran.ekran.ParkEkrani).ipucuKarti.visibility)
    }

    @Test
    fun aracHaritasiReferansNoktalari() {
        AracHaritasi.yukle(app)
        val gece = AracHaritasi.noktalar(AracHaritasi.Kare.PARK_GECE)
        assertEquals(477f, gece.tavan!!.x, 0.6f)
        assertEquals(386f, gece.tavan!!.y, 0.6f)
        assertEquals(546f, gece.kapi!!.x, 0.6f)
        assertEquals(471f, gece.kapi!!.y, 0.6f)
        assertEquals(646f, gece.bagaj!!.x, 0.6f)
        val bagaj = AracHaritasi.noktalar(AracHaritasi.Kare.BAGAJ)
        assertEquals(594f, bagaj.bagaj!!.x, 0.6f)
        assertEquals(274f, bagaj.kapi!!.x, 0.6f)
        val surus = AracHaritasi.noktalar(AracHaritasi.Kare.SURUS_GECE)
        assertEquals(471f, surus.kapi!!.x, 0.6f)
        assertEquals(481f, surus.kapi!!.y, 0.6f)
    }

    // --- İ-4 ---

    private fun tumMetinGorunumleri(v: View): List<android.widget.TextView> = when (v) {
        is android.widget.TextView -> listOf(v)
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { tumMetinGorunumleri(v.getChildAt(it)) }
        else -> emptyList()
    }

    /** The first visible long-press target of the warning layer (card or park title). */
    private fun uzunBasmaHedefi(): View {
        fun gez(v: View): View? {
            if (v.visibility != View.VISIBLE) return null
            if (v !== a.uyari && v.isLongClickable) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) gez(v.getChildAt(i))?.let { return it }
            return null
        }
        return gez(a.uyari) ?: error("no long-press target")
    }

    @Test
    fun acilisGunduzdeDeGeceRenkleri() {
        Tema.zorla(Palet.GUNDUZ)
        c = TestYardim.ac()
        assertEquals(Ekran.ACILIS, e.gecerli)
        val gece = setOf(Palet.GECE.tx, Palet.GECE.mu, Palet.GECE.hud)
        fun renkler() = tumMetinGorunumleri(e.gorunum(Ekran.ACILIS).gorunum).map { it.currentTextColor }
        assertTrue(renkler().isNotEmpty())
        assertTrue(renkler().toString(), renkler().all { it in gece })
        assertFalse(renkler().contains(Palet.GUNDUZ.tx)) // #0B0F16 on the night clip: black on black
        // A theme change during the intro does not touch it either.
        Tema.zorla(Palet.GECE)
        Tema.zorla(Palet.GUNDUZ)
        assertTrue(renkler().all { it in gece })
    }

    @Test
    fun uzunBasmaKapiUyarisiniDegerDegisenekadarGizler() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        val u = a.uyari
        val isik = (a.katmanlar.altCubuk.parent as android.view.ViewGroup).getChildAt(1) as IsikCizgisi
        AracVerisi.guncelle { kapiAcik(true) }
        assertEquals(UyariKatmani.Gorunum.PARK_BASLIGI, u.gorunum)
        assertEquals(IsikCizgisi.Mod.KAPI, isik.mod)
        // A tap does nothing (only the long press hides).
        uzunBasmaHedefi().performClick()
        assertEquals(UyariKatmani.Gorunum.PARK_BASLIGI, u.gorunum)

        assertTrue(uzunBasmaHedefi().performLongClick())
        assertTrue(u.susturuldu)
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)
        assertEquals(IsikCizgisi.Mod.NORMAL, isik.mod)
        assertTrue(metinler(Ekran.PARK).contains("Gece mavisi")) // the park title is back
        assertTrue(metinler(Ekran.PARK).contains("Açık")) // the data itself is not hidden

        // The same value again, or another field (speed, park/drive): still hidden.
        AracVerisi.guncelle { kapiAcik(true) }
        AracVerisi.guncelle { hiz(3.0) }
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)
        hal(HareketHali.SURUS)
        assertEquals(Ekran.SURUS, e.gecerli)
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)
        assertEquals(IsikCizgisi.Mod.NORMAL, isik.mod)

        // A door/boot VALUE change brings it back: the boot opens too.
        AracVerisi.guncelle { bagajAcik(true) }
        assertFalse(u.susturuldu)
        assertEquals(UyariKatmani.Gorunum.KART, u.gorunum)
        assertEquals(IsikCizgisi.Mod.ALARM, isik.mod)

        // The drive card hides the same way, and closing brings the "closed" confirmation.
        assertTrue(uzunBasmaHedefi().performLongClick())
        assertEquals(UyariKatmani.Gorunum.YOK, u.gorunum)
        AracVerisi.guncelle { kapiAcik(false); bagajAcik(false) }
        assertFalse(u.susturuldu)
        assertEquals(UyariKatmani.Gorunum.KAPANDI_KART, u.gorunum)
    }

    @Test
    fun teshisKapiUyarisiniSifirlarYalnizUygulamaIci() {
        SahteVeri.uygula(SahteVeri.parkGece())
        c = TestYardim.acVeGec()
        AracVerisi.guncelle { kapiAcik(true); bagajAcik(true) }
        assertEquals(UyariKatmani.Gorunum.PARK_BASLIGI, a.uyari.gorunum)
        a.katmanlar.kareHizi.performLongClick()
        assertTrue(a.teshis.acik)
        val oncekiYayinlar = shadowOf(app).broadcastIntents.size
        val oncekiServisler = shadowOf(app).nextStartedService

        a.teshis.sifirlaDugmesi.performClick()
        TestYardim.bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
        assertNull(AracVerisi.durum.bagajAcik)
        assertEquals(UyariKatmani.Gorunum.YOK, a.uyari.gorunum) // unknown is not "closed": no confirmation
        assertTrue(a.teshis.acik) // stays open to show the result
        assertTrue(a.teshis.sifirlaDugmesi.text.startsWith("Sıfırlandı"))
        // Nothing left the app: no broadcast, no service, no activity.
        assertEquals(oncekiYayinlar, shadowOf(app).broadcastIntents.size)
        assertNull(oncekiServisler)
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(a).nextStartedActivity)
        // Next open shows the button text again.
        a.teshis.kapat()
        a.katmanlar.kareHizi.performLongClick()
        assertEquals("Kapı uyarısını sıfırla — yalnız bu uygulamada, araca bir şey gönderilmez", a.teshis.sifirlaDugmesi.text.toString())
    }

    @Test
    fun surusBaslayincaTeshisKapanir() {
        c = TestYardim.acVeGec()
        assertEquals(Ekran.PARK, e.gecerli)
        a.katmanlar.kareHizi.performLongClick()
        assertTrue(a.teshis.acik)
        hal(HareketHali.SURUS) // from the park base screen
        assertEquals(Ekran.SURUS, e.gecerli)
        assertFalse(a.teshis.acik)

        TestYardim.gecisleriBitir()
        a.katmanlar.kareHizi.performLongClick()
        hal(HareketHali.PARK) // stopping does not close it
        assertTrue(a.teshis.acik)
    }
}
