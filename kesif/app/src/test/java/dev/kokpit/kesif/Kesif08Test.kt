package dev.kokpit.kesif

import android.app.Application
import android.location.Location
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class Kesif08Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun clear() {
        GpsGuard.feed(null)
        AirKeys.setApkCheck("test: APK doğrulandı", false)
    }

    // ---------- GPS guard ----------

    private fun fix(kmh: Float?, ageMs: Long = 0): Location = Location("gps").apply {
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000
        if (kmh != null) speed = kmh / 3.6f
    }

    @Test
    fun guardNeedsFreshSlowGps() {
        assertEquals("GPS yok", GpsGuard.blockReason())
        GpsGuard.feed(fix(0f)); assertNull(GpsGuard.blockReason())
        GpsGuard.feed(fix(0.9f)); assertNull(GpsGuard.blockReason())
        GpsGuard.feed(fix(1.5f)); assertTrue(GpsGuard.blockReason()!!.startsWith("araç hareket ediyor"))
        GpsGuard.feed(fix(0f, ageMs = 5000)); assertTrue(GpsGuard.blockReason()!!.startsWith("GPS eski"))
        GpsGuard.feed(fix(null)); assertEquals("GPS hız vermiyor", GpsGuard.blockReason())
        GpsGuard.feed(fix(Float.NaN)); assertTrue(GpsGuard.blockReason()!!.startsWith("araç hareket ediyor"))
        GpsGuard.feed(fix(0f, ageMs = -5000)); assertEquals("GPS zamanı geçersiz", GpsGuard.blockReason())
    }

    // ---------- climate keys ----------

    /** A stand-in for the vendor service: records every airCtrl call as (key, action). */
    private class FakeAir(private val desc: String, private val failPress: Boolean = false, private val failFirstRelease: Boolean = false) : android.os.Binder() {
        val calls = CopyOnWriteArrayList<Pair<Int, Int>>()
        private var releases = 0
        override fun getInterfaceDescriptor(): String = desc
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (code != AirKeys.TRANSACTION) return false
            data.enforceInterface(desc)
            val key = data.readInt(); val action = data.readInt()
            calls.add(key to action)
            if (action == AirKeys.PRESS && failPress) throw RuntimeException("basış hatası")
            if (action == AirKeys.RELEASE && failFirstRelease && releases++ == 0) throw RuntimeException("bırakma hatası")
            reply?.writeNoException()
            return true
        }
    }

    private fun run(fake: FakeAir, key: AirKeys.Key = AirKeys.keys[0]): String {
        val intent = android.content.Intent(ServiceProbe.ACTION).setPackage(ServiceProbe.PKG)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(intent,
            android.content.ComponentName(ServiceProbe.PKG, "com.hcn.autocan.CanService"), fake)
        var done = false
        assertTrue(AirKeys.send(app, key) { done = true })
        assertFalse(AirKeys.send(app, key) { }) // a second tap while busy is refused
        val until = System.currentTimeMillis() + 12_000
        while (!done && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)); Thread.sleep(20)
        }
        assertTrue(done)
        return AirKeys.lastResult.orEmpty().also { println(it) }
    }

    @Test
    fun pressThenRelease() {
        GpsGuard.feed(fix(0f))
        val fake = FakeAir(AirKeys.DESCRIPTOR)
        val r = run(fake, AirKeys.keys.first { it.code == 4 })
        assertEquals(listOf(4 to 0, 4 to 1), fake.calls.toList())
        assertTrue(r.contains("gönderildi ·"))
        // The row is written by the log's writer thread; wait for it.
        val f = AirKeys.log.file(app)
        val until = System.currentTimeMillis() + 5000
        while (!(f.exists() && f.readText().contains("Sol sıcaklık +")) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(f.readText().contains("Sol sıcaklık +"))
    }

    @Test
    fun releaseIsSentEvenWhenPressFails() {
        GpsGuard.feed(fix(0f))
        val fake = FakeAir(AirKeys.DESCRIPTOR, failPress = true)
        val r = run(fake)
        assertEquals(listOf(2 to 0, 2 to 1), fake.calls.toList())
        assertTrue(r.contains("hata"))
    }

    @Test
    fun failedReleaseIsTriedOnceMore() {
        GpsGuard.feed(fix(0f))
        val fake = FakeAir(AirKeys.DESCRIPTOR, failFirstRelease = true)
        run(fake)
        assertEquals(listOf(2 to 0, 2 to 1, 2 to 1), fake.calls.toList())
    }

    @Test
    fun nothingGoesToAnotherInterface() {
        GpsGuard.feed(fix(0f))
        val fake = FakeAir("com.hcn.autocan.aidl.ICanService")
        val r = run(fake)
        assertTrue(fake.calls.isEmpty())
        assertTrue(r.contains("komut gönderilmedi"))
    }

    @Test
    fun nothingIsSentWhileMovingOrWithoutGps() {
        assertFalse(AirKeys.send(app, AirKeys.keys[0]) { })
        assertTrue(AirKeys.lastResult!!.contains("GPS yok"))
        GpsGuard.feed(fix(20f))
        assertFalse(AirKeys.send(app, AirKeys.keys[0]) { })
        assertTrue(AirKeys.lastResult!!.contains("hareket"))
    }

    @Test
    fun onlyTheFourKeys() {
        GpsGuard.feed(fix(0f))
        assertEquals(listOf(2, 3, 4, 5), AirKeys.keys.map { it.code })
        assertFalse(AirKeys.send(app, AirKeys.Key(16, "A/C", "x")) { })
        assertFalse(AirKeys.send(app, AirKeys.Key(1, "aç/kapa", "x")) { })
    }

    @Test
    fun nothingBeforeTheApkCheck() {
        GpsGuard.feed(fix(0f))
        AirKeys.setApkCheck(null, false)
        assertEquals("APK denetleniyor…", AirKeys.blockReason())
        assertFalse(AirKeys.send(app, AirKeys.keys[0]) { })
    }

    @Test
    fun apkMismatchBlocks() {
        GpsGuard.feed(fix(0f))
        AirKeys.setApkCheck("APK uyuşmuyor: airCtrl #7", mismatch = true)
        assertEquals("APK uyuşmuyor: airCtrl #7", AirKeys.blockReason())
        assertFalse(AirKeys.send(app, AirKeys.keys[0]) { })
    }

    // ---------- door properties ----------

    @Test
    fun flagsAreFoundInSettings() {
        assertEquals("", DoorProps.readFlag(app, "can_ignore_doorinfo"))
        Settings.System.putString(app.contentResolver, "can_ignore_doorinfo", "1")
        Settings.Global.putString(app.contentResolver, "close_avm_door_flag", "0")
        assertEquals("Settings.System=1", DoorProps.readFlag(app, "can_ignore_doorinfo"))
        assertEquals("Settings.Global=0", DoorProps.readFlag(app, "close_avm_door_flag"))
    }

    @Test
    fun doorChangesAreReportedOnce() {
        DoorProps.poll(app) // first values are not changes
        Settings.System.putString(app.contentResolver, "can_ignore_doorinfo", "1")
        val c = DoorProps.poll(app)
        assertEquals(1, c.size)
        assertEquals("can_ignore_doorinfo", c[0].id)
        assertEquals("Settings.System=1", c[0].new)
        assertTrue(DoorProps.poll(app).isEmpty())
    }

    // ---------- dex strings ----------

    /** A minimal dex with a string table only: enough for the parser. */
    private fun dex(strings: List<String>): ByteArray {
        val header = 0x70
        val ids = header
        val dataStart = ids + strings.size * 4
        val data = ByteArrayOutputStream()
        val offs = ArrayList<Int>()
        for (s in strings) {
            offs.add(dataStart + data.size())
            val b = s.toByteArray(Charsets.US_ASCII)
            data.write(b.size) // uleb128, < 128
            data.write(b); data.write(0)
        }
        val out = ByteArray(dataStart + data.size())
        "dex\n035\u0000".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        fun le(v: Int, at: Int) { for (i in 0..3) out[at + i] = (v shr (8 * i)).toByte() }
        le(out.size, 0x20)
        le(strings.size, 0x38); le(ids, 0x3C)
        offs.forEachIndexed { i, o -> le(o, ids + i * 4) }
        data.toByteArray().copyInto(out, dataStart)
        return out
    }

    @Test
    fun dexStringsAndActionFilter() {
        val list = listOf("com.hcn.bt.ACTION_MUSIC_INFO", "android.permission.BLUETOOTH", "res/drawable/play.png",
            "ACTION_BT_CONNECT", "hello world", "com.hcn.media.id3", "short")
        val got = DexStrings.strings(dex(list), 0)
        assertTrue(got.containsAll(listOf("com.hcn.bt.ACTION_MUSIC_INFO", "ACTION_BT_CONNECT", "com.hcn.media.id3")))
        assertFalse(got.contains("hello world")) // has a space
        val acts = got.filter { DexStrings.looksLikeAction(it) }
        assertEquals(setOf("com.hcn.bt.ACTION_MUSIC_INFO", "ACTION_BT_CONNECT", "com.hcn.media.id3"), acts.toSet())
        // A dex inside a bigger file (as in a .vdex): offsets are relative to its own start.
        val inner = dex(list)
        val outer = ByteArray(1000) + inner + ByteArray(50)
        assertEquals(got, DexStrings.strings(outer, 1000, inner.size))
        // Garbage never crashes.
        assertTrue(DexStrings.strings(ByteArray(200) { 0x7F }, 0).isEmpty())
        assertTrue(DexStrings.strings(ByteArray(10), 0).isEmpty())
    }

    // ---------- pages build ----------

    @Test
    fun pagesBuildAndTick() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        listOf(DoorPage(a) {}, MediaPage(a) {}, AirPage(a) {}).forEach { p ->
            p.onShow(); p.tick(); p.onHide()
        }
        DoorProps.stopRecording(a)
    }
}
