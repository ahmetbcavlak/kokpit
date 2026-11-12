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
}
