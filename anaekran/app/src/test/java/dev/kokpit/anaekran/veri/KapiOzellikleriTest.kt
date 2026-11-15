package dev.kokpit.anaekran.veri

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Door/boot from persist.vendor.cem.cardoor1..5 (K-116), with fake properties, processes and schedule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KapiOzellikleriTest {

    private var t = 0L

    /** Fake properties; a missing key reads as "" (not set), [bozuk] makes every read fail. */
    private class SahteOkuyucu(override val ad: String = "yansıma") : KapiOzellikleri.OzellikOkuyucu {
        val degerler = HashMap<String, String>()
        var bozuk = false
        var okuma = 0
        fun ayarla(vararg d: String) = d.forEachIndexed { i, v -> degerler[KapiOzellikleri.ADLAR[i]] = v }
        override fun oku(ozellik: String): String? {
            okuma++
            return if (bozuk) null else degerler[ozellik] ?: ""
        }
    }

    /** Records the schedule; [adim] runs the next pass and returns the delay it was scheduled with. */
    private class SahteZamanlayici : KapiOzellikleri.Zamanlayici {
        val bekleyen = ArrayList<Pair<Long, Runnable>>()
        var bitti = false
        override fun sonra(gecikmeMs: Long, r: Runnable) { bekleyen += gecikmeMs to r }
        override fun temizle() = bekleyen.clear()
        override fun bitir() { bekleyen.clear(); bitti = true }
        fun adim(): Long {
            val (ms, r) = bekleyen.removeAt(0)
            r.run()
            return ms
        }
        val sonrakiGecikme: Long get() = bekleyen.single().first
    }

    /** A stream that remembers whether it was closed. */
    private class IzliAkis(b: ByteArray) : ByteArrayInputStream(b) {
        var kapandi = false
        override fun close() { kapandi = true; super.close() }
    }

    private class SahteSurec(
        private val cikti: String,
        private val biter: Boolean = true,
        private val kod: Int = 0,
    ) : Process() {
        var yokEdildi = false
        val girdi = IzliAkis(cikti.toByteArray())
        val hata = IzliAkis(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = girdi
        override fun getErrorStream(): InputStream = hata
        override fun waitFor(): Int = kod
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = biter
        override fun exitValue(): Int = if (biter) kod else throw IllegalThreadStateException()
        override fun destroy() { yokEdildi = true }
    }

    private val okuyucu = SahteOkuyucu()
    private val zaman = SahteZamanlayici()
    private var ayarSayisi = 0
    private var ayarDegeri = "0"
    private var dinlenen = 0
    private val dinleyici = AracDinleyici { dinlenen++ }

    private fun ozellikler(o: KapiOzellikleri.OzellikOkuyucu = okuyucu) = KapiOzellikleri(
        okuyucuSec = { o },
        ayar = { ayarSayisi++; ayarDegeri },
        zamanlayici = zaman,
        saat = { t },
    )

    @Before
    fun hazirla() {
        KapiOzellikleri.testSifirla()
        AracVerisi.saat = { t }
        AracVerisi.dinle(dinleyici)
    }

    @After
    fun temizle() {
        AracVerisi.birak(dinleyici)
        AracVerisi.saat = { SystemClock.elapsedRealtime() }
        KapiOzellikleri.testSifirla()
        KapiServisi.yurutucu = null
        KapiServisi.gecikmeli = null
        AracVerisi.sifirla()
        Teshis.sifirla()
        ShadowSystemProperties.reset()
    }

    private fun bosalt() = shadowOf(Looper.getMainLooper()).idle()
    private val d get() = AracVerisi.durum

    // --- Pure resolution ---

    @Test
    fun degerCozumu() {
        assertEquals(false, KapiOzellikCozumu.deger("1"))
        assertEquals(true, KapiOzellikCozumu.deger("2"))
        assertEquals(true, KapiOzellikCozumu.deger(" 2\n"))
        assertNull(KapiOzellikCozumu.deger(""))
        assertNull(KapiOzellikCozumu.deger(null))
        assertNull(KapiOzellikCozumu.deger("0"))
        assertNull(KapiOzellikCozumu.deger("3"))
        assertNull(KapiOzellikCozumu.deger("open"))
    }

    @Test
    fun kapiBirlesimi() {
        assertEquals(true, KapiOzellikCozumu.kapiAcik(listOf(false, true, null, null))) // an open one wins
        assertEquals(false, KapiOzellikCozumu.kapiAcik(listOf(false, false, false, false)))
        assertNull(KapiOzellikCozumu.kapiAcik(listOf(false, false, null, false))) // unknown, none open
        assertNull(KapiOzellikCozumu.kapiAcik(listOf(null, null, null, null)))
        assertTrue(KapiOzellikCozumu.okunabilir(listOf("", "", "", "", "1")))
        assertTrue(KapiOzellikCozumu.okunabilir(listOf(null, "0", null, null, null))) // non-empty, even if not understood
        assertFalse(KapiOzellikCozumu.okunabilir(listOf("", "", "", "", "")))
        assertFalse(KapiOzellikCozumu.okunabilir(listOf(null, null, null, null, null)))
    }

    // --- Source choice ---

    @Test
    fun kaynakSecimi_bosOzelliklerdeErisilebilirlikYazar_birKezDoluysaOzellik() {
        val o = ozellikler()
        o.basla()
        assertEquals(0L, zaman.adim()) // first pass at once
        // All five empty: not readable. Nothing written, the accessibility path stays in charge.
        assertFalse(KapiOzellikleri.birincil)
        assertEquals(KapiKaynagi.YOK, d.kapiKaynagi)
        assertEquals(0, dinlenen)
        assertEquals("yansıma", Teshis.kapiOzellikOkuma)
        AracVerisi.guncelle { kapiAcik(true); kapiKaynagi(KapiKaynagi.ERISILEBILIRLIK) } // as KapiServisi would
        assertEquals(true, d.kapiAcik)

        // One non-empty value is enough: the properties take over and write all of their state.
        okuyucu.ayarla("", "", "", "", "1")
        zaman.adim()
        assertTrue(KapiOzellikleri.birincil)
        assertEquals(KapiKaynagi.OZELLIK, d.kapiKaynagi)
        assertNull(d.kapiAcik) // doors 1–4 unknown, none open
        assertEquals(false, d.bagajAcik)
        assertEquals(listOf<Boolean?>(null, null, null, null), d.kapilar)

        // Sticky: empty again does not hand it back.
        okuyucu.ayarla("", "", "", "", "")
        zaman.adim()
        assertTrue(KapiOzellikleri.birincil)
        assertEquals(KapiKaynagi.OZELLIK, d.kapiKaynagi)
        assertNull(d.bagajAcik)
    }

    @Test
    fun okumaCalismiyorsa_okunamaz() {
        okuyucu.bozuk = true
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertFalse(KapiOzellikleri.birincil)
        assertEquals("okunamıyor", Teshis.kapiOzellikOkuma)
        assertEquals(listOf<String?>(null, null, null, null, null), Teshis.kapiOzellikHam)
    }

    @Test
    fun acikKapaliBilinmiyorBirlesimi_veKapilar() {
        okuyucu.ayarla("1", "1", "1", "1", "1")
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertEquals(false, d.kapiAcik)
        assertEquals(false, d.bagajAcik)
        assertEquals(listOf<Boolean?>(false, false, false, false), d.kapilar)

        okuyucu.ayarla("1", "1", "2", "1", "1") // rear left open
        zaman.adim()
        assertEquals(true, d.kapiAcik)
        assertEquals(false, d.bagajAcik)
        assertEquals(listOf<Boolean?>(false, false, true, false), d.kapilar)
        assertEquals(t, d.kapilarZamani)

        okuyucu.ayarla("1", "", "1", "1", "2") // one unknown, none open; boot open
        zaman.adim()
        assertNull(d.kapiAcik)
        assertEquals(true, d.bagajAcik)

        okuyucu.ayarla("1", "", "2", "1", "x") // open beats unknown; boot value not understood
        zaman.adim()
        assertEquals(true, d.kapiAcik)
        assertNull(d.bagajAcik)
    }

    @Test
    fun cleanAvmDoor_hepsiBireCekilince_kapandi() {
        okuyucu.ayarla("2", "1", "1", "2", "2")
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertEquals(true, d.kapiAcik)
        assertEquals(true, d.bagajAcik)
        val once = dinlenen

        // Util.cleanAvmDoor(): all five to "1" at once. Taken as a real close (K-116): the screens'
        // "Kapı kapandı." confirmation follows from true → false.
        okuyucu.ayarla("1", "1", "1", "1", "1")
        t += 250
        zaman.adim()
        assertEquals(false, d.kapiAcik)
        assertEquals(false, d.bagajAcik)
        assertEquals(listOf<Boolean?>(false, false, false, false), d.kapilar)
        assertEquals(once + 1, dinlenen) // one write for the whole change
        assertTrue(Teshis.kapiOlaylari().last().contains("1=2→1"))
        assertTrue(Teshis.kapiOlaylari().last().contains("5=2→1"))
    }

    @Test
    fun yansimada250ms_yalnizDegisimdeYazar() {
        okuyucu.ayarla("1", "1", "1", "1", "1")
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertEquals(1, dinlenen)
        val yazma = d.kapiAcikZamani

        // Parked, nothing changes: ten passes, every one 250 ms apart, no write at all (not even a silent one).
        repeat(10) {
            assertEquals(KapiOzellikleri.ARALIK_MS, zaman.sonrakiGecikme)
            t += 250
            zaman.adim()
        }
        assertEquals(1, dinlenen)
        assertEquals(yazma, d.kapiAcikZamani)
        assertEquals(55, okuyucu.okuma) // 11 passes × 5 properties

        okuyucu.ayarla("1", "2", "1", "1", "1")
        t += 250
        zaman.adim()
        assertEquals(2, dinlenen)
        assertEquals(t, d.kapiAcikZamani)
    }

    @Test
    fun yasamDongusu_baslaHemenOkur_durDurdurur() {
        okuyucu.ayarla("1", "1", "1", "1", "1")
        val o = ozellikler()
        o.basla()
        assertEquals(0L, zaman.sonrakiGecikme)
        zaman.adim()
        val eski = zaman.bekleyen.single().second

        o.dur()
        assertTrue(zaman.bekleyen.isEmpty())
        val sayi = okuyucu.okuma
        eski.run() // a pass that was already queued when dur() came: does nothing, schedules nothing
        assertEquals(sayi, okuyucu.okuma)
        assertTrue(zaman.bekleyen.isEmpty())

        // While stopped the door opened: the read at basla() picks it up at once.
        okuyucu.ayarla("2", "1", "1", "1", "1")
        o.basla()
        assertEquals(0L, zaman.adim())
        assertEquals(true, d.kapiAcik)

        o.birak()
        assertTrue(zaman.bitti)
        assertTrue(zaman.bekleyen.isEmpty())
    }

    // --- Away from the app (R1 ÖNEMLİ-1): unknown while stopped, no late "Kapı kapandı." ---

    @Test
    fun dur_kapiBilinmiyor_donusteGuncelDegerYazilir() {
        okuyucu.ayarla("2", "1", "1", "1", "2")
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertEquals(true, d.kapiAcik)
        assertEquals(true, d.bagajAcik)

        o.dur() // onPause: CarPlay comes to the front
        assertNull(d.kapiAcik)
        assertNull(d.bagajAcik)
        assertEquals(AracDurumu.KAPILAR_BILINMIYOR, d.kapilar)
        assertEquals(KapiKaynagi.OZELLIK, d.kapiKaynagi)

        // Closed while away: on return null → false (no confirmation: that is true → false only).
        okuyucu.ayarla("1", "1", "1", "1", "1")
        o.basla()
        zaman.adim()
        assertEquals(false, d.kapiAcik)
        assertEquals(false, d.bagajAcik)

        // Unchanged values are still written after a stop: the null in between must be replaced.
        o.dur()
        o.basla()
        zaman.adim()
        assertEquals(false, d.kapiAcik)
        assertEquals(listOf<Boolean?>(false, false, false, false), d.kapilar)
    }

    @Test
    fun dur_erisilebilirlikKaynakkenDokunmaz() {
        // Properties never readable: the accessibility path owns the state, dur() leaves it alone.
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertFalse(KapiOzellikleri.birincil)
        AracVerisi.guncelle { kapiAcik(true); kapiKaynagi(KapiKaynagi.ERISILEBILIRLIK) }
        o.dur()
        assertEquals(true, d.kapiAcik)
    }

    @Test
    fun durGecisSirasindaGelirse_yazmaDusurulur() {
        var durdur: (() -> Unit)? = null
        val araya = object : KapiOzellikleri.OzellikOkuyucu {
            override val ad = "yansıma"
            var n = 0
            override fun oku(ozellik: String): String? {
                if (++n == 3) durdur?.invoke()
                return okuyucu.oku(ozellik)
            }
        }
        okuyucu.ayarla("2", "1", "1", "1", "1")
        val o = ozellikler(araya)
        o.basla()
        zaman.adim()
        assertEquals(true, d.kapiAcik)

        // The door closes; onPause comes while the worker is in the middle of the next pass.
        okuyucu.ayarla("1", "1", "1", "1", "1")
        araya.n = 0
        durdur = { o.dur() }
        zaman.adim()
        assertNull("the stopped pass does not overwrite the null", d.kapiAcik)
        assertTrue("and plans no next pass", zaman.bekleyen.isEmpty())

        // Back: the current value, no stale "open" and no true → false.
        durdur = null
        o.basla()
        zaman.adim()
        assertEquals(false, d.kapiAcik)
    }

    @Test
    fun isciZamanlayici_bitirdenSonraIsParcacigiAcmaz() {
        // R1 KÜÇÜK-5: a pass that asks for the next one after birak() must not start a new thread.
        val z = KapiOzellikleri.IsciZamanlayici()
        val calisti = java.util.concurrent.CountDownLatch(1)
        z.sonra(0L, Runnable { calisti.countDown() })
        assertTrue(calisti.await(2, TimeUnit.SECONDS))
        assertTrue(z.isciVar)
        z.bitir()
        assertFalse(z.isciVar)
        z.sonra(0L, Runnable { throw AssertionError("ran after bitir") })
        assertFalse("no thread after bitir", z.isciVar)
        z.temizle()
        z.bitir() // twice: harmless
        assertFalse(z.isciVar)
    }

    @Test
    fun uygulamaIciSifirlama_sonrakiGecisYenidenYazar() {
        okuyucu.ayarla("2", "1", "1", "1", "1")
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertEquals(true, d.kapiAcik)

        KapiServisi.uygulamaIciSifirla()
        bosalt()
        assertNull(d.kapiAcik)
        assertEquals(AracDurumu.KAPILAR_BILINMIYOR, d.kapilar)

        // Same values as before, still written again.
        zaman.adim()
        assertEquals(true, d.kapiAcik)
        assertEquals(false, d.bagajAcik)
        assertEquals(listOf<Boolean?>(true, false, false, false), d.kapilar)
    }

    // --- Accessibility gated by the properties ---

    private fun olay(tur: Int, paket: String?, metin: String?, pencereId: Int): AccessibilityEvent =
        AccessibilityEvent.obtain(tur).apply {
            packageName = paket
            className = "com.hcn.autocan.CanDoorWindow"
            if (metin != null) text.add(metin)
            AccessibilityEvent::class.java.getMethod("setWindowId", Int::class.javaPrimitiveType).invoke(this, pencereId)
        }

    private fun pencere(id: Int, paket: String, vararg metin: String): AccessibilityWindowInfo {
        val kok = AccessibilityNodeInfo.obtain().apply { packageName = paket; className = "android.widget.FrameLayout" }
        for (m in metin) shadowOf(kok).addChild(AccessibilityNodeInfo.obtain().apply { packageName = paket; text = m })
        return AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(id)
            shadowOf(it).setRoot(kok)
        }
    }

    @Test
    fun ozellikBirincilken_erisilebilirlikYazmaz_yalnizKaydeder() {
        KapiServisi.yurutucu = Executor { it.run() }
        KapiServisi.gecikmeli = { _, _ -> }
        val s = Robolectric.setupService(KapiServisi::class.java)
        s.onServiceConnected()
        bosalt()
        assertEquals(KapiKaynagi.ERISILEBILIRLIK, d.kapiKaynagi)

        // Before the properties are readable the popup path writes as in 0.2.
        shadowOf(s).setWindows(listOf(pencere(7, KapiMetni.PAKET, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, KapiMetni.PAKET, "Kapı açık", 7))
        bosalt()
        assertEquals(true, d.kapiAcik)

        // Properties readable: all closed. They overwrite, and from now on the popup does not write.
        okuyucu.ayarla("1", "1", "1", "1", "1")
        val o = ozellikler()
        o.basla()
        zaman.adim()
        bosalt()
        assertEquals(KapiKaynagi.OZELLIK, d.kapiKaynagi)
        assertEquals(false, d.kapiAcik)

        shadowOf(s).setWindows(listOf(pencere(8, KapiMetni.PAKET, "Bagaj açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, KapiMetni.PAKET, "Bagaj açık", 8))
        bosalt()
        assertEquals(false, d.bagajAcik)
        assertEquals(KapiKaynagi.OZELLIK, d.kapiKaynagi)
        assertTrue(Teshis.kapiOlaylari().any { it.contains("Bagaj açık") }) // still logged

        // The service stopping does not touch the properties' state.
        s.onDestroy()
        bosalt()
        assertEquals(false, d.kapiAcik)
        assertEquals(false, d.bagajAcik)
        assertEquals(KapiKaynagi.OZELLIK, d.kapiKaynagi)
    }

    // --- Readers ---

    @Test
    fun yansimaOkuyucu_gercekSystemProperties() {
        ShadowSystemProperties.override("persist.vendor.cem.cardoor1", "2")
        val y = KapiOzellikleri.YansimaOkuyucu.olustur()!!
        assertEquals("yansıma", y.ad)
        assertEquals("2", y.oku("persist.vendor.cem.cardoor1"))
        assertEquals("", y.oku("persist.vendor.cem.cardoor4"))
    }

    @Test
    fun yansimaCalismazsa_getpropaGecer() {
        // A Method whose call always fails, as a blocked hidden API would.
        val bozukYansima = KapiOzellikleri.YansimaOkuyucu(Integer::class.java.getMethod("parseInt", String::class.java))
        val surecler = ArrayList<List<String>>()
        val getprop = KapiOzellikleri.GetpropOkuyucu({ ad -> surecler += listOf("getprop", ad); SahteSurec("1\n") })
        val o = KapiOzellikleri({ bozukYansima }, { "0" }, zaman, { t }, { getprop })
        o.basla()
        zaman.adim()
        assertEquals("getprop", o.okuyucuAdi)
        assertEquals("getprop", Teshis.kapiOzellikOkuma)
        assertEquals(5, surecler.size)
        assertEquals(false, d.kapiAcik)
        assertEquals(KapiOzellikleri.GETPROP_ARALIK_MS, zaman.sonrakiGecikme)
    }

    @Test
    fun getprop_sahteSurecle_okurVeHerDurumdaYokEder() {
        val surecler = ArrayList<SahteSurec>()
        val komutlar = ArrayList<String>()
        var cikti = "2\n"
        var biter = true
        var kod = 0
        val g = KapiOzellikleri.GetpropOkuyucu({ ad ->
            komutlar += ad
            SahteSurec(cikti, biter, kod).also { surecler += it }
        })
        assertEquals("2", g.oku("persist.vendor.cem.cardoor1"))
        cikti = "\n"
        assertEquals("", g.oku("persist.vendor.cem.cardoor5")) // not set
        biter = false
        assertNull(g.oku("persist.vendor.cem.cardoor2")) // hangs: time-out, unknown
        biter = true; kod = 1
        assertNull(g.oku("persist.vendor.cem.cardoor3")) // failed
        assertTrue(surecler.all { it.yokEdildi })
        // The pipes are closed on every path, the time-out and the failure too (R1 KÜÇÜK-7a).
        assertTrue(surecler.all { it.girdi.kapandi && it.hata.kapandi })
        assertEquals(4, surecler.size)

        // Only the five fixed names ever reach a process.
        assertNull(g.oku("ro.serialno"))
        assertNull(g.oku("persist.vendor.cem.cardoor1; reboot"))
        assertEquals(4, komutlar.size)

        // The starter throwing (no getprop binary) is a failed read, not a crash.
        val yok = KapiOzellikleri.GetpropOkuyucu({ throw java.io.IOException("no getprop") })
        assertNull(yok.oku("persist.vendor.cem.cardoor1"))
    }

    @Test
    fun getpropAraligi_okunabilirken1sn_degilken5sn() {
        var cikti = "\n"
        val g = KapiOzellikleri.GetpropOkuyucu({ SahteSurec(cikti) })
        val o = ozellikler(g)
        o.basla()
        zaman.adim()
        assertFalse(KapiOzellikleri.birincil)
        assertEquals(KapiOzellikleri.BEKLEME_ARALIK_MS, zaman.sonrakiGecikme)
        cikti = "1\n"
        zaman.adim()
        assertTrue(KapiOzellikleri.birincil)
        assertEquals(KapiOzellikleri.GETPROP_ARALIK_MS, zaman.sonrakiGecikme)
        assertEquals(false, d.kapiAcik)
    }

    // --- can_ignore_doorinfo ---

    @Test
    fun canIgnoreDoorinfo_ikiSaniyedeBirOkunur() {
        val o = ozellikler()
        o.basla()
        zaman.adim()
        assertEquals(1, ayarSayisi)
        assertEquals("0", Teshis.canIgnoreDoorinfo)
        repeat(7) { t += 250; zaman.adim() } // up to 1.75 s
        assertEquals(1, ayarSayisi)
        ayarDegeri = "1"
        t += 250; zaman.adim() // 2.0 s
        assertEquals(2, ayarSayisi)
        assertEquals("1", Teshis.canIgnoreDoorinfo)
    }

    @Test
    fun sistemAyari_yalnizOkur() {
        val cr = RuntimeEnvironment.getApplication().contentResolver
        val a = KapiOzellikleri.SistemAyari(cr)
        assertEquals("okunamadı (ayar yok)", a.oku())
        Settings.System.putInt(cr, KapiOzellikleri.AYAR_CAN_IGNORE_DOORINFO, 0) // test only: the app never writes
        assertEquals("0", a.oku())
        Settings.System.putString(cr, KapiOzellikleri.AYAR_CAN_IGNORE_DOORINFO, "x")
        assertEquals("okunamadı (ayar yok)", a.oku()) // not a number: SettingNotFoundException
    }

    // --- Diagnostics ---

    @Test
    fun teshisSatirlari() {
        assertTrue(Teshis.satirlar().contains("Kapı özellikleri: 1=— 2=— 3=— 4=— 5=— · kaynak yok · okuma başlamadı · son değişim hiç"))
        assertTrue(Teshis.satirlar().contains("can_ignore_doorinfo: okunmadı"))

        okuyucu.ayarla("1", "1", "1", "1", "")
        val o = ozellikler()
        o.basla()
        t = 1_000
        zaman.adim()
        okuyucu.ayarla("2", "1", "1", "1", "")
        t = 2_000
        zaman.adim()
        t = 7_500
        val satirlar = Teshis.satirlar()
        assertTrue(satirlar.toString(), satirlar.contains(
            "Kapı özellikleri: 1=2 2=1 3=1 4=1 5=boş · kaynak özellik · okuma yansıma · son değişim 5 sn önce",
        ))
        assertTrue(satirlar.contains("can_ignore_doorinfo: 0"))

        okuyucu.bozuk = true
        zaman.adim()
        assertTrue(Teshis.satirlar().any { it.startsWith("Kapı özellikleri: 1=? 2=? 3=? 4=? 5=? · kaynak özellik · okuma okunamıyor") })
    }

    // --- VeriBaglayici ---

    @Test
    fun veriBaglayici_baslaDurBirak() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        okuyucu.ayarla("1", "1", "1", "1", "2")
        val b = VeriBaglayici()
        b.kapiOzellikUretici = { ozellikler() }
        b.hazirla(a)
        assertTrue(zaman.bekleyen.isEmpty()) // nothing before onResume
        b.basla()
        assertEquals(0L, zaman.adim())
        assertEquals(true, d.bagajAcik)
        b.dur()
        assertTrue(zaman.bekleyen.isEmpty())
        b.basla()
        assertEquals(0L, zaman.sonrakiGecikme)
        b.birak()
        assertTrue(zaman.bitti)
        assertNull(b.kapiOzellikleri)
    }
}
