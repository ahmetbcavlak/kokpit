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

    @Before
    fun hazirla() {
        KapiServisi.yurutucu = Executor { it.run() }
        KapiServisi.gecikmeli = { _, r -> gecikenler += r }
    }

    @After
    fun temizle() {
        KapiServisi.yurutucu = null
        KapiServisi.gecikmeli = null
        AracVerisi.sifirla()
        Teshis.sifirla()
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
        val y = KapiYorumlayici()
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
        val y = KapiYorumlayici()
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
        val y = KapiYorumlayici { t }
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
        val y = KapiYorumlayici { t }
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
        val y = KapiYorumlayici { 0L }
        y.pencereler(listOf(p(7)))
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 7)
        assertEquals(true, y.kapiAcik())
        assertFalse(y.dogrulanmamisVar()) // counted as listed: the id was in the last list
        y.pencereler(emptyList())
        assertEquals(false, y.kapiAcik())
    }

    @Test
    fun yorumlayici_ayniPencereKapidanBagaja() {
        val y = KapiYorumlayici { 0L }
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 7)
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Bagaj açık"), 7)
        assertEquals(false, y.kapiAcik())
        assertEquals(true, y.bagajAcik())
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
