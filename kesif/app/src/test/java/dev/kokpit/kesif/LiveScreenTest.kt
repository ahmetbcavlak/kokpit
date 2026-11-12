package dev.kokpit.kesif

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the live screen at the head unit's size (768×1024, 160 dpi, Android 8.1)
 * with made-up values, and checks the recorder's file. The PNGs are previews only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w768dp-h1024dp-mdpi")
class LiveScreenTest {

    private val out = File("build/preview").apply { mkdirs() }
    private val p = "persist.vendor.cem."

    private fun render(name: String, a: Activity) {
        BuildConfigCompat.version = "0.4.0"
        val screen = LiveScreen(a, onReport = {}, address = { "http://192.168.43.120:8080" })
        screen.tick()
        // What the driver sees: 1024 minus the unit's status bar and climate bar (about 900 px).
        snap("$name", screen.root, 900)
        // Everything, scrolled out.
        val c = screen.content
        (c.parent as android.view.ViewGroup).removeView(c)
        snap("$name-tam", c, 0)
    }

    private fun snap(name: String, v: View, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(768, View.MeasureSpec.EXACTLY),
            if (h > 0) View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, 768, v.measuredHeight)
        val bmp = Bitmap.createBitmap(768, v.measuredHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Palette.bg)
        v.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("preview $name: 768x${v.measuredHeight}")
    }

    @Test
    fun previewAndRecord() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        Recorder.init(a)

        // 1) Just opened, nothing read yet.
        render("1-bos", a)

        // 2) Values present, unchanged.
        val t = System.currentTimeMillis() - 30_000
        (1..5).forEach { LiveProbe.feed("${p}cardoor$it", "1", at = t) }
        LiveProbe.feed("${p}speed", "0", at = t)
        LiveProbe.feed("persist.sys.cem.speed.from.can", "1", at = t)
        LiveProbe.feed("${p}angle", "0", at = t)
        (0..7).forEach { LiveProbe.feed("${p}radar$it", "0", at = t) }
        (0..2).forEach { LiveProbe.feed("${p}light$it", "0", at = t) }
        LiveProbe.feed("${LiveProbe.AIR}/KEY_VALUE_LEFT_TEMP", "22", at = t)
        LiveProbe.feed("${LiveProbe.AIR}/KEY_VALUE_RIGHT_TEMP", "22", at = t)
        LiveProbe.feed("${LiveProbe.AIR}/KEY_VALUE_WIND_LEVEL", "2", at = t)
        LiveProbe.feed("persist.vendor.cem.dang", "P", unknown = true, at = t)
        render("2-degerler", a)

        // 3) During the visit: recording on, a door and the boot open, wheel turned, gear moved.
        Recorder.start()
        Recorder.mark("3.1")
        LiveProbe.feed("${p}cardoor1", "2")
        LiveProbe.feed("${p}cardoor5", "2")
        LiveProbe.feed("${p}angle", "-215")
        LiveProbe.feed("${p}radar5", "3")
        LiveProbe.feed("${p}radar6", "7")
        LiveProbe.feed("${LiveProbe.AIR}/KEY_VALUE_LEFT_TEMP", "24")
        LiveProbe.feed("persist.vendor.cem.dang", "R", unknown = true)
        render("3-test-sirasinda", a)

        Recorder.stop()
        val f = Recorder.latest()!!
        val lines = f.readLines()
        println(lines.joinToString("\n"))
        assertEquals("zaman,saniye,tur,anahtar,eski,yeni,not", lines.first().removePrefix("\uFEFF"))
        assertTrue(lines.any { it.contains(",isaret,") && it.endsWith(",3.1") })
        assertTrue(lines.any { it.contains(",deger,${p}cardoor1,1,2,") })
        assertTrue(lines.any { it.contains(",bilinmeyen,persist.vendor.cem.dang,P,R,") })
        assertTrue(Recorder.asJson(f).contains("\"tur\":\"isaret\""))

        // The live JSON for the iPad page must parse and carry every card.
        val j = org.json.JSONObject(LiveProbe.json())
        assertEquals(LiveProbe.signals.size, j.getJSONArray("signals").length())
        val gear = (0 until j.getJSONArray("signals").length()).map { j.getJSONArray("signals").getJSONObject(it) }.first { it.getString("id") == "vites" }
        assertEquals("LIVE", gear.getString("status"))
    }
}
