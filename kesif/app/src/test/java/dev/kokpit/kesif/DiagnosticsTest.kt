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

    @Test
    fun climateCandidateIsStrict() {
        assertTrue(AirCommand.looksLikeTempUp("tempUp"))
        assertTrue(AirCommand.looksLikeTempUp("setLeftTempAdd"))
        assertTrue(AirCommand.looksLikeTempUp("addTemp"))
        assertFalse(AirCommand.looksLikeTempUp("tempDown"))
        assertFalse(AirCommand.looksLikeTempUp("windLevelUp"))
        assertFalse(AirCommand.looksLikeTempUp("getTemp"))
        assertFalse(AirCommand.looksLikeTempUp("onTempUpdate"))
        for (bad in listOf("setTempUnitInC", "isTempUnitSupported", "tempFanUp", "setTempModeUp", "setTemperaturePowerUp",
                "setTempAutoWindUp", "powerUpTemp", "tempUpDown", "getTempSinceBoot", "tempPopup", "tempBackup",
                "tempUpgrade", "rightTempUp", "passengerTempUp", "TEMP_UP_ALL", "tempDec", "tempUpR", "tempUpRR", "rearTempUp",
                "backTempUp", "coTempUp", "fuTempUp", "seatHeatTempUp", "steerWheelTempUp", "addTempOffset", "setTempUpLimit"))
            assertFalse(bad, AirCommand.looksLikeTempUp(bad))
        for (good in listOf("tempUp", "setLeftTempUp", "DriverTempUp", "addTemp", "TEMP_UP", "temperatureIncrease"))
            assertTrue(good, AirCommand.looksLikeTempUp(good))
        fun c(m: String) = AirCommand.Candidate("x.IAirCtrlServices", "x.IAirCtrlServices", m, "void $m()", 1)
        AirCommand.reset("x.IAirCtrlServices")
        AirCommand.choose(listOf(c("tempUp")))
        assertEquals("tempUp", AirCommand.candidate?.method)
        assertTrue(AirCommand.ready)
        AirCommand.choose(listOf(c("tempUp"), c("tempAdd")))
        assertEquals(null, AirCommand.candidate) // two, neither says left/driver: ambiguous
        AirCommand.choose(listOf(c("leftTempUp"), c("tempAdd")))
        assertEquals("leftTempUp", AirCommand.candidate?.method)
        // The service reports another interface: never ready.
        AirCommand.reset("x.ICanService")
        AirCommand.choose(listOf(c("tempUp")))
        assertFalse(AirCommand.ready)
    }

    private class FakeAir(private val desc: String) : android.os.Binder() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        override fun getInterfaceDescriptor(): String = desc
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(desc)
            calls.incrementAndGet()
            reply?.writeNoException()
            return true
        }
    }

    private fun sendWith(fakeDesc: String, candDesc: String): Pair<Int, String> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fake = FakeAir(fakeDesc)
        val intent = android.content.Intent(ServiceProbe.ACTION).setPackage(ServiceProbe.PKG)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent,
            android.content.ComponentName(ServiceProbe.PKG, "com.hcn.autocan.CanService"), fake)
        val c = AirCommand.Candidate(candDesc, candDesc, "tempUp", "void tempUp()", 7)
        AirCommand.reset(null); AirCommand.choose(listOf(c))
        var done = false
        assertTrue(AirCommand.send(app, c) { done = true })
        assertFalse(AirCommand.send(app, c) { }) // a second confirmation while busy is refused
        val until = System.currentTimeMillis() + 10_000
        while (!done && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(20)
        }
        assertTrue(done)
        return fake.calls.get() to AirCommand.lastResult.orEmpty()
    }

    @Test
    fun climateSendsExactlyOnce() {
        val (calls, result) = sendWith("x.IAirCtrlServices", "x.IAirCtrlServices")
        println(result)
        assertEquals(1, calls)
        assertTrue(result.startsWith("gönderildi"))
    }

    @Test
    fun climateSendsNothingToAnotherInterface() {
        val (calls, result) = sendWith("x.ICanService", "x.IAirCtrlServices")
        println(result)
        assertEquals(0, calls)
        assertTrue(result.contains("komut gönderilmedi"))
    }
}
