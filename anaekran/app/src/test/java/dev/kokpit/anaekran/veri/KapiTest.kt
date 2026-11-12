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
import java.util.concurrent.Executor

/** Door/boot from com.hcn.autocan's popup, with fake accessibility events and windows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KapiTest {

    private val AUTOCAN = KapiMetni.PAKET
    private val BASKA = "com.example.baska"

    @Before
    fun hazirla() {
        KapiServisi.yurutucu = Executor { it.run() }
    }

    @After
    fun temizle() {
        KapiServisi.yurutucu = null
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
    fun yorumlayici_listedeGorulmeyenPencereKapanmaz() {
        val y = KapiYorumlayici()
        y.durumOlayi(AUTOCAN, "CanDoorWindow", listOf("Kapı açık"), 11)
        y.pencereler(emptyList()) // never listed (research decision 7): no time-out, stays open
        y.pencereler(emptyList())
        assertEquals(true, y.kapiAcik())
        assertEquals(1, Teshis.listeDisiPencere)
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
