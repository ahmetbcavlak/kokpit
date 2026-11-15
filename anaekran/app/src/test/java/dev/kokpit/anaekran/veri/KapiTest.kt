package dev.kokpit.anaekran.veri

import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executor

/** Door/boot from com.hcn.autocan's popup, with fake accessibility events and windows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KapiTest {

    private val AUTOCAN = KapiMetni.PAKET
    private val BASKA = "com.example.baska"

    private val gecikenler = ArrayList<Runnable>()
    private val gecikmeler = ArrayList<Long>()
    private val abonelikler = ArrayList<Boolean>()

    @Before
    fun hazirla() {
        KapiOzellikleri.testSifirla() // the properties are not the source here: the 0.2 path writes
        KapiServisi.yurutucu = Executor { it.run() }
        KapiServisi.gecikmeli = { ms, r -> gecikmeler += ms; gecikenler += r }
        // The 0.2 rules are tested without the closing hold; the hold has its own tests below.
        KapiServisi.kapanisBeklemesi = 0L
        KapiServisi.abonelikKaydi = { abonelikler += it }
    }

    @After
    fun temizle() {
        KapiServisi.yurutucu = null
        KapiServisi.gecikmeli = null
        KapiServisi.kapanisBeklemesi = null
        KapiServisi.abonelikKaydi = null
        AracVerisi.sifirla()
        Teshis.sifirla()
        KapiOzellikleri.testSifirla()
    }

    private fun bosalt() = shadowOf(Looper.getMainLooper()).idle()

    // --- Text matching ---

    @Test
    fun metinTurkceVeBuyukKucukHarfeDayanikli() {
        assertEquals("kapi acik", KapiMetni.sade("Kapı açık"))
        assertEquals("kapi acik", KapiMetni.sade("KAPI AÇIK"))
        assertEquals("kapi acik", KapiMetni.sade("KAPİ  AÇIK "))
        assertEquals("kapi acik", KapiMetni.sade("kapi acik"))
        assertEquals("bagaj acik", KapiMetni.sade("BAGAJ\nAÇIK"))

        assertEquals(KapiMetni.Okunan(kapi = true, bagaj = false), KapiMetni.sinifla(listOf("Sol ön kapı açık")))
        assertEquals(KapiMetni.Okunan(kapi = false, bagaj = true), KapiMetni.sinifla(listOf(null, "Bagaj açık")))
        assertEquals(KapiMetni.Okunan(kapi = true, bagaj = true), KapiMetni.sinifla(listOf("Kapı açık", "Bagaj açık")))
        assertTrue(KapiMetni.sinifla(listOf("display door info: Open", "Kapı kapalı", "")).bos)
    }

    @Test
    fun metinSozcukTabanli() {
        val kapi = KapiMetni.Okunan(kapi = true, bagaj = false)
        val bagaj = KapiMetni.Okunan(kapi = false, bagaj = true)
        assertEquals(kapi, KapiMetni.sinifla(listOf("Kapılar açık")))
        assertEquals(kapi, KapiMetni.sinifla(listOf("KAPILAR AÇIK!")))
        assertEquals(bagaj, KapiMetni.sinifla(listOf("Bagaj kapağı açık")))
        assertEquals(bagaj, KapiMetni.sinifla(listOf("Bagaj kapısı açık"))) // boot lid, not a door
        // Words split over nodes: the window's texts joined once more.
        assertEquals(kapi, KapiMetni.sinifla(listOf("Kapı", "açık")))
        assertEquals(bagaj, KapiMetni.sinifla(listOf("Bagaj", null, "AÇIK")))
        assertEquals(KapiMetni.Okunan(kapi = true, bagaj = true), KapiMetni.sinifla(listOf("Kapı ve bagaj açık")))
        // Closed / unrelated texts are not "open".
        assertTrue(KapiMetni.sinifla(listOf("Kapı kapalı")).bos)
        assertTrue(KapiMetni.sinifla(listOf("Kapı", "kapalı")).bos)
        assertTrue(KapiMetni.sinifla(listOf("Kapak açık")).bos)
        assertTrue(KapiMetni.sinifla(listOf("açık")).bos)
    }

    // --- Interpreter ---

    private fun p(id: Int, vararg m: String, paket: String = AUTOCAN) = KapiYorumlayici.Pencere(id, paket, "CanDoorWindow", m.toList())

    @Test
    fun yorumlayici_kapiBagajKapandi() {
        val y = KapiYorumlayici(kapanisBeklemesi = 0)
        assertNull(y.kapiAcik())
        assertNull(y.bagajAcik())

        // No popup on screen says nothing: both stay unknown.
        y.pencereler(emptyList())
        assertNull(y.kapiAcik())
        assertNull(y.bagajAcik())

        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 7)
        assertEquals(true, y.kapiAcik())
        assertNull(y.bagajAcik()) // the door popup says nothing about the boot
        y.pencereler(listOf(p(7, "Kapı açık")))
        assertEquals(true, y.kapiAcik())

        // Door popup closes, boot popup opens.
        y.pencereler(listOf(p(8, "Bagaj açık")))
        assertEquals(false, y.kapiAcik())
        assertEquals(true, y.bagajAcik())

        // Both at once.
        y.pencereler(listOf(p(8, "Bagaj açık"), p(9, "Kapı açık")))
        assertEquals(true, y.kapiAcik())
        assertEquals(true, y.bagajAcik())

        // All closed.
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
        assertEquals(false, y.bagajAcik())
    }

    @Test
    fun yorumlayici_baskaPaketVeOkunamayanPencere() {
        val y = KapiYorumlayici(kapanisBeklemesi = 0)
        assertFalse(y.durumOlayi(BASKA, "X", listOf("Kapı açık"), 3))
        y.pencereler(listOf(p(3, "Kapı açık", paket = BASKA)))
        assertNull(y.kapiAcik())

        y.pencereler(listOf(p(5, "Kapı açık")))
        assertEquals(true, y.kapiAcik())
        // Root briefly unreadable: not taken as closed.
        y.pencereler(emptyList(), okunamayan = setOf(5))
        assertEquals(true, y.kapiAcik())
        // An empty text in the same window does not clear what was read before.
        y.pencereler(listOf(p(5)))
        assertEquals(true, y.kapiAcik())
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
    }

    @Test
    fun yorumlayici_listedeGorulmeyenKayit_onSaniyeSonraDuser() {
        var t = 0L
        val y = KapiYorumlayici({ t }, 0)
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 11)
        assertTrue(y.dogrulanmamisVar())
        t = 5_000
        y.pencereler(emptyList()) // never listed (research decision 7): kept for now
        assertEquals(true, y.kapiAcik())
        assertEquals(1, Teshis.listeDisiPencere)
        t = 10_000
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
        assertEquals(1, Teshis.kapiZamanAsimi)
    }

    @Test
    fun yorumlayici_listedeDogrulanmisKayitZamanAsimiYok() {
        var t = 0L
        val y = KapiYorumlayici({ t }, 0)
        y.pencereler(listOf(p(7, "Kapı açık")))
        t = 600_000 // ten minutes with the door open
        y.pencereler(listOf(p(7, "Kapı açık")))
        y.pencereler(emptyList(), okunamayan = setOf(7))
        assertEquals(true, y.kapiAcik())
        assertEquals(0, Teshis.kapiZamanAsimi)
    }

    @Test
    fun yorumlayici_listeOlaydanOnceGelirse_takilmaz() {
        // TYPE_WINDOWS_CHANGED pass first (window without text yet), then the state event.
        val y = KapiYorumlayici({ 0L }, 0)
        y.pencereler(listOf(p(7)))
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 7)
        assertEquals(true, y.kapiAcik())
        assertFalse(y.dogrulanmamisVar()) // counted as listed: the id was in the last list
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
    }

    @Test
    fun yorumlayici_ayniPencereKapidanBagaja() {
        val y = KapiYorumlayici({ 0L }, 0)
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 7)
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Bagaj açık"), 7)
        assertEquals(false, y.kapiAcik())
        assertEquals(true, y.bagajAcik())
    }

    // --- Closed by the user (X) vs. the door closing (0.2.2) ---

    @Test
    fun yorumlayici_XileKapatilinca_bilinmiyor() {
        var t = 0L
        val y = KapiYorumlayici({ t }, 0)
        y.pencereler(listOf(p(7, "Kapı açık")))
        assertEquals(true, y.kapiAcik())
        t = 1_000
        assertTrue(y.tiklama(AUTOCAN, 7))
        t = 1_800 // gone 0.8 s after the click
        y.pencereler(emptyList())
        assertNull(y.kapiAcik()) // the door may still be open: unknown, not closed
        assertEquals(1, Teshis.kullaniciKapatti)
    }

    @Test
    fun yorumlayici_tiklamadanGecKapaninca_kapandi() {
        var t = 0L
        val y = KapiYorumlayici({ t }, 0)
        y.pencereler(listOf(p(7, "Kapı açık"), p(8, "Bagaj açık")))
        t = 1_000
        y.tiklama(AUTOCAN, 7)
        t = 2_501 // 1.501 s later: the popup went by itself
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
        assertEquals(false, y.bagajAcik())
        assertEquals(0, Teshis.kullaniciKapatti)

        // A click in another app does not count; neither does a click in another autocan window.
        y.pencereler(listOf(p(9, "Kapı açık"), p(10, "Bagaj açık")))
        assertFalse(y.tiklama(BASKA, 9))
        y.tiklama(AUTOCAN, 10)
        t = 3_000
        y.pencereler(listOf(p(10, "Bagaj açık")))
        assertEquals(false, y.kapiAcik()) // window 9 closed by itself
        y.pencereler(emptyList())
        assertNull(y.bagajAcik()) // window 10 closed by its X
    }

    @Test
    fun yorumlayici_penceresiBilinmeyenTiklama_hepsineSayilir() {
        var t = 0L
        val y = KapiYorumlayici({ t }, 0)
        y.pencereler(listOf(p(7, "Kapı açık")))
        y.tiklama(AUTOCAN, -1)
        t = 500
        y.pencereler(emptyList())
        assertNull(y.kapiAcik())
        // Not kept beyond its 1.5 s.
        y.pencereler(listOf(p(8, "Kapı açık")))
        t = 2_100
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
    }

    @Test
    fun servis_XeBasilincaBilinmiyor_kendiligindenKapanincaKapandi() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        assertEquals(KapiKaynagi.ERISILEBILIRLIK, AracVerisi.durum.kapiKaynagi)

        // The user taps the popup's X; the popup goes.
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_VIEW_CLICKED, AUTOCAN, null, 7))
        sh.setWindows(emptyList())
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik) // not "closed": no "Kapı kapandı."
        assertTrue(Teshis.kapiOlaylari().any { it.contains("tıklama 7") })
        assertTrue(Teshis.kapiOlaylari().any { it.contains("kullanıcı kapattı") })

        // Next popup; another app's click and an old click of this one do not count.
        sh.setWindows(listOf(pencere(9, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 9))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_VIEW_CLICKED, AUTOCAN, null, 9))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_VIEW_CLICKED, BASKA, null, 9))
        // The door closes: the vendor takes the popup away by itself.
        sh.setWindows(emptyList())
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertEquals(false, AracVerisi.durum.kapiAcik)
        assertTrue(Teshis.kapiOlaylari().none { it.contains(BASKA) })

        // Still read only.
        assertTrue(sh.globalActionsPerformed.isEmpty())
        assertTrue(sh.gesturesDispatched.isEmpty())
    }

    // --- Click after the window left (R1 ÖNEMLİ-2): the leaving is held, then decided ---

    @Test
    fun yorumlayici_tiklamaKaybolmadanSonraGelirse_bilinmiyor() {
        var t = 0L
        val y = KapiYorumlayici({ t }) // the real hold
        assertEquals(KapiYorumlayici.KAPANIS_BEKLEME_MS, y.bekleme)
        y.pencereler(listOf(p(7, "Kapı açık")))
        // The X removed the window first; the pass sees it gone before the click event came.
        t = 1_000
        y.pencereler(emptyList())
        assertEquals("held, not closed yet", true, y.kapiAcik())
        assertTrue(y.kapanisBekleniyor())
        t = 1_150
        assertTrue(y.tiklama(AUTOCAN, 7, an = 1_050)) // stamped when the event arrived
        t = 1_000 + KapiYorumlayici.KAPANIS_BEKLEME_MS
        y.pencereler(emptyList())
        assertNull("closed by the user: unknown, no \"Kapı kapandı.\"", y.kapiAcik())
        assertFalse(y.kapanisBekleniyor())
        assertEquals(1, Teshis.kullaniciKapatti)
    }

    @Test
    fun yorumlayici_tiklamasizKaybolma_beklemedenSonraKapandi() {
        var t = 0L
        val y = KapiYorumlayici({ t })
        y.pencereler(listOf(p(7, "Kapı açık")))
        t = 1_000
        y.pencereler(emptyList())
        t = 1_000 + KapiYorumlayici.KAPANIS_BEKLEME_MS - 1
        y.pencereler(emptyList()) // another pass during the hold decides nothing
        assertEquals(true, y.kapiAcik())
        t = 1_000 + KapiYorumlayici.KAPANIS_BEKLEME_MS
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik()) // went by itself: closed
        assertEquals(0, Teshis.kullaniciKapatti)
    }

    @Test
    fun yorumlayici_bekletilirkenGeriGelirse_acikKalir() {
        var t = 0L
        val y = KapiYorumlayici({ t })
        y.pencereler(listOf(p(7, "Kapı açık")))
        t = 1_000
        y.pencereler(emptyList()) // briefly missing from one list
        t = 1_200
        y.pencereler(listOf(p(7, "Kapı açık")))
        assertFalse(y.kapanisBekleniyor())
        t = 5_000
        y.pencereler(listOf(p(7, "Kapı açık")))
        assertEquals(true, y.kapiAcik())
        // A click long before the window finally goes is not the X.
        y.tiklama(AUTOCAN, 7, an = 5_000)
        t = 9_000
        y.pencereler(emptyList())
        t = 9_000 + KapiYorumlayici.KAPANIS_BEKLEME_MS
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
    }

    @Test
    fun servis_tersSira_onceKaybolmaSonraTiklama() {
        KapiServisi.kapanisBeklemesi = null // the real 400 ms
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        gecikenler.clear()
        gecikmeler.clear()

        // The window list changes first (the X detached the popup), the click event comes after.
        sh.setWindows(emptyList())
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertEquals("held: still open", true, AracVerisi.durum.kapiAcik)
        assertEquals(1, gecikenler.size) // one pass after the hold
        assertTrue(gecikmeler.single() >= KapiYorumlayici.KAPANIS_BEKLEME_MS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_VIEW_CLICKED, AUTOCAN, null, 7))
        bosalt()
        // The click's own pass decides at once: closed by the user, unknown, never "closed".
        assertNull("the X, not the door: no \"Kapı kapandı.\"", AracVerisi.durum.kapiAcik)
        assertTrue(Teshis.kapiOlaylari().any { it.contains("kullanıcı kapattı") })

        // The pass planned after the hold finds nothing left and plans nothing more.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(gecikmeler.single()))
        gecikenler.removeAt(0).run()
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
        assertEquals(1, Teshis.kullaniciKapatti)
        assertTrue(gecikenler.isEmpty())
    }

    @Test
    fun servis_tiklamaZamaniOlayGelinceAlinir() {
        // Worker jobs held back (a slow getWindows on the unit): the click is stamped on arrival.
        KapiServisi.kapanisBeklemesi = null
        val kuyruk = ArrayList<Runnable>()
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        KapiServisi.yurutucu = Executor { kuyruk += it }
        // A click in the popup's body; the worker gets to it 3 s later.
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_VIEW_CLICKED, AUTOCAN, null, 7))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_000))
        kuyruk.toList().forEach { it.run() }
        kuyruk.clear()
        KapiServisi.yurutucu = Executor { it.run() }
        // Then the popup goes by itself (the door closed): the 3 s old click is not the X.
        sh.setWindows(emptyList())
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KapiYorumlayici.KAPANIS_BEKLEME_MS + 100))
        gecikenler.toList().forEach { it.run() }
        bosalt()
        assertEquals(false, AracVerisi.durum.kapiAcik)
        assertEquals(0, Teshis.kullaniciKapatti)
    }

    // --- Click subscription only while needed (R1 ÖNEMLİ-4) ---

    @Test
    fun servis_tiklamaAboneligi_yalnizPencereVarkenVeOzellikDegilken() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)
        s.onServiceConnected()
        bosalt()
        assertTrue("nothing subscribed without a popup", abonelikler.isEmpty())
        assertFalse(Teshis.tiklamaAboneligi)

        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(listOf(true), abonelikler)
        assertTrue(Teshis.tiklamaAboneligi)
        // Other windows coming and going change nothing.
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        assertEquals(listOf(true), abonelikler)

        sh.setWindows(emptyList())
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertEquals(listOf(true, false), abonelikler)
        assertFalse(Teshis.tiklamaAboneligi)

        // The properties take over while a popup is up: the next event of any app unsubscribes.
        sh.setWindows(listOf(pencere(8, AUTOCAN, "Bagaj açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Bagaj açık", 8))
        assertEquals(listOf(true, false, true), abonelikler)
        KapiOzellikleri.birincil = true
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_VIEW_CLICKED, BASKA, null, 3))
        assertEquals(listOf(true, false, true, false), abonelikler)
        // With the properties as source a popup never subscribes again.
        sh.setWindows(listOf(pencere(9, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 9))
        assertEquals(listOf(true, false, true, false), abonelikler)
        assertTrue(Teshis.kapiOlaylari().any { it.contains("Kapı açık") }) // still read and logged
    }

    @Test
    fun servis_gercekAbonelik_robolectrictePatlamaz() {
        // Without the test hook the service calls its own get/setServiceInfo (no connection here: nothing).
        KapiServisi.abonelikKaydi = null
        val s = Robolectric.setupService(KapiServisi::class.java)
        shadowOf(s).setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        assertNull(Teshis.sonHata)
    }

    // --- The service with Robolectric's accessibility shadows ---

    private fun olay(tur: Int, paket: String?, metin: String?, pencereId: Int): AccessibilityEvent =
        AccessibilityEvent.obtain(tur).apply {
            packageName = paket
            className = "com.hcn.autocan.CanDoorWindow"
            if (metin != null) text.add(metin)
            // setWindowId is hidden in the SDK stubs; it exists in the 8.1 framework.
            AccessibilityEvent::class.java.getMethod("setWindowId", Int::class.javaPrimitiveType).invoke(this, pencereId)
        }

    private fun pencere(id: Int, paket: String, vararg metin: String): AccessibilityWindowInfo {
        val kok = AccessibilityNodeInfo.obtain().apply { packageName = paket; className = "android.widget.FrameLayout" }
        for (m in metin) {
            val c = AccessibilityNodeInfo.obtain().apply { packageName = paket; text = m }
            shadowOf(kok).addChild(c)
        }
        return AccessibilityWindowInfo.obtain().also {
            shadowOf(it).setId(id)
            shadowOf(it).setRoot(kok)
        }
    }

    @Test
    fun servis_kapiBagajKapandi_baskaPaketYokSayilir() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)

        // Another app's window event with the same words: ignored.
        sh.setWindows(listOf(pencere(3, BASKA, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, BASKA, "Kapı açık", 3))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
        assertNull(AracVerisi.durum.bagajAcik)

        // Door popup.
        sh.setWindows(listOf(pencere(3, BASKA), pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        assertNull(AracVerisi.durum.bagajAcik)

        // Door closes, boot opens (text only in the window's nodes, not in the event).
        sh.setWindows(listOf(pencere(8, AUTOCAN, "Bagaj açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, null, 8))
        bosalt()
        assertEquals(false, AracVerisi.durum.kapiAcik)
        assertEquals(true, AracVerisi.durum.bagajAcik)

        // Popup gone.
        sh.setWindows(listOf(pencere(3, BASKA)))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertEquals(false, AracVerisi.durum.kapiAcik)
        assertEquals(false, AracVerisi.durum.bagajAcik)

        // Only autocan events are kept for diagnostics, never the other app's.
        val olaylar = Teshis.kapiOlaylari()
        assertTrue(olaylar.isNotEmpty())
        assertTrue(olaylar.none { it.contains(BASKA) })

        // Read only: no global action, no gesture.
        assertTrue(sh.globalActionsPerformed.isEmpty())
        assertTrue(sh.gesturesDispatched.isEmpty())

        // Service switched off: unknown, not closed.
        s.onDestroy()
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
        assertNull(AracVerisi.durum.bagajAcik)
    }

    @Test
    fun servis_baglanincaAcikPencereyiGorur() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        shadowOf(s).setWindows(listOf(pencere(4, BASKA), pencere(9, AUTOCAN, "Bagaj", "açık")))
        s.onServiceConnected()
        bosalt()
        assertTrue(Teshis.kapiServisiBagli)
        assertEquals(true, AracVerisi.durum.bagajAcik)
        assertNull(AracVerisi.durum.kapiAcik) // the boot popup says nothing about the doors
    }

    @Test
    fun servis_ayniPenceredeOlayIleKapidanBagaja() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Bagaj açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Bagaj açık", 7))
        bosalt()
        assertEquals(false, AracVerisi.durum.kapiAcik)
        assertEquals(true, AracVerisi.durum.bagajAcik)

        // The first event came before any list pass, so a delayed pass was scheduled. The record has
        // been confirmed by the list since: that pass must not drop it, however late it runs.
        assertTrue(gecikenler.isNotEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60_000))
        gecikenler.toList().forEach { it.run() }
        bosalt()
        assertEquals(true, AracVerisi.durum.bagajAcik)
    }

    @Test
    fun servis_listedeGorulmeyenPencere_gecikmeliTaramaIleDuser() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        shadowOf(s).setWindows(emptyList()) // popup not interactive: never in the list
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 11))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)
        assertEquals(1, gecikenler.size)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KapiYorumlayici.ZAMAN_ASIMI_MS + 500))
        gecikenler.removeAt(0).run()
        bosalt()
        assertEquals(false, AracVerisi.durum.kapiAcik)
    }

    @Test
    fun servis_yokEdildiktenSonraBekleyenYazmaDusurulur() {
        // Worker jobs held back, as if still queued when the system destroys the service.
        val kuyruk = ArrayList<Runnable>()
        KapiServisi.yurutucu = Executor { kuyruk += it }
        val s = Robolectric.setupService(KapiServisi::class.java)
        shadowOf(s).setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        s.onDestroy()
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
        kuyruk.toList().forEach { it.run() } // the late worker jobs
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik) // still unknown, not "open"
    }

    @Test
    fun servis_uygulamaIciSifirlama() {
        val s = Robolectric.setupService(KapiServisi::class.java)
        val sh = shadowOf(s)
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onServiceConnected()
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)

        // Stuck: the popup went without any event. The diagnostics reset forgets it.
        sh.setWindows(emptyList())
        KapiServisi.uygulamaIciSifirla()
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
        assertNull(AracVerisi.durum.bagajAcik)
        assertTrue(Teshis.kapiOlaylari().any { it.contains("uygulama içi sıfırlama") })
        // The records really went: a later list pass without the popup says "unknown", not "closed".
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, null, null, -1))
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)

        // The popup comes again: written again (the service's last written value was reset too).
        sh.setWindows(listOf(pencere(7, AUTOCAN, "Kapı açık")))
        s.onAccessibilityEvent(olay(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AUTOCAN, "Kapı açık", 7))
        bosalt()
        assertEquals(true, AracVerisi.durum.kapiAcik)

        // Memory only: no global action, no gesture.
        assertTrue(sh.globalActionsPerformed.isEmpty())
        assertTrue(sh.gesturesDispatched.isEmpty())

        // Without a connected service the shared state is still cleared.
        s.onDestroy()
        bosalt()
        AracVerisi.guncelle { kapiAcik(true) }
        KapiServisi.uygulamaIciSifirla()
        bosalt()
        assertNull(AracVerisi.durum.kapiAcik)
    }

    @Test
    fun eskiKapiAlaniTuretilir() {
        @Suppress("DEPRECATION")
        run {
            assertNull(AracDurumu().kapi)
            assertNull(AracDurumu(kapiAcik = false).kapi) // boot unknown: not "closed"
            assertEquals(KapiDurumu.KAPALI, AracDurumu(kapiAcik = false, bagajAcik = false).kapi)
            assertEquals(KapiDurumu.KAPI_ACIK, AracDurumu(kapiAcik = true, bagajAcik = true).kapi)
            assertEquals(KapiDurumu.BAGAJ_ACIK, AracDurumu(bagajAcik = true).kapi)
        }
    }
}
