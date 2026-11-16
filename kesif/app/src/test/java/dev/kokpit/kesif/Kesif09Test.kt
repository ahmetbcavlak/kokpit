package dev.kokpit.kesif

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class Kesif09Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun touchStats() {
        TouchStats.clear()
        (1..20).forEach { TouchStats.add(TouchStats.Tap(it, 1.0, it.toDouble(), it + 16.0)) }
        TouchStats.add(TouchStats.Tap(21, 0.0, 999.0, 999.0)) // over 20: ignored
        assertTrue(TouchStats.done)
        assertEquals(10.5, TouchStats.mean(TouchStats.all().map { it.frameMs }), 1e-9)
        assertEquals(19.0, TouchStats.p95(TouchStats.all().map { it.frameMs }), 1e-9)
        assertEquals(0.0, TouchStats.p95(emptyList()), 0.0)
        TouchStats.clear()
    }

    @Test
    fun brightnessGoesBackAfterThreeSeconds() {
        Brightness.canWriteForTest = true
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 200)
        var done = false
        assertTrue(Brightness.stepTest(app) { done = true })
        assertEquals(200 - Brightness.STEP, Brightness.value(app))
        assertFalse(Brightness.stepTest(app) { }) // one at a time
        idle(Brightness.HOLD_MS + 100)
        assertTrue(done)
        assertEquals(200, Brightness.value(app))
        assertFalse(app.getSharedPreferences("parlaklik", 0).contains("old"))
    }

    @Test
    fun brightnessInterruptedIsRestoredAtStart() {
        Brightness.canWriteForTest = true
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 80)
        app.getSharedPreferences("parlaklik", 0).edit().putInt("old", 150).commit()
        Brightness.restorePending(app)
        assertEquals(150, Brightness.value(app))
    }

    @Test
    fun brightnessNeedsPermission() {
        Brightness.canWriteForTest = false
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 120)
        assertFalse(Brightness.stepTest(app) { })
        assertEquals(120, Brightness.value(app))
    }

    @Test
    fun apkProviderServesOnlyTheOneFile() {
        val f = Updater.file(app).apply { parentFile?.mkdirs(); writeBytes(ByteArray(1234) { 7 }) }
        val p = Robolectric.setupContentProvider(ApkSaglayici::class.java, ApkSaglayici.AUTHORITY)
        assertEquals("application/vnd.android.package-archive", p.getType(ApkSaglayici.URI))
        p.query(ApkSaglayici.URI, null, null, null, null)!!.use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(ApkSaglayici.NAME, c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)))
            assertEquals(1234L, c.getLong(c.getColumnIndex(OpenableColumns.SIZE)))
        }
        assertNull(p.query(Uri.parse("content://${ApkSaglayici.AUTHORITY}/baska"), null, null, null, null))
        val refused = runCatching { p.openFile(ApkSaglayici.URI, "w") }
        assertTrue(refused.exceptionOrNull() is SecurityException)
        f.delete()
    }

    @Test
    fun openingMarksFirstOnly() {
        val start = SystemClock.elapsedRealtime()
        Acilis.begin(app, "sıcak", start, "test")
        Acilis.first("ilk GPS konumu", start + 500)
        Acilis.first("ilk GPS konumu", start + 900) // second one ignored
        Acilis.first("ilk klima yayını", start + 200)
        val m = Acilis.marks()
        assertEquals(listOf("başlangıç", "ilk GPS konumu", "ilk klima yayını"), m.map { it.key })
        assertEquals(500L, m[1].sinceStart)
        // A cached fix from before the opening is not "first".
        Acilis.begin(app, "sıcak", start, "test 2")
        Acilis.onFix(android.location.Location("gps").apply { elapsedRealtimeNanos = (start - 5000) * 1_000_000 })
        assertEquals(listOf("başlangıç"), Acilis.marks().map { it.key })
    }

    @Test
    fun armingIsTimeLimited() {
        Acilis.arm(app)
        assertTrue(Acilis.armed(app))
        assertTrue(Acilis.armedUntil(app) - System.currentTimeMillis() <= 24L * 3600 * 1000)
        Acilis.disarm(app)
        assertFalse(Acilis.armed(app))
    }

    @Test
    fun toneIsAValidWav() {
        val f = ShortSound.file(app)
        val b = f.readBytes()
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals(44 + 44_100 * ShortSound.MS / 1000 * 2, b.size)
    }

    @Test
    fun updateCheckAfterStart() {
        val prefs = app.getSharedPreferences("guncelleme", 0)
        prefs.edit().putLong("from", 10).putLong("to", 11).putLong("at", System.currentTimeMillis()).commit()
        Updater.checkAfterStart(app)
        assertFalse(prefs.contains("to"))
        assertFalse(Updater.openInstaller(Robolectric.buildActivity(MainActivity::class.java).setup().get())) // nothing downloaded
    }

    @Test
    fun yolPagesBuildAndTick() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        listOf(YolPage(a, {}) {}, InternetPage(a) {}, UpdatePage(a) {}, AcilisPage(a) {}, TouchPage(a) {}, SoundPage(a) {}, BrightnessPage(a) {})
            .forEach { p -> p.onShow(); p.tick(); p.onHide() }
        assertTrue(InternetProbe.network(app).isNotEmpty())
    }
}
