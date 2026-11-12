package dev.kokpit.kesif

import android.app.Application
import android.system.OsConstants
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class DiagnosticsTest {

    @Test
    fun permissionString() {
        assertEquals("crw-rw----", SerialPorts.permString(OsConstants.S_IFCHR or "660".toInt(8)))
        assertEquals("-rwxr-xr-x", SerialPorts.permString(OsConstants.S_IFREG or "755".toInt(8)))
    }

    @Test
    fun serialCheckNeverThrows() {
        // On the test machine the nodes do not exist; every port must still give a result.
        val r = SerialPorts.check()
        assertEquals(4, r.size)
        r.forEach { println(it.path + " → " + it.lines.joinToString(" | ") { l -> "${l.label}: ${l.value}" }) }
    }

    private fun event(type: Int, pkg: String, cls: String, text: String, desc: String?) =
        AccessibilityEvent.obtain(type).apply {
            packageName = pkg; className = cls; getText().add(text); contentDescription = desc
        }

    @Test
    fun windowLogKeepsOnlyAutocan() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WindowLog.file(app).delete()
        val svc = Robolectric.setupService(PencereGunlugu::class.java)
        svc.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.hcn.autocan",
            "com.hcn.autocan.CanDoorWindow", "Sol ön kapı açık", "kapı uyarısı"))
        svc.onAccessibilityEvent(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.whatsapp",
            "com.whatsapp.Conversation", "özel mesaj", null))
        svc.onAccessibilityEvent(event(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.hcn.autocan", "x", "y", null))
        // Writing happens on the service's worker thread.
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until && !WindowLog.file(app).let { it.exists() && it.readText().contains("CanDoorWindow") }) Thread.sleep(20)
        Thread.sleep(200)
        val csv = WindowLog.file(app).readText()
        println(csv)
        assertTrue(csv.contains("com.hcn.autocan.CanDoorWindow"))
        assertTrue(csv.contains("Sol ön kapı açık") && csv.contains("kapı uyarısı"))
        assertFalse(csv.contains("whatsapp") || csv.contains("özel mesaj"))
        assertFalse(csv.lines().any { it.contains(",x,") })
    }

    @Test
    fun serviceCheckWithoutVendorApp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = ServiceProbe.staticInfo(app)
        assertTrue(info.first().value.contains("yüklü değil"))
        var done = false
        ServiceProbe.runBindTest(app) { done = true }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
        assertTrue(done)
        val r = ServiceProbe.lastResult!!.associate { it.label to it.value }
        println(r)
        // Robolectric connects with a null binder: not a usable connection.
        assertEquals("false", r["Bağlanılabilir"])
    }

    @Test
    fun closeTestDoesNothingWithoutService() {
        val msg = CloseTest.request()
        println(msg)
        assertTrue(msg.contains("kapalı"))
        assertFalse(CloseTest.running || CloseTest.armed)
    }

    @Test
    fun interfaceListingWithoutVendorApp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var done = false
        ServiceProbe.listInterface(app) { done = true }
        val until = System.currentTimeMillis() + 8000
        while (!done && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); Thread.sleep(50)
        }
        assertTrue(done)
        val lines = ServiceProbe.interfaceLines!!
        println(lines.joinToString("\n") { "${it.label}: ${it.value}" })
        assertTrue(lines.any { it.label == "APK" && it.value.contains("bulunamadı") })
        assertTrue(ServiceProbe.interfaceFile(app).readText().contains("arayüz listesi"))
    }

    @Test
    fun doorWindowWordsAreWholeWords() {
        // Door warning: yes
        assertTrue(UiDump.doorish("com.hcn.autocan.CanDoorWindow", name = true))
        assertTrue(UiDump.doorish("iv_door_fl", name = true))
        assertTrue(UiDump.doorish("Sol ön kapı açık", name = false))
        assertTrue(UiDump.doorish("Bagaj kapısı açık", name = false))
        assertTrue(UiDump.doorish("Door open", name = false))
        assertTrue(UiDump.doorish("Kapıyı kapatın", name = false))
        assertTrue(UiDump.doorish("Kapılar açık", name = false))
        // Climate and others: no
        assertFalse(UiDump.doorish("com.hcn.autocan.OutdoorTempView", name = true))
        assertFalse(UiDump.doorish("tv_outdoor_temp", name = true))
        assertFalse(UiDump.doorish("Outdoor 18°C", name = false))
        assertFalse(UiDump.doorish("Indoor", name = false))
        assertFalse(UiDump.doorish("kapalı", name = false))
        assertFalse(UiDump.doorish("Klima kapatıldı", name = false))
    }
}
